# Edge hygiene: a transfer under its own route, a declared header as wire text

> **Status: in progress.** Four pull requests, in this order, each branched from fresh
> `origin/main` after the previous one merged. **E0** — a file-export or file-import route's
> `{transferId}` subtree answers for the transfers that route created and for no other, the
> foreign id indistinguishable from an unknown one, the cancel refused before it reaches the
> run: **shipped with this record** (the pull request that registers this file in both
> internal-doc lists). **E1** — a declared `headers:` `Location`/`HX-Redirect` acquires the
> application's prefix, so the documented 201 recipe answers under `tesseraql dev` and `host`:
> planned. **E2** — a GET or HEAD never engages the form parser, so a form content type on one
> is not an unhandled exception and a raw 500 through the gateway: planned. **E3** — a declared
> header value is judged where it is declared: a `security.responseHeaders` value carrying a
> control character is refused at lint and boot (the three writers that bypass the edge's
> backstop become safe by construction), and an `HX-Trigger` map is JSON-escaped to ASCII so a
> Japanese toast survives the wire: planned. Each pull request flips its own line here when it
> merges.
>
> **The filings under-stated the first item.** [`download-name-and-bytes.md`](download-name-and-bytes.md)
> filed an edge slice of header-writer and lint items; [`export-hygiene.md`](export-hygiene.md)
> decision 12 filed N2 — "any file-export route's status/file subtree serves any transfer id in
> the app" — to it as a reason-leak mitigated by the coded reason. Measured on `57aa7ed0d`: N2 is
> not a leak of a sentence, it is the file. An anonymous caller holding the id of an export
> started under an `ADMIN`-gated route received the export's rows through a public route's
> `/file`, its status through the public route's status endpoint, its card through a public
> import route's, and could set its cancel flag — three guards red on HEAD, the bytes in the
> failure message (`work/edge-slice/e0/head-red-2.log`, this machine only). The route's policy
> was the whole gate, and the subtree it mounted opened onto every transfer in the database.

Registered in `docs-site/nav.mjs` `EXCLUDED` and `ErrorIndex.INTERNAL_DOCS` by E0, with the
registration asserted in `InternalDocsSyncTest`. Site-excluded, so the prose lint and the
vocabulary guard never read it; the error index skips internal documents, so the codes named
here move no reference page.

---

## The measured premise

Measured by reading and running against `57aa7ed0d` (`0.17.0-SNAPSHOT`) on 2026-09-13, one
session, no agents. The items the two records filed to this slice, re-read on today's main, and
what each turned out to be.

