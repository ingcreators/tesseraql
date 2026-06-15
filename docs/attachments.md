# Attachments and object storage

> **Status: planned (roadmap Phase 30).** This document is the design; nothing here ships yet.
> Three slices are planned: attachment core on a durable `BlobStore` with a local default
> (slice 1); the opt-in `tesseraql-s3` leaf module for S3 and S3-compatible stores (slice 2);
> the scan-hook SPI and retention wiring that complete the phase (slice 3). Lint codes and config
> keys below are proposals, confirmed against the linter and config schema at implementation time.

Attachments let a business record carry files — an invoice PDF, a scanned form, a product image —
stored as durable objects outside the database and addressed from SQL. The phase adds three things:
a durable object-store SPI (`BlobStore`) with a local default and an opt-in S3 implementation; a
**record-attachment recipe** that streams uploads off-heap, writes metadata through 2-way SQL, and
serves downloads under the same authorization a query gets; and the governance (deny-by-default
egress, virus scanning, retention) that keeps it safe.

It builds on existing subsystems rather than inventing parallel machinery:

- **The spool/`TempStore` model** (`tesseraql-core` `io.tesseraql.core.spool`) — the off-heap
  streaming primitives (`SpoolWriter`, `SpoolRef`) that already keep large payloads out of the heap.
- **The file-transfer machinery** (`FileTransferService` / `JdbcFileTransferService`) — the
  asynchronous, off-heap, operations-tracked transfer path behind `file-import`/`file-export` and
  the Phase 26 `poll:` trigger. Uploads ride it; downloads stream from the store the same way.
- **2-way SQL + policies + scoping** — attachment metadata is a row, so reading an attachment is a
  `SELECT` under the same `policy:` and Phase 29 `/*%scope ... */` machinery every other query gets.
  Authorization is never a second, bespoke code path (see [data-scoping.md](data-scoping.md)).
- **The SecretResolver SPI** (`io.tesseraql.yaml.secret`) — object-store credentials resolve from
  `${secret.env.*}` / `${secret.file.*}` lazily, never logged, never written to an artifact.
- **The retention sweeper** (`RetentionSweeper`, design ch. 44) — orphaned and aged blobs are
  reclaimed by the same cluster-safe timer that purges the outbox and job history.
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

So Phase 30 introduces a sibling SPI, **`BlobStore`** (`io.tesseraql.core.blob`), for durable
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
- **`S3BlobStore`** (`tesseraql-s3` leaf module, slice 2) — AWS SDK for Java v2, confined to the
  opt-in module so its weight never reaches an app that does not store blobs in S3.

## The record-attachment recipe

An attachment is a durable blob **plus** a metadata row that ties it to a business record. The
metadata follows IAM's managed/app duality (like `WorkflowStore` and `OrgUnitStore`), selected per
document or globally by `tesseraql.attachments.mode`:

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
scan: require-clean                              # gate downloads on a clean scan (slice 3)
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
   record reference, and the canonical `/* audit.user */` / `/* audit.now */` binds (Phase 18).

