# Attachments and object storage

Attachments let a business record carry files — an invoice PDF, a scanned form, a product image —
stored as durable objects outside the database and addressed from SQL. The feature has three parts:
a durable object-store SPI (`BlobStore`) with a local default and an opt-in S3 implementation; a
**record-attachment recipe** that streams uploads off-heap, writes metadata through 2-way SQL, and
serves downloads under the same authorization a query gets; and the governance (deny-by-default
egress, virus scanning, retention) that keeps it safe.

It builds on existing subsystems rather than inventing parallel machinery:

- **The spool/`TempStore` model** (`tesseraql-core` `io.tesseraql.core.spool`) — the off-heap
  streaming primitives (`SpoolWriter`, `SpoolRef`) that already keep large payloads out of the heap.
- **The file-transfer machinery** (`FileTransferService` / `JdbcFileTransferService`) — the
  asynchronous, off-heap, operations-tracked transfer path behind `file-import`/`file-export` and
  the `poll:` trigger ([managed connectors](connectors.md)). Uploads ride it; downloads stream from
  the store the same way.
- **2-way SQL + policies + scoping** — attachment metadata is a row, so reading an attachment is a
  `SELECT` under the same `policy:` and `/*%scope ... */` machinery every other query gets.
  Authorization is never a second, bespoke code path (see [data scoping](data-scoping.md)).
- **The SecretResolver SPI** (`io.tesseraql.yaml.secret`) — object-store credentials resolve from
  `${secret.env.*}` / `${secret.file.*}` lazily, never logged, never written to an artifact.
- **The retention sweeper** (`RetentionSweeper`) — orphaned and aged blobs are reclaimed by the same
  cluster-safe timer that purges the outbox and job history.
- **The opt-in leaf-module pattern** ([tesseraql-oidc](authentication.md), `tesseraql-pdf`) — the
  heavy object-store SDK lives in a leaf module apps install only if they need it.

## Why a new `BlobStore`, not the `TempStore` SPI

The `TempStore` SPI (`io.tesseraql.core.spool.TempStore`) is for **ephemeral spool**: rowsets,
export intermediates, CSV/JSONL buffers. Its lifecycle is *create → read once → delete*; `SpoolRef`
carries a row count, and the store is free to reclaim a spool as soon as a transfer finishes.
Attachments are the opposite: **durable, addressable, retained** business objects whose deletion is
governed by retention, never by the store. Overriding `TempStore` with an S3 implementation — the
naive reading of "S3-compatible `TempStore`" — would push spool traffic into object storage and
conflate two lifecycles that must stay separate.

TesseraQL therefore provides a sibling SPI, **`BlobStore`** (`io.tesseraql.core.blob`), for durable
objects, and reuses the spool *streaming primitives* (off-heap write, checksum-as-you-stream) rather
than the spool *store*. The two SPIs coexist: a deployment can keep spool on local disk and put
attachments in S3, or vice versa. (An app that genuinely wants spool in S3 can also install an
`S3TempStore` from the same leaf module — a one-line bonus, not the main event.)

```java
package io.tesseraql.core.blob;

public interface BlobStore {

    /** Opens a writer streaming bytes to a new durable object; its ref is available on close. */
    BlobWriter createWriter(BlobSpec spec);        // spec: contentType, optional filename hint

    /** Opens an input stream over a stored object. */
    InputStream openInput(BlobRef ref) throws IOException;

    /** Whether the object still exists. */
    boolean exists(BlobRef ref);

    /** Deletes the object, ignoring a missing target. Called only by retention, never implicitly. */
    void delete(BlobRef ref);

    /** A short-lived pre-signed GET URL, or empty if the provider does not support one. */
    Optional<URI> presignGet(BlobRef ref, Duration ttl);
}
```