| # | item, as filed | verdict on `57aa7ed0d` | where it lives | goes to |
|---|---|---|---|---|
| N2 | any file-export route's status/file subtree serves any transfer id in the app | **LIVE, wider — it is the bytes, and every subtree**: `status(transferId)` reads the transfer by id alone (no app, no route); the status, file and cancel processors resolve the id and nothing else; the cancel is asked of the run BEFORE the status is read. RUN: three guards red, the admin export's CSV in an anonymous answer. | `JdbcFileTransferService.status:412`, `FileTransferStatusProcessor:62-68`, `FileDownloadProcessor:23-29`, `TransferCancelProcessor:57-59`, `RouteCompiler.mountTransferStatus` | **E0** |
| 17 | a declared `headers:` `Location` never acquires the base prefix | LIVE — `ResponseHeaders.apply` encodes a URI-reference header with `PercentEncoding.uriLiteral` and never joins the prefix; the documented recipe (`docs/response-shaping.md:288`, `Location: "/api/items/{steps.record.keys.id}"`) emits `/api/items/1` under `/shop`, an address no member serves. `HX-Redirect` the same. READ. | `ResponseHeaders.java:64-68` | E1 |
| a6 N9 | GET with a form content type through the gateway → raw 500 | LIVE — the body handler is mounted on every method (`RouteEdge.mount`/`remount`); vertx-web 5.1.6 `BodyHandlerImpl.handle` engages on every HTTP/2 request (`hasTransferEncoding` is true for h2) and on any HTTP/1.1 request with framing, and its `BHandler` calls `setExpectMultipart(true)` on a form content type, which Vert.x core refuses on a GET with an `IllegalStateException` — unhandled, a raw 500 with no envelope, logged as `Unhandled exception in router`. The gateway forwards over h2c, so the content type alone reproduces through it. READ against the library's sources. | `RouteEdge.java:164,203`; `BodyHandlerImpl.java:91-98,232-233` | E2 |
| 20 | the documented `HX-Trigger` toast mangles non-ASCII | LIVE — a map or list value is written with a default `ObjectMapper`, which does not escape non-ASCII; the edge folds every char above U+00FF to `?` on the wire. READ. | `ResponseHeaders.java:69-71` | E3 |
| 17/23 | the asset and SSE bypass checks, MCP `HttpTransport:68` | LIVE, low — `AssetRoutes.headers`, `SseRoutes` and the MCP transport write `security.responseHeaders` values with `putHeader` directly, past `RouteEdge.wireHeaders`; the values are the author's configuration, resolved by `ResponseHeaderDefaults.from` with no character check. A control character there hangs the connection the way 4b's finding did. READ. | `AssetRoutes:343`, `SseRoutes:145`, `HttpTransport:68`, `ResponseHeaderDefaults.from` | E3 |
| 7 | a literal-value lint at `ExportRules:189`'s seam (`response.file.contentType` charset, authored OWS in `location:`) | filed, lint-only | `ExportRules` | E3 if it fits one rule, else filed |
| 15 | the `headers:` `Content-Disposition` injection and F125 mangling | LIVE, low — a declared header interpolates request data; CR/LF and every C0 are refused at the edge (4b), so what remains is the quoted-string's own grammar and the `?` fold of a non-ASCII name. The caller injects into a header the caller receives. | `ResponseHeaders.apply` | filed |
| — | an IDN host in an absolute `location:`; `[`/`]` in a path and the JDK follower | file-only | — | filed |
| — | HTTP/1.0: the edge's honest mid-body close is honest to HTTP/1.1 and h2 clients only | record-only in `export-declarations.md`; an export is spooled first, so a mid-body failure is structurally impossible there | `RouteEdge.stream` | filed (a `Content-Length` on a spooled download would make the truncation visible; a design question) |
| — | `dev` loading manifests outside its `TqlException` catch | LIVE, CLI shape | `DevCommand` | slice 9 (F114, "the CLI refuses before it works") |

---

## The decisions

1. **A transfer is the route's, not the application's** (E0). The subtree is secured like its
   parent route, so the parent's policy must be the whole gate: the status, the file and the
   cancel answer for transfers whose recorded `app_name` AND `route_id` are this route's, and
   for no other. Not by subject: the import commit holds its batch to the subject too, because
   a confirm is the uploader's own act; a transfer's readers are whoever the route admits, so a
   colleague under the same policy can fetch a link the exporter shared. The ops console keeps
   its app-wide view under its own policy.
2. **A foreign transfer is an unknown one** (E0). Same status, same code (`TQL-LD-2822`), same
   sentence, the card the tombstone — `commitImport`'s precedent ("deliberately the same answer
   shape as an unknown batch: whose it is, is not for the caller to learn"). No existence oracle.
3. **The scope is checked before the cancel is asked of the run** (E0). The cancel processor
   used to set the flag and then read the status; a foreign cancel would have stopped a
   transfer this route was about to call unknown.
4. **One helper, three processors, no service change** (E0). `TransferScope.own` filters the
   service's own `status()`; the service keeps its id-keyed read for the ops console and the
   sweep. The rule lives where the route is known.
5. **A declared URI-reference header goes through `BasePath.url`** (E1) — the one place the
   prefix goes (`download-name-and-bytes.md` decision 8: encoded once, at `BasePath.url`, after
   the join). `BasePaths.join` leaves an absolute, protocol-relative, fragment or empty value
   untouched, so an authored `https://…` Location is byte-identical; without a prefix the ASCII
   recipe is byte-identical.
6. **A GET or HEAD does not engage the body handler** (E2). A bodiless method carries its
   parameters in the URL; no route in the repository reads `body.*` on a GET (141 `get.yml`,
   zero); `formAttributes()` without the handler is an empty map and `ctx.body().buffer()` is
   null, both of which `RouteEdge.body` already handles. A form content type on a GET is then
   what it is on a direct HTTP/1.1 GET without framing today: ignored, 200 — the gateway and
   the member answer alike.
