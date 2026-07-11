# Attachments and object storage

Attachments let a business record carry files — an invoice PDF, a scanned form, a product image —
stored as durable objects outside the database and addressed from SQL. A `kind: attachment`
document binds uploads to their owning record and synthesizes the upload, list, and download
routes; the blobs land in a local file store by default, or in any S3-compatible store by config
alone; and governance (deny-by-default egress, virus scanning, retention) keeps the whole surface
safe. Uploads and downloads stream off-heap through the same file-transfer machinery behind
`file-import`/`file-export` ([file transfers](file-transfers.md)), so large files never
materialize in memory.

## The `kind: attachment` document

A `kind: attachment` document under `attachments/` declares the binding:

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
security:
  auth: bearer
  policy: invoices.write
```

It **synthesizes three routes** — the way `kind: workflow` synthesizes a route per transition:

| Route | Does |
| --- | --- |
| `POST {basePath}` | uploads one file (multipart), answers `201` with the stored metadata |
| `GET {basePath}` | lists the record's attachments as JSON (metadata only, no bytes) |
| `GET {basePath}/{attachmentId}` | streams the file back as a download |

The document's `security:` applies to all three routes exactly as on a hand-written route, and
the owning-record key in `basePath` scopes the list and the download to that record.

Attachment metadata is a row: the framework provisions and owns a generic `tql_attachment`
table (`id`, `entity`, `entity_id`, `filename`, `content_type`, `byte_size`, `checksum`,
`storage_key`, `scan_status`, `created_by`, `created_at`). `tesseraql.attachments.mode` selects
this `managed` default, or `app` for an application that keeps attachment metadata in its own
schema and opts out of the provisioned table.

## Uploading

`POST {basePath}` accepts a multipart request and reads the part named `file` (falling back to
the first file part, or the raw request body for non-multipart callers). A plain form is all a
page needs — the [mutating-form conventions](hypermedia-ui.md#mutating-forms) layer on like any
other form:

```html
<form method="post" enctype="multipart/form-data"
      th:action="|/invoices/${invoice.id}/files|">
  <input class="hc-input" type="file" name="file" required>
  <button class="hc-button" data-variant="primary" type="submit">Upload</button>
</form>
```

The body streams **off-heap**; SHA-256 and byte size are computed as the bytes flow, and
`limits` are enforced during the stream. The durable object is written to the blob store first,
then the metadata row is inserted in a DB transaction with the audit identity of the uploader.
A successful upload answers `201` with the stored metadata:

```json
{"id": "…", "filename": "invoice.pdf", "contentType": "application/pdf",
 "byteSize": 182044, "checksum": "…"}
```

The blob write is **not** transactional — like the `http-call` step
([managed connectors](connectors.md)) and the transactional outbox ([messaging](messaging.md)),
an external side effect cannot be rolled back. If the metadata insert fails or the request
aborts after the blob is written, a best-effort delete covers the common case and retention
reclaims aged blobs (see [Retention](#retention)). This is the same "commit the record,
reconcile the side effect" discipline the framework already uses, not a new failure model.

## Downloading

`GET {basePath}/{attachmentId}` loads the metadata **owner-scoped to the record in the path**:
an attachment owned by a different record reads as unknown and answers `404`, never leaked
across records. An object that did not pass virus scanning is refused with `409` (see
[Scanning](#scanning)). Otherwise the blob streams back with the stored content type and a
sanitized `Content-Disposition: attachment; filename="…"` header. A template renders a
download link from the metadata it queried:

```html
<a th:each="file : ${files}"
   th:href="|/invoices/${invoice.id}/files/${file.id}|"
   th:text="${file.filename}">invoice.pdf</a>
```

`GET {basePath}` returns the record's attachments as a JSON array — `id`, `filename`,
`contentType`, `byteSize`, `checksum`, `scanStatus`, `createdBy`, `createdAt` — without ever
reading the bytes.

## S3-compatible storage

By default blobs live under the app home's `work/blob/` directory — durable, dependency-free,
and right for local development and single-node deployments. The opt-in `tesseraql-s3` module
targets any S3-compatible store — AWS S3, Cloudflare R2, Ceph RGW, Backblaze B2, Wasabi — by
overriding the endpoint. **One module covers AWS and every compatible store**; there is no
per-provider module, and **switching `provider` is the whole change** — no DSL touches the
bytes, so an app moves from local disk to S3 by config alone.

```yaml
tesseraql:
  attachments:
    mode: managed                      # managed (default): the framework owns the metadata table
    scan:
      onInfected: quarantine           # quarantine (default) | delete; see Scanning below
  object-storage:
    provider: s3                       # default: file (the local blob store)
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
- **`checksumMode`** — recent AWS SDKs default to adding CRC32 request checksums, which some
  compatible stores reject; `when-required` restores compatibility, `default` keeps the SDK
  behavior for AWS.

