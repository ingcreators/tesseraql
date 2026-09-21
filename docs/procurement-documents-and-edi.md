# The procurement demo, finished: three printable documents and the EDI companion

> **Status: designed 2026-09-21; nothing shipped.** `docs/procurement-demo.md` closed slices
> 1–7 and left two things open: the three PDFs (見積書, 注文書, 納品書), "deferred to a
> dedicated documents step resolving the `tesseraql-pdf` module story once for all three
> documents", and slice 8, the EDI companion, "named so it is a decision, not scope drift". Both
> blockers are gone. The module story was settled by `docs/codec-discovery.md` (#1344–#1349): an
> application declares the codec module under `tesseraql.modules`, `dev` resolves it, a package
> carries it, and a format no codec serves refuses boot on every arm. The outbound `push:` step
> (`docs/jobs.md`) and the hardened `poll:` trigger (`docs/connectors.md`,
> `docs/poll-connector-hardening.md`) are the two halves of an SFTP exchange, each deny by
> default. What remains is application work, one asset gap this record measured — the sample
> font lacks the glyphs the documents need — and the decisions below. Four slices, the user
> naming each: **S1** the font and the quotation; **S2** the order and the delivery note; **S3**
> the receipt-notice feed; **S4** the companion application and the crossing.

## Why finish it

The procurement app is the demo centerpiece and the last pre-1.0 composition probe. Its tour
(`examples/procurement-app/README.md`) walks one requisition to goods receipt across three
logins, and three of its steps promise a document the app cannot print. The design named PDFs
on the screens the tour actually visits — a quotation the supplier prints, an order the buyer
mails, a delivery note that travels with the goods — because a business application that
cannot produce paper is not one an evaluator recognises. The EDI companion is the other
promise the gallery has never kept: `poll:` and `push:` are tested by runtime integration
tests and dogfooded nowhere a reader can run (`docs/connectors.md` says so about the poll
trigger; the push step's only example is the local mirror in `inventory-app`). One file
crossing between two applications on one machine, refused until the host key is pinned, is
the demo-visible shape of that hardening.

## What was measured

At `89d244609` (main, 2026-09-21), read and measured, not assumed:

1. **The sample font does not cover the demo.** `TesseraQLSampleGothic-Regular.ttf` (the same
   80,588-byte file under `examples/user-admin-app/fonts/` and
   `tesseraql-runtime/src/test/resources/fonts/`) holds 422 glyphs: "ASCII, kana, and the
   kanji the TesseraQL examples and tests render", per its README. Checked with
   `java.awt.Font.canDisplay` over every non-ASCII code point the procurement app renders
   (`V7__japanese_names.sql`, `messages/ja.yml`): 47 of 434 are missing, among them 技, 営, 調,
   見 and 積. Over a document vocabulary (見積書 注文書 納品書 検収通知, 品名 数量 単価 金額 合計
   納期 御中 and their kin): 34 of 128 missing, among them 見, 積, 注, 納 and 収. The codec
   renders a missing glyph as the font's `.notdef` box and says nothing; the demo would print
   tofu in its own titles.
2. **A principled subset fits.** Noto Sans JP (`NotoSansJP[wght].ttf`, 9,589,900 bytes),
   instanced at weight 400 and subset with fontTools to JIS X 0208 rows 1–8 (symbols, kana,
   Greek, Cyrillic, box drawing) plus the level-1 kanji — the ranges derived through the
   Shift_JIS encoding, so the set is the standard's, not a hand list — gives 3,651 code points,
   4,749 glyphs, 1,351,040 bytes, and covers every code point in fact 1 (0 missing on both
   sets). Adding level 2 gives 7,041 code points, 8,326 glyphs, 2,629,448 bytes. The instance
   keeps the source's name table ("Noto Sans JP Thin"), so the recipe must rename the family;
   `PdfFontsTest` pins `TesseraQL Sample Gothic`, and no test pins a rendered PDF's bytes.
3. **How the codec reaches the application.** `user-admin-app` declares
   `tesseraql.modules: [io.tesseraql:tesseraql-pdf]`; `AppModules.load` reads `work/modules`
   (or a package's bundled set) and, when that directory holds no jar, discovers codecs from
   the runtime's own class loader — so a runtime test with `tesseraql-pdf` on its classpath
   serves PDF with no declaration at all. `TesseraqlRuntime.start` runs no `ModulesGuard`;
   `MultiAppHost` does (`TQL-APP-4216`), which is why every host-shaped copy of user-admin
   strips the declaration (`UserAdminAppCopy.prepare`). The gallery bar
   (`GalleryAppsIntegrationTest`) lints with the maven-plugin's classpath, csv only: a declared
   `format: pdf` is `TQL-YAML-1408`, a warning, and admission counts errors only
   (`TQL-ADM-4706`). A declaring procurement app passes the bar unchanged.
4. **The export contract a document needs is all there.** `sources:` beside `main` reach a
   print template as `header.first.*` inside the extraction's own transaction
   (`docs/file-transfers.md`, "What a template can see"); `filename: order-{path.id}.pdf`
   (`TQL-YAML-1076` for a placeholder the request cannot resolve); `locale:` is a literal or a
   request source, English when none; `maxRows` bounds a document (`TQL-LD-2850`); the
   template must live inside the app home (`TQL-YAML-1075`, `TQL-LD-2832`); fonts are the app
   home's `fonts/` directory and nothing else (`PdfFonts.scan` — the codec bundles no font);
   output is deterministic. A query-export has no `statusWhen`: a header source that returns no
   row renders a document with an empty header rather than answering 404.
5. **The shipment feed today.** `api.shipments.export` is a synchronous `query-export` CSV
   (`bom: true`, for spreadsheets) over `export.sql`: `delivery_note_no, order_id, partner_id,
   partner_name, ship_date, carrier, received_at`. No shipment is seeded (the suite's "the
   shipment export starts empty"); `receive` stamps `received_at` in the same transaction as
   the state advance; `orders` carries `unique (quote_id)`, so seeding an order over RFQ-2002's
   quotes — the pair the tour's step 4 orders from — would break the tour.
6. **The push step.** A `batch-pipeline` step, never a route: `file: steps.<id>.transferId`,
   `as:` renames within the context's roots, `tesseraql.connectors.push` is its own policy
   block (allowedHosts, allowedPaths, knownHostsFile, credentials with exactly one method),
   the upload stages under a dot-name and renames, an off-list host is `TQL-SEC-4141` before
   any connection, a failed delivery `TQL-BATCH-5315` on the step, a bare `*` fails admission
   (`TQL-ADM-4703`). No suite target covers a push (`docs/testing.md` names `http:` and
   `notify:` step targets, no `push:`); coverage stops at the export step's SQL.
7. **The poll trigger.** A `file-import` job with `trigger.poll` (`transport`, `host`, `port`,
   `path` relative to the login home, `credential`, `include`, `delay` default 60 s, `move`,
   `moveFailed`, `consumeOnce`) and an `import:` block whose column names are the per-row
   SQL's parameters. `PollLoop.run` logs a failed cycle as a WARNING and retries after
   `delay`, with no backoff; a wiring-time refusal (host off the list, no `import:`) is a skip
   the poll-source status registry shows on the ops jobs page. Without
   `knownHostsFile` lint warns `TQL-SEC-4084`; without `consumeOnce` it warns `TQL-YAML-1310`;
   `consumeOnce` claims through `tql_poll_consumed`. `SftpClient` hands a declared
   `knownHostsFile` to JSch with strict checking: a missing file or an unlisted key fails the
   connect — a cycle WARNING on poll, 5315 on push — and never refuses boot.
8. **The harnesses exist.** `PushSftpIntegrationTest` and `PollImportSftpIntegrationTest`
   run an in-process Apache MINA sshd (`VirtualFileSystemFactory`, port 0, password
   authentication) and format the port into the app's YAML. Neither pins a known-hosts file;
   only the unit tests (`SftpClientTest`, `RemotePollSourceTest`) exercise the pin. The
   sshd and `tesseraql-pdf` are test dependencies of `tesseraql-runtime` and of nothing
   else; PDFBox rides in with the codec's engine for text extraction.
9. **The stack.** `examples/` is a stack (`tesseraql-stack.yml`); every member names its own
   database and `--embedded-db` gives each one; `dev --app-name` narrows the boot to one
   member; one command runs two. The only runtime-level procurement test
   (`ProcurementRequisitionTaskIntegrationTest`) copies the app and boots one
   `TesseraqlRuntime`; `MultiAppHostIntegrationTest` is the two-member shape (an install
   root, each member's `application.yml` rewritten to its own schema, a stack settings file
   for the framework datasource).
10. **`juchu-kanri-app`** has a 受注 table (受注番号, 顧客名, 状態, 地域, 金額) and exists to
    demonstrate Japanese identifiers. It was considered as the companion and rejected
    (decision 2).
11. **The suite grammar.** `given:` steps are transitions only; a suite cannot insert a row
    before a case. A feed case over the seeds can assert the empty shape and the column list;
    a file on a server is the integration test's to prove.

## The mechanism

A document is a `query-export` route with `format: pdf`: the route's `sources:` run on one
connection, the template renders through the standard engine in the export's locale, the
codec embeds every font under `fonts/` and converts page-oriented CSS to PDF, deterministically.
The application declares the codec module once; `dev` resolves it into `work/modules`, a
package carries it, and a runtime whose codec set lacks it refuses to start naming the route.

A file crosses like this: a batch-pipeline job's export step writes a transfer; its push step
delivers that transfer to an allow-listed SFTP host under a pinned host key, staged and
renamed; the other application's poll trigger lists the same directory under its own
allow-list and pin, waits for the file to be stable, claims it once across replicas, feeds it
row by row through the import SQL as a transfer the operations console shows, and moves it
to `.done`. Two policy blocks, two databases, one file, and both consoles under one gateway.

## The decisions

### 1 — The exchange is the buyer's receipt notice, buyer to supplier

The demo's chain ends at goods receipt, and the design's non-goals fence out invoicing. The one
document a buyer owes a supplier at that point is the receipt notice (検収通知): which delivery
notes were received, when — the hand-off to the supplier's order-to-cash, which is outside the
fence. The feed is the shipment export's seven columns, filtered to the business date's
receipts. This honours the design's sentence ("re-uses exactly this export, moved over SFTP,
imported by `file-import` on the other side") and gives the companion one table with a
meaning.

The reverse direction — the supplier's shipping system sending an advance shipping notice into
the buyer's `shipments` table — was rejected: the `ship` transition is the engine's and a job
cannot take it, so an import would register a shipment the workflow never saw, and the row
authority `TQL-WORKFLOW-3204` enforces would be bypassed by the framework's own poll. The
purchase-order feed (注文データ, buyer to supplier at `issue`) is the other honest candidate;
it is the same shape with a different SQL and is not in this campaign.

### 2 — The other side is a new gallery member, `examples/supplier-edi-app`

Two parties are two applications with two databases; that separation is the demo point, and
the companion must run under the same one-command stack the tour uses. The companion is
minimal and admission-held like every gallery member: one migration (`receipt_notices`, keyed
by `delivery_note_no`), one poll-triggered `file-import` job with a per-row upsert, one
declarative list at `/notices` and its JSON twin at `/api/notices` behind a `notices.read`
policy, one suite, a README. It registers in `examples/README.md`, in
`GalleryAppsIntegrationTest`'s `@ValueSource`, and is named by `docs/guide-integration.md`
as the worked example of push and poll.

Reusing `juchu-kanri-app` was rejected: it exists for the identifier contract, its 受注 row
is an order, not a receipt, and `地域` has no source in the feed. A fixture directory under
`procurement-app` was rejected: a member is what the stack runs, and a fixture is not a
member.

### 3 — The feed is a job, and the route stays

`batch/edi/receipt-notice/job.yml` (`edi.receiptNotice`, `recipe: batch-pipeline`): an export
step over `receipt-notice.sql` — `export.sql`'s column list with
`cast(s.received_at as date) = cast(/* batch.businessDate */ … as date)` — writing
`receipt-notice-{batch.businessDate}.csv` without a byte-order mark (the route's `bom: true`
is for a spreadsheet; this file is for a program), then a push step:
`transport: sftp`, `host: localhost`, `port: 2222`, `path: drop`, `credential: partner-drop`,
`file: steps.notice.transferId`. A nightly schedule, and the tour runs it on demand through the
operations API (`POST /_tesseraql/ops/batch/jobs/edi.receiptNotice/run`) with the day's
business date. A rerun re-delivers under the same name, an overwrite. `api.shipments.export`
stays as the human download.

Whether `file-import` tolerates a byte-order mark on the first column name is unknown; the
companion's import never sees one under this decision, and S4 measures the other case once,
because an export the framework writes with `bom: true` must be readable by the import the
framework reads (see "Filed, not fixed").

### 4 — One SFTP server for the demo, and the host key is pinned or the exchange is refused

The tour starts one container that both applications talk to:

```bash
docker run --rm -p 2222:22 atmoz/sftp edi:edi-secret:::drop
ssh-keyscan -p 2222 localhost > examples/procurement-app/security/known_hosts
ssh-keyscan -p 2222 localhost > examples/supplier-edi-app/security/known_hosts
```

Both applications declare `knownHostsFile: security/known_hosts` and commit that file with a
comment line only (a real host key is the operator's; the repository commits no key of any
kind). Until the second and third commands run, the push fails its job with
`TQL-BATCH-5315` and the poll warns every cycle — the exchange is refused loudly, never served
unverified, and the poll-source status on the companion's ops jobs page says why. `allowedHosts:
[localhost]` is committed on both sides; the credential's password comes from
`${secret.env.EDI_SFTP_PASSWORD:edi-secret}`, a dev default like every other in the gallery.
The companion polls every 30 seconds with `consumeOnce: true` and `include: receipt-notice-*.csv`.

The cost: `dev --stack examples` now boots a poll consumer that logs a WARNING every 30
seconds while no server listens. That is the status registry doing its job, and the README
says so; the alternative — arming the host through an environment variable so the committed
state polls nothing — would make the gallery's only poll consumer one that never polls.

### 5 — Three documents, two of them printed from both sides of the portal

| Document | Route | File | Policy and reach | `header` | `main` |
|---|---|---|---|---|---|
| 見積書 (quotation) | `web/api/supplier/quotes/{id}/print/get.yml` | `quote-{path.id}.pdf` | `sup.read`; `quotes_scope` on both sources — a competitor's quote is outside the caller's reach | the quote, its partner, the RFQ's title and due date | `quote_lines` joined to `items` |
| 注文書 (purchase order) | `web/api/orders/{id}/print/get.yml` | `order-{path.id}.pdf` | new `doc.read` (procurement, head, supplier, admin); `quotes_scope` confines a supplier to their own orders | the order, its partner, the RFQ, the requisition's department | `order_lines` joined to `items` |
| 納品書 (delivery note) | `web/api/orders/{id}/delivery-note/get.yml` | `delivery-note-{path.id}.pdf` | `doc.read`; the same scope | the shipment, its order, the partner | `order_lines` joined to `items` |

The buyer and the supplier print the same 注文書 from the same route — one document, one
policy, the scope deciding whose rows — which is the composition the design asked the portal
to demonstrate. Labels render through `#{doc.*}` messages in `messages/ja.yml` and `en.yml`;
`locale:` reads the request's `lang` query with Japanese as the configured default
(`tesseraql.i18n.defaultLocale: ja`, declared by S1 the way user-admin declares its own), so
`?lang=en` prints the English document from the same template. Templates are colocated with
their routes, self-contained, A4, with the page counter in the footer; `fontFamilies` is
not used — the template names `TesseraQL Sample Gothic`.

A document whose header source returns no row (a delivery note before the shipment is
registered, a quote outside the caller's reach) is fact 4's empty render. S1 measures what the
route answers and files it if it prints (see "Filed, not fixed"); the application does not
paper over it with a rule.

### 6 — One sample font, at JIS level 1, in every place the sample font lives

The level-1 subset of fact 2 replaces `TesseraQLSampleGothic-Regular.ttf` in
`examples/user-admin-app/fonts/`, `tesseraql-runtime/src/test/resources/fonts/`,
`tesseraql-pdf/src/test/resources/fonts/` (a fourth copy fact 1 missed) and the new
`examples/procurement-app/fonts/`: the same family name, the same OFL text, one README stating
the character set. Its recipe is committed as `scripts/sample-font.py` (fontTools: instance
`NotoSansJP[wght].ttf` at 400, subset to the Shift_JIS-derived ranges, rename the family,
keep every layout feature) so the file is reproducible from the source font, which is not
committed. The cost is 1.27 MB per copy, four copies. The alternative — the wide font for
procurement only, the seed subset elsewhere — keeps 2.5 MB out of the repository at the price of
two fonts under one family name with different coverage, and is recorded as the user's call.
The reason for level 1 rather than the seeds' own glyphs: the tour types free text (a
requisition title, a selection reason, a carrier) and any of it may reach a document.

### 7 — Proofs: the suite asserts the SQL, three integration tests prove the file and the glyphs

- **Suite cases** (`tests/procurement-test.yml`): the three documents' `header` and `main`
  SQL as `sql:` cases with the supplier and procurement postures (the `document` coverage kind
  counts each route covered), the feed's SQL over the seeds with a business date (row count 0,
  the column list — fact 11), and, in the companion, the per-row upsert (the `file-poll` kind)
  and the list.
- **`ProcurementDocumentsIntegrationTest`** (`tesseraql-runtime`): `GET` each document as its
  persona → `%PDF`, and the extracted text contains the document's title and a seeded Japanese
  name — the tofu proof, red against the 422-glyph font; a competitor's quote as the other
  supplier; the English document under `?lang=en`.
- **`ProcurementReceiptNoticeIntegrationTest`**: the in-process sshd with its generated host
  key written into the copy's `security/known_hosts` (the first pinned SFTP test), a received
  shipment inserted over the seeded RFQ-2002 through JDBC, the job run through the operations
  API, the file on the server whole with the header line and the row; the negative twin: an
  unpinned key fails the job with 5315 and delivers nothing.
- **`SupplierEdiIntegrationTest`**: `MultiAppHost` over both members (each copied, its
  `application.yml` pointed at its own schema, a `ProcurementAppCopy.prepare` stripping the
  module declaration the host would refuse — fact 3), the same sshd: the feed lands, the
  companion imports it, `/supplier-edi/api/notices` lists the row, the same file dropped again
  is skipped, the poll source reports polling.

### 8 — No new seed

The tour's own goods receipt makes the feed non-empty; the suite asserts the empty shape; the
integration tests insert what they need. Seeding a received chain would either sit beside the
workflow (an order with no instance) or collide with the tour's step 4 (fact 5).

### 9 — The tour grows three documents and one crossing; the console shows it

Steps 3, 4 and 6 gain their PDFs (`curl -o quote.pdf …`), a new step 7 runs the feed and
watches the companion's `/notices` fill, and the finale adds the transfer on each side and the
poll source's status. No framework surface changes for this; the companion's console rides
`tql.ops.view.supplier-edi` like every member's.

## What this breaks

- The sample font's bytes change everywhere it lives; every PDF a test renders changes bytes.
  No test pins them (fact 2); the family name is kept.
- `procurement-app` declares a module: `dev --app-name procurement` resolves it on first run
  as user-admin does, and every host-shaped test that copies the app must strip the
  declaration (today none copies it into a host).
- The examples stack gains a member, and `dev --stack examples` logs the companion's poll
  warnings while no SFTP server listens (decision 4).
- The procurement suite grows; `GalleryAppsIntegrationTest`'s `@ValueSource` gains one;
  `examples/README.md`, `docs/guide-integration.md` and the procurement README change; the
  status block of `docs/procurement-demo.md` closes.
- `tesseraql.i18n` on procurement-app: the suite's two message cases pass an explicit locale
  and are unaffected; the default locale of every page becomes Japanese, which the seeds
  already are.

## Filed, not fixed

Framework observations this record makes; each becomes a finding with its own fix only if a
slice confirms it.

- A print template rendering a code point its embedded fonts lack draws `.notdef` and reports
  nothing. Data cannot be linted, but the engine can count missing glyphs at render time; a
  WARNING naming the route and the count would have caught fact 1. **S1 measured it: the
  engine logs nothing** — the old font rendered the quotation with boxed titles and the log
  held only `Loading font(TesseraQL Sample Gothic)`. Filed; the read-back test is the guard.
- A `query-export` whose `header` source returns no row: **S1 measured a 500**, not a blank
  page — `TQL-LD-2831` from the template's own dereference of `header.first`. Fixed in S1,
  not filed: `export.statusWhen` on a query-export (the renderers' block, judged over the
  sources before the extraction opens and again with `main.rowCount` after it; `TQL-LD-2863`
  carries the declared status; a file-export and a job step refuse the key, `TQL-YAML-1041`).
- A print template's `#{key}` rendered as `??key_locale??`: **S1 found the PDF codec's engine
  had no message resolver** while `printable-documents.md` promised the export's locale.
  Fixed in S1, not filed: the write spec carries the application's catalogs
  (`DocumentMessages`), resolved once per document in the locale it renders in.
- No suite target plans a `push:` step the way `http:` plans an outbound call; coverage stops at
  the export SQL. S3 records the gap.
- Whether `file-import` strips a byte-order mark the framework's own `bom: true` export writes.
  S4 measures it once.
- `PollLoop` retries a failing source every `delay` with no backoff — the remaining designed
  slices of `docs/poll-connector-hardening.md`, not this campaign.
- Fonts are per application home; a stack cannot share one. Four copies is the cost paid here.

## The slices

### S1 — the font and the quotation

`scripts/sample-font.py` and the three font replacements with their READMEs;
`tesseraql.modules` and `tesseraql.i18n` on procurement-app; `doc.*` messages; the 見積書 route,
template and two SQL files; suite cases; `ProcurementDocumentsIntegrationTest` with the tofu
proof and the competitor refusal; the README's step 3; the two measurements of "Filed, not
fixed" that belong to a document; CHANGELOG.

### S2 — the order and the delivery note

The `doc.read` policy; the 注文書 and 納品書 routes, templates and SQL; suite cases; the
integration test's remaining cases (both personas on one 注文書, the English document); the
README's steps 4 and 6; `examples/README.md`'s procurement row; a pointer from
`docs/printable-documents.md` to the header-and-lines example; CHANGELOG.

### S3 — the receipt-notice feed

The job, `receipt-notice.sql`, the push policy block, `security/known_hosts` with its comment
line, the README's server and keyscan steps and the job run; the suite's feed case;
`ProcurementReceiptNoticeIntegrationTest` with the pinned sshd and the negative twin;
`docs/guide-integration.md` naming the pair; CHANGELOG.

### S4 — the companion and the crossing

`examples/supplier-edi-app` in full (decision 2), its registration in the gallery bar and the
README table, `ProcurementAppCopy` and `SupplierEdiIntegrationTest`; the tour's step 7 and
finale; the byte-order-mark measurement; `docs/procurement-demo.md`'s status block closed;
CHANGELOG.

## Docs and CHANGELOG

Per slice, in the slice: `examples/procurement-app/README.md` (the tour), `examples/README.md`
(both rows), `docs/guide-integration.md` (the worked pair), `docs/printable-documents.md` (one
pointer), `docs/procurement-demo.md` (the status block), and a CHANGELOG entry under
"Added" naming the record. This record itself is internal: registered in `docs-site/nav.mjs`'s
`EXCLUDED` and `ErrorIndex.INTERNAL_DOCS`, pinned by `InternalDocsSyncTest`.

## Error codes

None new. The codes this record names are the existing ones the slices lean on; a framework
finding confirmed by a slice mints its code in its own fix.