7. **A declared header value is judged where it is declared** (E3). `security.responseHeaders`
   values: a C0 control or DEL refused at lint (a new `TQL-SEC-` code) and at boot in
   `ResponseHeaderDefaults.from`, so the three writers that bypass the edge's backstop are safe
   by construction rather than each taught the check. `HX-Trigger` and any map/list header
   value: `JsonWriteFeature.ESCAPE_NON_ASCII` on the header mapper, so the wire value is ASCII
   and the toast text arrives intact.

### The four, in build order

| # | pull request | items | modules |
|---|---|---|---|
| E0 | a transfer answers under the route that created it | N2 | compiler |
| E1 | a declared `Location`/`HX-Redirect` under a prefix | 17 | compiler |
| E2 | a GET never engages the form parser | a6 N9 | runtime |
| E3 | a declared header value is judged where it is declared | 20, 17/23, 7 | yaml, compiler |

Independent of each other; ordered by severity.

---

## E0 — a transfer answers under the route that created it

### What was wrong

`RouteCompiler.mountTransferStatus` mounts `GET {path}/{transferId}` and `POST
{path}/{transferId}/cancel` for every file-import and file-export route, and `buildFileExport`
mounts `GET {path}/{transferId}/file` beside them, each "secured like its parent route" —
`applyCommonGovernance` with the parent's `security:`. The three processors then called
`transfers.status(transferId)` (and `download`, and `cancel`), which `JdbcFileTransferService`
answers from `tql_file_transfer` by id alone. The transfer row records its `route_id` and
`app_name`; nothing read them back.

So the gate was the parent route's, and the door it guarded opened onto the whole table. With
one public file-export or file-import route in the application, every transfer in the database
was readable, downloadable and cancellable by anyone holding its id — an id that appears in the
status URL the exporter's page polls, in the card, in the ops console and in any shared link.
The transfer id is a random UUID, so this was not enumerable; it was a capability the route's
policy was supposed to bound and did not.

`TransferCancelProcessor` also asked the run to stop before it read the status, so even a
subtree that refused the foreign id afterwards would already have set the flag.

The import commit had the rule all along: `commitImport` refuses a batch whose `appName`,
`routeId` or `subject` differ from the confirming route's, "deliberately the same answer shape
as an unknown batch". The transfer subtree never got it.

### The change

- `TransferScope.own(transfers, transferId, appName, routeId)` (compiler, package-private):
  the service's `status()` filtered to this application's and this route's own transfer.
- `FileTransferStatusProcessor`, `FileDownloadProcessor` and `TransferCancelProcessor` take
  `appName` and `routeId` and resolve through it; the unused one-argument status constructor
  is deleted. The cancel processor reads the status first and asks the run to stop only for its
  own transfer.
- `RouteCompiler` passes `appName` and the route's `definition.id()` — the same values
  `FileImportProcessor`, `ImportCommitProcessor` and `FileExportStartProcessor` record on the
  transfer.

### The guards, red before the fix

`TransferRouteScopeIntegrationTest` (tesseraql-runtime; PostgreSQL; an `ADMIN`-gated export
route, a public export route, a public import route, and a `pg_sleep`-per-row export that stays
RUNNING for two seconds):

- `anotherRoutesSubtreeAnswersAnAdminTransferAsUnknown` — anonymous GET of the admin export's
  file, status and card through the public export route's subtree, and of its status through
  the public import route's, each 404 `TQL-LD-2822` / the tombstone card; the admin's own
  subtree still serves the bytes; the admin subtree without a token is 401 (what protected it).
- `aForeignTransferAnswersExactlyAsAnUnknownOne` — the foreign answer equals the unknown-id
  answer with the id factored out.
- `aCancelThroughAForeignRouteLeavesTheRunUntouched` — a foreign POST cancel on a RUNNING
  transfer is 404 and `tql_job_execution.cancel_requested` stays null; the run completes; the
  own-route cancel answers 200.

`TransferScopeTest` (tesseraql-compiler; a proxy-backed service): the route half, the
application half — the same route id in another application — and the null service.