## Governance

Object storage is **egress**, so it is deny-by-default like outbound HTTP and poll connectors:

- **`tesseraql.object-storage.allowedBuckets`** is an explicit allow-list; an attachment document
  whose `bucket` resolves to a bucket not on the list fails the build (it is never silently
  served).
- **Credentials** resolve through the secret-reference syntax at call time, never inlined, never
  logged, never written to a generated artifact.
- The storage client stays an implementation detail — apps see the `bucket`/`limits` recipe
  surface, never raw SDK options.

### Scanning

Every upload is scanned before its metadata is recorded. The scanner is a hook
(`AttachmentScanner`, discovered from the classpath) — the seam for ClamAV or a cloud
malware-scan service; the default is a no-op that reports `clean` without reading the bytes, so
an app enables real scanning by adding a scanner module — no config flag.

The verdict is recorded as `scan_status`. A `clean` object is served normally; an `infected`
object is **never served** (the download gate refuses any non-clean object with `409`) and is
kept or removed per `tesseraql.attachments.scan.onInfected: quarantine | delete` (default
`quarantine`); a scanner error fails the upload closed (`503`). An asynchronous
`pending → scanned` model is not currently supported; synchronous keeps the gate simple.

### Retention

Attachment retention wires into the same cluster-safe sweep that already purges the outbox and
job history, gated by `tesseraql.retention.attachments`:

- **Age policy** — attachment metadata past the configured window is deleted and each blob
  reclaimed (best-effort, so a concurrent node or an already-removed blob is harmless). Driven
  by the sweep rather than provider lifecycle rules **on purpose**: server-side lifecycle
  support varies across S3-compatible stores, so the portable, observable path is the in-app
  sweep.
- **Orphan GC** (not currently supported) — reclaiming a blob whose `storage_key` matches no
  metadata row needs a store-listing capability the minimal storage SPI does not expose; the
  upload path's best-effort delete-on-failure already covers the common case. A planned
  refinement.

## Lint and coverage

The attachment surface is machine-checkable like every other recipe. The attachment document has
its own lint family (`TQL-ATTACH-34xx`, mirroring the `TQL-WORKFLOW`/`TQL-SCOPE` per-kind
families rather than crowding the `TQL-YAML` loader-error number space); object-storage egress
takes `TQL-SEC-411x` (`TQL-SEC-4100` is reserved for the write-scope bypass check in
[data scoping](data-scoping.md)):

| Code | Severity | Meaning |
| --- | --- | --- |
| `TQL-ATTACH-3401` | error | a document under `attachments/` whose `kind` is not `attachment` |
| `TQL-ATTACH-3402` | error | a `kind: attachment` document is missing `basePath` |
| `TQL-ATTACH-3403` | error | a `kind: attachment` document is missing `record.entity`/`record.key` |
| `TQL-ATTACH-3404` | error | the `basePath` does not contain the record key as a path parameter |
| `TQL-ATTACH-3405` | error | `limits.maxBytes` is missing or unparseable |
| `TQL-SEC-4110` | error | with `provider: s3`, an attachment's resolved bucket is not in `tesseraql.object-storage.allowedBuckets` (deny-by-default) |

Scanning adds no lint — it is config plus a discovered scanner, with no new YAML surface — and is
enforced at runtime instead (the codes below).

The runtime fails closed, all mapped from the `TQL-LD-284x` codes the attachment routes raise: a
`404` when an attachment is unknown or owned by a different record (never leaked across records),
a `413` past the size limit, a `415` for a disallowed content type, a `409` for a download of an
object that did not pass scanning, and a `503` when the scanner cannot reach a verdict
(fail-closed).

An **`attachment`** coverage kind (one item per `kind: attachment` document, gated with
`coverage.thresholds.attachment`) is planned but not currently supported: it needs a declarative
test target that exercises the upload/download round-trip, which pulls the attachment runtime
into the test harness. The `TQL-ATTACH-34xx` lint already makes the attachment surface
machine-checkable in the meantime.

## The storage SPI

Durable objects sit behind a deliberately small SPI, `BlobStore`: put, get, exists, delete, and
an optional pre-signed GET — the **minimal portable intersection** of every S3-compatible store.
Tagging, ACLs, object-lock, and lifecycle rules are *not* on the SPI, because they vary across
providers; keeping them off the interface is what makes portability hold. Deletion is called
only by retention, never implicitly. Two implementations ship: the local file store (the
default, `java.nio` only) and the S3 store in the opt-in `tesseraql-s3` leaf module, so the
object-store SDK's weight never reaches an app that does not use it. Durable attachments are
deliberately a separate store from the ephemeral spool the file-transfer machinery uses — the
two lifecycles never mix, and a deployment can keep spool on local disk while attachments live
in S3, or vice versa.