`BlobRef` is an immutable record — `key`, `contentType`, `byteSize`, `checksum` (SHA-256 hex,
computed while streaming), `createdAt` — and never carries the bytes. `BlobWriter` mirrors
`SpoolWriter`: `write(...)` during streaming, `toRef()` after `close()`. The surface is deliberately
the **minimal portable intersection** of every S3-compatible store: put, get, exists, delete, and an
optional presign. Tagging, ACLs, object-lock, and lifecycle rules are *not* on the SPI, because they
vary across providers — keeping them off the interface is what makes portability hold (see
[S3-compatible storage](#s3-compatible-storage)).

Two built-in implementations:

- **`FileBlobStore`** (`tesseraql-core`, default) — durable objects under `{appHome}/work/blob/…`,
  `java.nio` only, no new dependency. Works out of the box; the local-development and single-node
  default.
- **`S3BlobStore`** (`tesseraql-s3` leaf module) — AWS SDK for Java v2, confined to the opt-in
  module so its weight never reaches an app that does not store blobs in S3.

## The record-attachment recipe

An attachment is a durable blob **plus** a metadata row that ties it to a business record. The
metadata follows IAM's managed/app duality (like the [approval workflow](approval-workflow.md)
`WorkflowStore` and the [data scoping](data-scoping.md) `OrgUnitStore`), selected per document or
globally by `tesseraql.attachments.mode`:

- **`managed`** (default) — the runtime provisions and owns a generic `tql_attachment` table
  (`id`, `entity`, `entity_id`, `filename`, `content_type`, `byte_size`, `checksum`, `storage_key`,
  `scan_status`, `created_by`, `created_at`) behind an `AttachmentStore` SPI (`tesseraql-core`) with
  a `JdbcAttachmentStore` impl (`tesseraql-operations`). Unlike workflow state (often already a
  column) or org units (an app may already have them), attachment metadata is a brand-new generic
  concern with no pre-existing app table, so the managed table is the low-friction default.
- **`app`** — the application owns its own attachment columns/table and supplies the `insert:` /
  `select:` 2-way SQL; nothing managed is provisioned. For apps that want attachments first-class in
  their own schema.

A `kind: attachment` document under `attachments/` declares the binding and **synthesizes two
routes** — an upload `POST` and a download `GET` — the way `kind: workflow` synthesizes a route per
transition:

```yaml
# attachments/invoice_files.yml
version: tesseraql/v1
id: invoice_files
kind: attachment
basePath: /invoices/{invoiceId}/files
record: { entity: invoice, key: invoiceId }     # the owning record, bound from the path
bucket: app-uploads                              # a logical bucket; see tesseraql.object-storage
limits:
  maxBytes: 25MB
  contentTypes: [application/pdf, image/png, image/jpeg]
scan: require-clean                              # gate downloads on a clean scan
# managed mode needs nothing more; app mode adds:
# metadata:
#   mode: app
#   insert: sql/attach_invoice_file.sql          # /* audit.* */ binds allowed
#   select: sql/fetch_invoice_file.sql           # /*%scope ... */ for row authorization
```

### Upload — off-heap, then commit

`POST {basePath}` (multipart) runs the same path as `file-import`:

1. The body streams **off-heap** through the file-transfer machinery; SHA-256 and byte size are
   computed during the stream (never materialized in memory), and `limits` are enforced as bytes
   flow.
2. The durable object is written to the `BlobStore`, yielding a `BlobRef`.
3. The metadata row is inserted in a DB transaction — managed (`AttachmentStore.record(...)`) or the
   app's `insert:` 2-way SQL — binding `storage_key`, `content_type`, `byte_size`, `checksum`, the
   record reference, and the canonical `/* audit.user */` / `/* audit.now */` binds
   ([transactional writes](transactional-writes.md)).

The blob write (step 2) is **not** transactional — like the `http-call` step
([managed connectors](connectors.md)) and the transactional outbox ([messaging](messaging.md)), an
external side effect cannot be rolled back. If the transaction in step 3 fails or the request aborts
after step 2, the blob is **orphaned**; a best-effort delete on the failure path covers the common
case, and retention reclaims aged blobs (see [Retention](#retention)). This is the same "commit the
record, reconcile the side effect" discipline the framework already uses, not a new failure model.

### Download — authorization is a `SELECT`

`GET {basePath}/{attachmentId}` is where the SQL-first design pays off:

1. The framework runs the metadata `SELECT` — managed default or the app's `select:` — under the
   route's `policy:` and any `/*%scope ... */` directive it carries. **If the caller may not see the
   row, the query returns nothing and the download is `404`** (`403` when policy denies outright).
   Attachment access control is therefore *the same SELECT under the same policy and row scoping
   every other read gets* — there is no second authorization path to get wrong.
2. If `scan: require-clean` and the row is not `scan_status = clean`, the download is refused (`409`).
3. The blob streams from the `BlobStore` with `Content-Disposition: attachment; filename="…"` and
   the stored `content_type` — or, when the provider supports it and presigning is enabled, a `302`
   to a short-lived pre-signed GET URL so the bytes never transit the app. The presign is issued
   **only after** step 1 authorizes, so offloading transfer never bypasses authorization.

An `attach:` job-pipeline step — so a batch can attach a generated file, e.g. a `pdf` export
([printable documents](printable-documents.md)), to a record — is planned but not currently
supported. It would compose with the `BlobStore` and `AttachmentStore` exactly as the routes do.

## S3-compatible storage

The `tesseraql-s3` leaf module ships `S3BlobStore` on **AWS SDK for Java v2**, whose `S3Client`
targets any S3-compatible store — AWS S3, Cloudflare R2, Ceph RGW, Backblaze B2, Wasabi — by
overriding the endpoint. **One module covers AWS and every compatible store**; there is no
per-provider module.

```yaml
tesseraql:
  object-storage:
    provider: s3                       # default: file (FileBlobStore)
    buckets:
      app-uploads: { bucket: acme-app-uploads }   # logical name → real bucket
    s3:
      endpoint: https://s3.us-east-1.amazonaws.com # or https://<acct>.r2.cloudflarestorage.com, http://minio:9000
      region: us-east-1                # required by the signer even for compatible stores (a dummy is fine)
      pathStyle: false                 # true for MinIO/Ceph; false for AWS/R2
      checksumMode: when-required      # see the checksum note below
      credentials:
        accessKey: ${secret.env.OBJECT_STORE_ACCESS_KEY}
        secretKey: ${secret.file.object_store_secret_key}
    allowedBuckets:                    # deny-by-default egress allow-list (mirrors http.outbound)
      - acme-app-uploads
```

Compatibility settings that the config exposes because they bite in practice:

- **`pathStyle`** — AWS and R2 accept virtual-hosted-style (`bucket.endpoint`); MinIO and Ceph
  generally need **path-style** (`endpoint/bucket`).
- **`region`** — the SigV4 signer requires a region even when it is meaningless to a compatible
  store; default `us-east-1`.
- **`checksumMode`** — AWS SDK v2 (2.30+) defaults to adding CRC32 request checksums, which some
  compatible stores reject. `checksumMode: when-required` maps to
  `requestChecksumCalculation = WHEN_REQUIRED` / `responseChecksumValidation = WHEN_REQUIRED`,
  restoring compatibility; `default` keeps the SDK behavior for AWS.

`S3BlobStore` is contributed by a `BlobStoreProvider` discovered through `ServiceLoader` (the
PdfEngine/FileCodec idiom) and selected when `provider: s3`, binding itself as the `BlobStore`.
**Switching `provider` is the whole change** — no DSL touches the bytes, so an app moves from local
disk to S3 by config alone.

## Governance

Object storage is **egress**, so it is deny-by-default like outbound HTTP and poll connectors:

- **`tesseraql.object-storage.allowedBuckets`** is an explicit allow-list; an attachment document
  whose `bucket` resolves to a bucket not on the list fails the build (it is never silently served).
- **Credentials** resolve through the SecretResolver at call time, never inlined, never logged,
  never written to a generated artifact.
- The SDK client stays an implementation detail — apps see the `bucket`/`limits`/`scan` recipe
  surface, never raw SDK options.

### Scanning

A **scan-hook SPI**, `AttachmentScanner` (`tesseraql-core` `io.tesseraql.core.scan`,
ServiceLoader-discovered via `AttachmentScanners.discover()`), is the seam for ClamAV or a cloud
malware-scan service. The default is a no-op that reports `clean` without reading the bytes, so an
app enables real scanning by adding a scanner module — no config flag.

```java
package io.tesseraql.core.scan;

public interface AttachmentScanner {
    String id();
    ScanVerdict scan(BlobRef ref, ContentSource content);   // CLEAN | INFECTED | ERROR
}
```

Scanning is **synchronous on upload**: the scanner runs on the stored object (the content is supplied
lazily, so the no-op default never opens the blob), and the verdict is recorded as `scan_status`. A
`CLEAN` object is served normally; an `INFECTED` object is recorded `infected` and **never served**
(the download gate refuses any non-clean object with `409`), and is kept or removed per
`tesseraql.attachments.scan.onInfected: quarantine | delete` (default `quarantine`); a scanner
`ERROR` fails the upload closed (`503`). An asynchronous `pending → scanned` model is not currently
supported; synchronous keeps the gate simple and needs no scan sweeper.

### Retention

Attachment retention wires into the `RetentionSweeper` (the cluster-safe timer that already purges
the outbox and job history), gated by `tesseraql.retention.attachments`:

- **Age policy** — attachment metadata past the configured window is deleted and each blob reclaimed
  (best-effort, so a concurrent node or an already-removed blob is harmless). Driven by the sweep
  rather than provider lifecycle rules **on purpose**: server-side lifecycle support varies across
  S3-compatible stores, so the portable, observable path is the in-app sweep.
- **Orphan GC** (not currently supported) — reclaiming a blob whose `storage_key` matches no
  metadata row needs a store-listing capability the minimal `BlobStore` SPI does not expose; the
  upload path's best-effort delete-on-failure already covers the common case. A planned refinement.

## Lint and coverage

The attachment surface is machine-checkable like every other recipe. The attachment document has its
own lint family (`TQL-ATTACH-34xx`, mirroring the `TQL-WORKFLOW`/`TQL-SCOPE` per-kind families rather
than crowding the `TQL-YAML` loader-error number space); object-storage egress takes `TQL-SEC-411x`
(`TQL-SEC-4100` is reserved for the write-scope bypass check in [data scoping](data-scoping.md)):

| Code | Severity | Meaning |
| --- | --- | --- |
| `TQL-ATTACH-3401` | error | a `kind: attachment` document does not declare `kind: attachment` |
| `TQL-ATTACH-3402` | error | a `kind: attachment` document is missing `basePath` |
| `TQL-ATTACH-3403` | error | a `kind: attachment` document is missing `record.entity`/`record.key` |
| `TQL-ATTACH-3404` | error | the `basePath` does not contain the record key as a path parameter |
| `TQL-ATTACH-3405` | error | `limits.maxBytes` is missing or unparseable |
| `TQL-SEC-4110` | error | with `provider: s3`, an attachment's resolved bucket is not in `tesseraql.object-storage.allowedBuckets` (deny-by-default) |

Scanning adds no lint — it is config + a ServiceLoader scanner, with no new YAML surface — and is
enforced at runtime instead (the codes below).

The runtime fails closed, all mapped from the `TQL-LD-284x` codes the processors/service raise: a
`404` when an attachment is unknown or owned by a different record (never leaked across records), a
`413` past the size limit, a `415` for a disallowed content type, a `409` for a download of an object
that did not pass scanning, and a `503` when the scanner cannot reach a verdict (fail-closed).

An **`attachment`** coverage kind (one item per `kind: attachment` document, gated with
`coverage.thresholds.attachment`) is planned but not currently supported: it needs a declarative test
target that exercises the upload/download round-trip, which pulls the attachment runtime into the
test harness. The `TQL-ATTACH-34xx` lint already makes the attachment surface machine-checkable in
the meantime.

## Module layout

| Module | Adds |
| --- | --- |
| `tesseraql-core` | `io.tesseraql.core.blob` (`BlobStore`, `BlobWriter`, `BlobRef`, `BlobSpec`), `FileBlobStore`, `AttachmentStore` SPI, `io.tesseraql.core.scan.AttachmentScanner` + no-op default — all dependency-free |
| `tesseraql-yaml` | the `kind: attachment` model + parser, the `object-storage`/`attachments`/`retention.attachments` config, and the lint (`TQL-ATTACH-34xx`, `TQL-SEC-411x`) |
| `tesseraql-compiler` | `buildAttachment` — synthesizes the upload/download routes and binds the `AttachmentStore` |
| `tesseraql-operations` | `JdbcAttachmentStore` (managed `tql_attachment`), the off-heap upload/download integration with `FileTransferService`, and the `RetentionSweeper` attachments pass |
| `tesseraql-s3` | `S3BlobStore` + `S3BlobStoreProvider` (ServiceLoader), AWS SDK v2 (Apache-2.0) confined here, and the S3/compatible config. The `BlobStoreProvider` SPI + `BlobStores` factory live in `tesseraql-yaml` |

## Design notes

- **Object-store client.** AWS SDK for Java v2 (Apache-2.0), confined to the `tesseraql-s3` leaf
  module, is the chosen client: standard, robust multipart/retry/presign, and it covers AWS and all
  S3-compatible stores from one module. A JDK-only SigV4 signer over `java.net.http.HttpClient` —
  in the spirit of the JDK-only OIDC/JWKS and mTLS choices — would keep the supply chain minimal
  but reimplement multipart, retries, and presigning; the `BlobStore` SPI is the seam that would
  let it replace AWS SDK v2 later without touching the DSL.
- **No MinIO, even as a test fixture.** The MinIO *server* is AGPLv3 (since 2021), and its community
  edition is widely described as in maintenance mode. Although using an unmodified AGPL server as a
  separate-process test fixture would not propagate AGPL to TesseraQL, the project's
  supply-chain-minimal ethos and AGPL-flagging license scanners make it cleaner to avoid entirely.
  The S3-compatibility integration tests run against **Adobe S3Mock** (Apache-2.0) via
  Testcontainers — they are the compatibility gate that catches the checksum and path-style issues
  above. What TesseraQL ships is AWS SDK v2 (Apache-2.0); no MinIO code is embedded or distributed.
- **`ServiceLoader` provider, not a `RuntimeExtension`.** A `RuntimeExtension` would install too
  late — after the attachment service is constructed — so the `BlobStoreProvider` factory, resolved
  inside the same wiring step, is the right seam.
- **Metadata mode default.** `managed` (the framework `tql_attachment` table) is the default,
  because attachment metadata is a new generic concern with no pre-existing app table; `app` mode is
  available per document for apps that want attachments in their own schema. Authorization flows
  through a 2-way SQL `SELECT` in **both** modes, so policy and row scoping apply uniformly.