The blob write (step 2) is **not** transactional — like the Phase 26 `http-call` step and the
Phase 27 outbox, an external side effect cannot be rolled back. If the transaction in step 3 fails or
the request aborts after step 2, the blob is **orphaned**, and retention's orphan-GC reclaims it
(see [Retention](#retention)). This is the same "commit the record, reconcile the side effect"
discipline the framework already uses, not a new failure model.

### Download — authorization is a `SELECT`

`GET {basePath}/{attachmentId}` is where the SQL-first design pays off:

1. The framework runs the metadata `SELECT` — managed default or the app's `select:` — under the
   route's `policy:` and any `/*%scope ... */` directive it carries. **If the caller may not see the
   row, the query returns nothing and the download is `404`** (`403` when policy denies outright).
   Attachment access control is therefore *the same SELECT under the same policy and Phase 29 row
   scoping every other read gets* — there is no second authorization path to get wrong.
2. If `scan: require-clean` and the row is not `scan_status = clean`, the download is refused (`409`).
3. The blob streams from the `BlobStore` with `Content-Disposition: attachment; filename="…"` and
   the stored `content_type` — or, when the provider supports it and presigning is enabled, a `302`
   to a short-lived pre-signed GET URL so the bytes never transit the app. The presign is issued
   **only after** step 1 authorizes, so offloading transfer never bypasses authorization.

A job pipeline may also gain an `attach:` step (a later refinement) so a batch can attach a
generated file — e.g. a Phase 21 `pdf` export — to a record. It composes with the `BlobStore` and
`AttachmentStore` exactly as the route does.

## S3-compatible storage

The `tesseraql-s3` leaf module (slice 2) ships `S3BlobStore` on **AWS SDK for Java v2**, whose
`S3Client` targets any S3-compatible store — AWS S3, Cloudflare R2, Ceph RGW, Backblaze B2, Wasabi —
by overriding the endpoint. **One module covers AWS and every compatible store**; there is no
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

`S3BlobStore` self-installs via the `RuntimeExtension` SPI (like the OIDC RP) when
`provider: s3`, binding itself as the `BlobStore`. **Switching `provider` is the whole change** — no
DSL touches the bytes, so an app moves from local disk to S3 by config alone. The same module can
also register an `S3TempStore` for the spool path if an app sets `tesseraql.spool.provider: s3`.

## Governance

Object storage is **egress**, so it is deny-by-default like outbound HTTP and poll connectors:

- **`tesseraql.object-storage.allowedBuckets`** is an explicit allow-list; an attachment document
  whose `bucket` resolves to a bucket not on the list fails the build (it is never silently served).
- **Credentials** resolve through the SecretResolver at call time, never inlined, never logged,
  never written to a generated artifact (rule 6).
- The Camel/SDK client stays an implementation detail — apps see the `bucket`/`limits`/`scan` recipe
  surface, never raw SDK options (extension principle 2).

### Scanning

A **scan-hook SPI**, `AttachmentScanner` (`tesseraql-core`, ServiceLoader-discovered), is the seam
for ClamAV or a cloud malware-scan service. The default is a no-op that marks uploads `clean`.

```java
package io.tesseraql.core.scan;

public interface AttachmentScanner {
    String id();
    ScanResult scan(BlobRef ref, InputStream content);   // CLEAN | INFECTED(reason) | ERROR
}
```

When scanning is enabled, an upload's metadata is written `scan_status = pending`; an async sweep
(riding the existing transfer/operations path) scans and flips it to `clean` or `infected`. A
document with `scan: require-clean` refuses to serve anything not `clean` (slice 3 default behavior),
and an `infected` blob is quarantined (`tesseraql.attachments.scan.onInfected: quarantine | delete`).

### Retention

Attachment retention wires into the ch. 44 `RetentionSweeper` (the cluster-safe timer that already
purges the outbox and job history), gated by `tesseraql.retention.attachments`:

- **Orphan GC** (always on when retention runs) — a blob whose `storage_key` matches no metadata row
  is reclaimed, closing the non-transactional upload window above. Reconciliation lists store keys
  against the metadata `storage_key` set; cluster-safe and idempotent like the existing sweep.
- **Age policy** (optional) — blobs (and their metadata) past the configured window are deleted,
  tied to the record lifecycle.

Retention is driven by the sweeper rather than provider lifecycle rules **on purpose**: server-side
lifecycle support varies across S3-compatible stores, so the portable, observable path is the
in-app sweep. Provider lifecycle stays an optional optimization, never the mechanism.

## Lint and coverage

Machine-checkable like every other recipe (extension principle 4). The attachment document gets its
own lint family (`TQL-ATTACH-34xx`, mirroring the `TQL-WORKFLOW`/`TQL-SCOPE` per-kind families rather
than crowding the `TQL-YAML` loader-error number space); object-storage egress in slice 2 takes
`TQL-SEC-411x` (`TQL-SEC-4100` is reserved by Phase 29 for write-scope bypass):

| Code | Severity | Meaning | Status |
| --- | --- | --- | --- |
| `TQL-ATTACH-3401` | error | a `kind: attachment` document does not declare `kind: attachment` | slice 1 |
| `TQL-ATTACH-3402` | error | a `kind: attachment` document is missing `basePath` | slice 1 |
| `TQL-ATTACH-3403` | error | a `kind: attachment` document is missing `record.entity`/`record.key` | slice 1 |
| `TQL-ATTACH-3404` | error | the `basePath` does not contain the record key as a path parameter | slice 1 |
| `TQL-ATTACH-3405` | error | `limits.maxBytes` is missing or unparseable | slice 1 |
| `TQL-SEC-411x` | error | an attachment's resolved bucket is not in `tesseraql.object-storage.allowedBuckets` (deny-by-default) | slice 2 |
| `TQL-SEC-411x` | error | `provider: s3` without resolvable credentials, or `scan: require-clean` with no scanner installed | slice 2/3 |

The runtime fails closed: a `404` when an attachment is unknown or owned by a different record (an
attachment is never leaked across records), a `413` past the size limit, a `415` for a disallowed
content type — all mapped from the `TQL-LD-284x` codes the upload/download processors raise.

An **`attachment`** coverage kind (one item per `kind: attachment` document, gated with
`coverage.thresholds.attachment`) is the slice-1 follow-up: it needs a declarative test target that
exercises the upload/download round-trip, which pulls the attachment runtime into the test harness —
a focused change kept out of the core slice. The `TQL-ATTACH-34xx` lint already makes the attachment
surface machine-checkable in the meantime.

## Module layout

| Module | Adds |
| --- | --- |
| `tesseraql-core` | `io.tesseraql.core.blob` (`BlobStore`, `BlobWriter`, `BlobRef`, `BlobSpec`), `FileBlobStore`, `AttachmentStore` SPI, `io.tesseraql.core.scan.AttachmentScanner` + no-op default — all dependency-free (principle 5) |
| `tesseraql-yaml` | the `kind: attachment` model + parser, the `object-storage`/`attachments`/`retention.attachments` config, and the lint (`TQL-YAML-12xx`, `TQL-SEC-411x`) |
| `tesseraql-compiler` | `buildAttachment` — synthesizes the upload/download routes and binds the `AttachmentStore` |
| `tesseraql-operations` | `JdbcAttachmentStore` (managed `tql_attachment`), the off-heap upload/download integration with `FileTransferService`, and the `RetentionSweeper` attachments pass |
| `tesseraql-s3` (new) | `S3BlobStore` (+ optional `S3TempStore`), AWS SDK v2 (Apache-2.0) confined here, the `RuntimeExtension` self-install, and S3/compatible config — added to the root `pom.xml` `<modules>` and the BOM |

Integration tests for `S3BlobStore` run against **Adobe S3Mock** (Apache-2.0) via Testcontainers —
not MinIO; see the decision points. They are the compatibility gate that catches the checksum and
path-style issues above (consistent with the project's Testcontainers IT approach for backing
stores).

## Decision points

1. **Object-store SDK.** AWS SDK for Java v2 (Apache-2.0), confined to the `tesseraql-s3` leaf
   module, is the chosen client: standard, robust multipart/retry/presign, and it covers AWS and all
   S3-compatible stores from one module. The alternative — a JDK-only SigV4 signer over
   `java.net.http.HttpClient`, in the spirit of the JDK-only OIDC/JWKS and mTLS choices — keeps the
   supply chain minimal but reimplements multipart, retries, and presigning. Deferred; the
   `BlobStore` SPI is the seam that lets it replace AWS SDK v2 later without touching the DSL.
2. **Test fixture / MinIO.** MinIO is **not** used, even as a test fixture. The MinIO *server* is
   AGPLv3 (since 2021), and its community edition direction has soured (admin UI removed from the
   community build in 2025; widely described as in maintenance mode). Although using an unmodified
   AGPL server as a separate-process Testcontainers fixture does not propagate AGPL to TesseraQL,
   the project's supply-chain-minimal ethos and AGPL-flagging license scanners make it cleaner to
   avoid it entirely. The S3-compatibility ITs use **Adobe S3Mock** (Apache-2.0); a gated CI job may
   run against real AWS S3 for highest fidelity. What TesseraQL ships is AWS SDK v2 (Apache-2.0) —
   no MinIO code is embedded or distributed.
3. **Metadata mode default.** `managed` (the framework `tql_attachment` table) is the default,
   because attachment metadata is a new generic concern with no pre-existing app table; `app` mode is
   available per document for apps that want attachments in their own schema. Authorization flows
   through a 2-way SQL `SELECT` in **both** modes, so policy and Phase 29 scoping apply uniformly.

## Delivery slices

Phase 30 ships in slices, each a reviewable PR with CI green:

1. **Attachment core** — the `BlobStore`/`BlobWriter`/`BlobRef` SPI and `FileBlobStore` default
   (`tesseraql-core`), the `AttachmentStore` and `AttachmentService` SPIs with the managed
   `tql_attachment` store (`tesseraql-operations`), the `kind: attachment` document and its
   synthesized off-heap upload/list/download routes (`tesseraql-compiler`/runtime), and the
   `TQL-ATTACH-34xx` lint. No S3, no new module — ships value on local storage alone. **Delivered**
   for managed mode; app-mode 2-way SQL metadata and the `attachment` coverage kind are the
   immediate slice-1 follow-up.
2. **`tesseraql-s3` leaf module** — `S3BlobStore` on AWS SDK v2 with the `RuntimeExtension`
   self-install, the deny-by-default `allowedBuckets` egress and SecretResolver credentials, the
   `endpoint`/`region`/`pathStyle`/`checksumMode` compatibility settings, the `TQL-SEC-411x` lint,
   and the Adobe S3Mock compatibility ITs. Switching `provider: s3` is the whole change.
3. **Scanning and retention** — the `AttachmentScanner` scan-hook SPI with the no-op default and the
   `require-clean` download gate, and the `RetentionSweeper` attachments pass (orphan GC plus the
   optional age policy). Completes the phase.

This completes the attachments leg of **Milestone M9** (an approval-workflow application with
org-scoped data and attachments).