Bracket (`work/edge-slice/e0/`, this machine only): HEAD `57aa7ed0d` — 3/3 integration guards
red, the first with `order_no o-1 o-2 o-3 o-4` in an anonymous answer (`head-red-2.log`);
`V-cancel-after` (scope checked, `cancel()` called first) — exactly the cancel-flag assertion
red (`v-cancel-after.log`); `V-routeonly` (route compared, application not) — exactly
`theSameRouteIdInAnotherApplicationDoesNot` red (`v-routeonly.log`); the fix — 3/3 and 4/4
green (`fix-green.log`, `unit-green.log`).

### What this breaks

- A page or client that polled one route's transfer through another route's subtree — nothing
  in the repository does; every emitted status URL is the creating route's own.

### Filed, not fixed (from E0's measurement)

- Subject scoping of a transfer (the import commit's third term): a product decision, not
  taken here — see decision 1.
- The ops console's transfer list and cancel are application-wide under `ops` policies by
  design; untouched.
- A stack whose members share one database with the same route id in two applications was
  exposed the same way across applications; the application half of the rule closes it, proven
  by the unit test only — no harness boots two members against one table.

---

## E1 — a declared `Location`/`HX-Redirect` under a prefix

Planned. `ResponseHeaders.apply` `:64-68`: `PercentEncoding.uriLiteral(Interpolation.interpolateUrl(template, evaluation))`
becomes `BasePath.url(exchange, Interpolation.interpolateUrl(template, evaluation))` — the same
encoder, after the join. Guards in `BasePathEmissionIntegrationTest` (the only prefix-aware
harness): a route declaring the documented recipe and an `HX-Redirect`, each emitted with the
prefix exactly once and resolving to a mounted route; an absolute `https://` value untouched.
Variants: HEAD; V-double (prefixed twice); V-absolute (an absolute value prefixed).

## E2 — a GET never engages the form parser

Planned. `RouteEdge.mount`/`remount`: the body handler is added to the route only for POST,
PUT, PATCH and DELETE. Guards in a runtime integration test: a GET with
`Content-Type: application/x-www-form-urlencoded` and `Content-Length: 0` answers 200 with the
route's body (HEAD: raw 500, `Unhandled exception in router`); a multipart content type the
same; a POST form still binds its fields (the control). Through the gateway when a harness
reaches one cheaply, else the direct HTTP/1.1 shape with framing, which the library's own
source shows is the same branch.

## E3 — a declared header value is judged where it is declared

Planned. `ResponseHeaderDefaults.from`: a value carrying a C0 control (other than HTAB) or DEL
is refused with a new `TQL-SEC-` code naming the header; the linter's security rules gain the
twin. `ResponseHeaders`: the header mapper is a dedicated instance with
`JsonWriteFeature.ESCAPE_NON_ASCII`. Guards: a lint case and a boot case per refusal; an
`HX-Trigger` map with a Japanese toast arrives as `\uXXXX` escapes and parses back to the text;
the asset, SSE and MCP writers unchanged. Variants: HEAD; V-lint-only; V-boot-only;
V-scalar-only (the escape applied to scalars, not maps).

---

## Scope out, each with its destination

- `StackRelay:509`'s raw `Location` write and every non-ASCII-prefix item → **the router
  slice** (`download-name-and-bytes.md` "Filed, not fixed").
- The `headers:` `Content-Disposition` quoted-string grammar and the `?` fold of a non-ASCII
  declared name → filed; the caller injects into a header the caller receives, and `filename:`
  is the documented spelling for a download name.
- IDN hosts; `[`/`]` in a path → file-only.
- `dev` manifests outside the `TqlException` catch → **slice 9** (F114).
- A `Content-Length` on a spooled download, so an HTTP/1.0 hop sees a truncation → **a design
  question for the export line**.
- `body.*` declared on a GET route, which E2 makes unbindable by construction → the filed lint
  (`export-declarations.md`, "lint hygiene / sweeps").

---

## The audit records, amended by this slice

- [`audit-medium-leads.md`](audit-medium-leads.md): N2 enters "Defects surfaced that the audit
  does not carry" as 8b with its measured width (the bytes, every subtree, the cancel); items
  17 and 20 gain this record as destination.
- [`export-hygiene.md`](export-hygiene.md) decision 12 and P8's "Filed, not fixed": N2's
  destination is this record's E0, and the coded reason was the mitigation of a reason leak
  that turned out to be a file leak.
- [`download-name-and-bytes.md`](download-name-and-bytes.md) "Filed, not fixed", the edge
  bullet: this record.
