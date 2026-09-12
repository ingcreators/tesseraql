# A download keeps its name and its bytes

> **Status: in progress.** Four pull requests, in this order, each branched from fresh
> `origin/main` after the previous one merges. **S** — Studio's data-browser download is a CSV:
> **shipped with this record** (the pull request that registers this file in both internal-doc
> lists). **4a** — a download keeps its name (RFC 6266 `filename*` with an ASCII fallback, the
> control/format fold, the split bundle's name): **shipped (#1302)**. **4b** — a redirect
> lands where it says (the URI-literal encoder at the base-path seam, the four bypass writers, the
> app-local gate, the edge backstop, the doubled login query, the paged list's `Link` header):
> designed, not yet shipped. **4c** — an `export:` CSV can carry a byte-order mark (`bom:`):
> designed, not yet shipped. Each pull request flips its own line here when it merges. Closes
> F125 and F128 of [`audit-medium-leads.md`](audit-medium-leads.md).
>
> **The plan this document replaces was wrong in three load-bearing places.** It called the
> filename fix "RFC 6266 `filename*` at the single helper": there is no single helper — a sixth
> emitter (`response.*.headers:`) and three default-name derivations sit beside the five call
> sites, and the *Location* half, which the plan did not carry at all, is the more severe half
> (a Japanese redirect target lands on the application root with a 200 in every client, and a
> Latin-1 one fails in twelve of fourteen followers, both browsers included). It assumed Latin-1
> names "very probably work today" and asked for a control that pins them: a factory Japanese-UI
> Chromium loses `café.csv` to the URL segment and saves `Übersicht.csv` as `ﾜbersicht.csv`, so
> the gate is U+007F on both halves and no such control may exist. And it took two shipped
> things at their word that were not what they said: Studio's "Download CSV" has been
> `Map.toString()` since #220 with its guard green on the wrapper, and `RouteEdge.wireHeaders`
> refuses CR and LF only, so every other C0 control hangs a buffered response and strips the
> headers off a streamed one. Read the decisions below, not the plan.
>
> **What is left is filed, not forgotten**, in [Filed, not fixed](#filed-not-fixed): the router
> slice (a Unicode application name at the gateway), the edge slice (the asset/SSE bypasses and
> the literal-value lints), the export-hygiene slice (the ZIP host byte, the surrogate split, the
> duplicate-format override, the zero-row header), and the file-only items.

## The measured premise

Measured on `0f25a51cf` (`0.17.0-SNAPSHOT`) against the real runtime, byte for byte, by eleven
measurements, eleven adversarial attacks, a three-vantage crux panel and an adjudicator, then
by twelve design-phase syntheses and their attacks. Only what survived that is stated here.

**The filename half.** `ContentDisposition.attachment` (`tesseraql-core/.../http/ContentDisposition.java:18-26`)
builds `attachment; filename="<name>"` and nothing else, and the edge writes a header one byte per
UTF-16 unit: any character above U+00FF leaves as `?` and any character in U+0080..U+00FF leaves
as its raw Latin-1 byte. `受注一覧.csv` downloads as `????.csv`; `café.csv` downloads as
`caf<E9>.csv`, which each client decodes its own way — an English-UI Chromium keeps it, a
Japanese-UI one loses or mis-names it, `curl -OJ` writes the undecodable byte. Five call sites
share the helper (`SqlStep:369` plain and split, `AttachmentDownloadProcessor:58`,
`FileDownloadProcessor:38`, `FileResponseRenderer:50`, `OperationsRoutes:719`); a sixth emitter,
`response.*.headers:` (`ResponseHeaders.java:68`), interpolates any header with no sanitiser at
all. Three derivations produce the name (`export.filename`, the route id at `RouteCompiler:1650`,
and the name a user uploaded to a `kind: attachment` document — which Netty's multipart decoder
passes through with C1 controls, bidirectional overrides and combining marks intact). The
sanitiser folds only `" \ CR LF`; a TAB reaches the wire as 0x09, and any other C0 control drops
the header on the streamed shape or hangs the buffered one. A split export's bundle is named
`orders-.zip` for `orders-{key}.csv`, because the separator strip runs before the extension is
cut.

**The Location half.** Every `Location` and `HX-Redirect` the framework writes is built raw. One
funnel, `BasePath.url` (`tesseraql-pipeline/.../BasePath.java:49-54`), serves nine writers
(`RedirectRenderer.negotiate` for every compiled `redirect:` and the login hop, the three
post/redirect/get writers, the OIDC, SAML, deploy and operations redirects) and twelve
non-Location outputs (shell hrefs, JSON `statusUrl`/`commitUrl`, the OIDC cookie `Path`). Four
writers bypass it with a prefixed wire path read back off the request
(`AttachmentUploadProcessor:105-107`, `AuthStep.activatedLocation:369-374`, `ScimRoutes:116/170`).
`BasePath.encodeSegment` encodes a segment, not a path, so the literal half of a target needs an
encoder the repository does not have. The app-local gate `BasePaths.isLocal` (`:55-62`) refuses
`//`, `/\`, CR and LF — and not a tab, which a browser deletes before parsing, so `/<TAB>/host/x`
passes the gate and navigates off-site: a live open redirect through `_return`, the login
`redirect`, the OIDC `next` and the SAML RelayState. `ErrorResponseRenderer:252-257` appends the
query string to a URI that already carries it, so every login bounce and every post-sign-in
return carries `?q=1?q=1`. `RouteEdge.wireHeaders` (`:600`) refuses a line break and nothing else.

**The bytes.** `CsvFileCodec.write` (`tesseraql-operations/.../CsvFileCodec.java:101-103`) writes
mark-less UTF-8 with no way to ask for a mark; a default-on mark is measured harmful to eleven
reader families (PostgreSQL `COPY … HEADER MATCH`, Python's `csv`, Commons CSV among them), and a
spreadsheet that sniffs the mark is the one reader that needs it. The procurement demo's shipments
export hands a user Japanese partner names in exactly that shape. Studio's "Download CSV" returns
`Map.of("csv", …)` from `StudioProviders.java:1388-1402` on all three branches while the route
binds the whole result as the template's one variable, so the file begins `{csv=` and ends with a
final row holding a lone `}`; the shipped guard's three `contains()` are green on it.

**The guard hazards.** Twenty-four ways a guard here is green on its own defect were measured
before design (a `contains(name)` through the JDK client is green on the Latin-1 defect because the
ISO-8859-1 decode is a bijection; a CJK-only fixture is green on a `> U+00FF` gate; a mark written
after the row loop is invisible under the writer's 8 KiB buffer; "status != 500" is green on the
streamed header-less 200; the Studio `contains()` trio), and nine more were found while designing.
Every guard below is red on a **built** broken variant, in a stamped bracket, before it ships.

## Decisions

### 1 — Studio's provider returns a scalar, on all three branches

`StudioProviders.java:1388-1402` (`studio.data.export`) returns the bare String where it returned
`Map.of("csv", …)`: the disabled note, the CSV, and the `catch (RuntimeException)` note — three
branches, not the two the record counted. Nothing else moves: the route already has the
`docs.openapi` shape (`model: csv: main`, `[(${csv})]`), the shell's delegation already carries a
scalar in its `__value__` envelope, and four places in the code and docs describe the export as a
scalar. **Rejected:** binding `csv: main.csv` at the route — a second correct fix that leaves a
`Map.of("csv", …)` for the next reader to copy, touches a second module, and (measured) applied on
top of the provider fix yields an empty 200 body. The disabled and error notes stay one-line `# …`
records (the `docs.routesPdf` precedent) and are now pinned. Evidence: `design-S.md` §0-§2,
`attack-S-single-guardsens.md` §3 (VT3).

### 2 — One core encoder, two allow-lists, one loop, and its rules are 4a's

`io.tesseraql.core.http.PercentEncoding` lands in 4a with both lists — `extValue` (RFC 8187
`attr-char`, 74 characters) and `uriLiteral` (RFC 3986's unreserved, gen-delims and sub-delims plus
a `%` that starts a `pct-encoded` triplet of two ASCII HEXDIG) — and 4b adds one predicate,
`isUriReferenceHeader` (`Location`/`HX-Redirect`, case-insensitive), so the compiler and the edge
cannot disagree on a name. The rules, settled once for both callers: the walk is over **code
points**; an astral character is four octets; an **unpaired surrogate is U+FFFD** (`%EF%BF%BD`,
never `%3F`, never a throw); the UTF-8 octets are **computed in the loop** (RFC 3629 section 3),
so no charset is consulted anywhere and the `defaultCharset` variant class cannot be written; hex
is **upper-case**; **`null` is a caller error** (`NullPointerException`) — every caller derives a
name or builds a string. **Rejected**, by a probe (`adjudicate/probe.log`, 2026-09-12T06:08:53Z):
4b's encoder shape — `getBytes(UTF_8)` per code point, a lazy builder returning the same instance
for an all-URI string, `null` in → `null` out, and a lone `%` passed through. Both encoders were
run against both pull requests' `PercentEncodingTest` rows: on the merged class exactly three of
4b's nineteen rows are red (`aMalformedPercentPassesThrough`, `everyUriCharacterIsLeftAlone`'s
`isSameAs`, `nullPassesThrough`) and all sixteen others green; on 4b's encoder seven of 4a's rows
are red. The three become `aPercentThatStartsNoTripletIsEncoded` (already 4a's), `isEqualTo`, and
`nullIsACallerError` (already 4a's). A same-instance return is an allowed optimisation, never a
contract. Evidence: `design-4a.md` §1.1/§2, `design-4b.md` §1.1/§2, `adjudicate/`.

### 3 — Both parameters, from the sanitised name, gated at U+007F, `filename=` first

`attachment()` sanitises, then: a name with every UTF-16 unit below 0x80 keeps today's exact
single-parameter value, byte for byte (four exact pins and nine `contains` readers stay green);
any other name is sent twice — `attachment; filename="<ASCII fallback>"; filename*=UTF-8''<octets>`,
`filename=` first (RFC 6266 Appendix D; jakarta.mail reads the last parameter, Python's
`headerregistry` the first — both measured), no language tag, both halves built from the
**sanitised** name so a `"`, `\`, control or bidi character reaches neither. Latin-1 is above
the gate on both halves (U1). `extValue` is applied once, to decoded text; `%` is data
(`100%完了.csv` → `100%25%E5%AE%8C%E4%BA%86.csv`). **Rejected:** a `> U+00FF` gate (the
`gateFF` variant: `café.csv` single-form with a raw 0xE9 — the measured Chromium loss), an
unconditional emitter (`unconditional`: four reds in three files), `filename*` first (`starFirst`:
first-wins readers take the ext-value), a `?` fallback (`qFallback`: the corruption symptom).
Evidence: `design-4a.md` §2 items 1-6, `synth-4a/logs/unit-matrix.txt`, `wire-matrix.txt`.

### 4 — The fold is by Unicode property, minus two joiners; the sanitiser stays transparent

`sanitizeFilename` stays Unicode-transparent (U4; `ContentDispositionTest:21-22` is relabelled as
the deliberate transparency contract, never cited as wire coverage) and folds, one `_` per code
point: `"` and `\`; every `Cc` (C0, DEL, C1); `Zl` and `Zp`; every `Cf` (all 170 on this JDK —
the twelve `Bidi_Control`, ZWSP, BOM, WJ, SHY, the invisible operators, the tag block) **except
U+200C ZWNJ and U+200D ZWJ**, which a Persian word and an emoji sequence are spelled with (IDNA2008
CONTEXTJ is the precedent for the exception; Chromium's `[:Cc:][:Cf:]` for the categories). A name
that is blank, `.` or `..` — exactly those — becomes `_` (`PollLoop.plainName`'s rule; `report..csv`,
`.hidden` survive). `/` is not folded: RFC 6266 section 4.3 puts path stripping on the recipient,
and curl and wget were measured leaving the download directory on neither half. **Rejected:** an
enumerated list — three attacks found three different missing members in three lists (U+061C;
U+2060/U+180E/U+206A-F/U+FFF9-B; the tag block), which is the evidence that enumeration is the
wrong shape; folding the joiners for Chromium parity — two independent fatal findings and the
transparency contract; folding `/` — a widening with no measured traversal. Costs, stated: U+00AD
and the subdivision-flag tag sequences fold. Evidence: `design-4a.md` §2 items 7-8, §7.3; the
whole-repertoire walk `everyControlAndFormatCharacterIsFoldedAndNothingElseIs` (red on
`enumeratedCf`, `tagsKept`, `separatorsKept`, `joinersFolded`, `slashFolded`, `c1Partial`).

### 5 — The ASCII fallback: a mark rides, a base letter survives, the rest is an underscore

Per code point of the sanitised name: ASCII stays; a combining mark (`Mn`/`Mc`/`Me`) adds nothing
— it rides on the character before it (`_` if it is the first) — so a decomposed spelling and its
composed twin fall back the same (`cafe<U+0301>` and `café` → `cafe`); any other code point whose
canonical decomposition (NFD, never NFKD) is an ASCII letter or digit followed only by marks becomes
that letter (`é→e`, `Ü→U`, `K→K`); everything else is one `_` (`受注一覧.csv` → `____.csv`,
`Straße.csv` → `Stra_e.csv`, `ﬁle.csv` → `_le.csv`). The unnameable rule of decision 4 is
re-applied to the fallback's **output**, because dropping a mark can leave exactly `..`
(`..<U+0301>` → `filename="_"; filename*=UTF-8''..%CC%81`). The fallback is printable ASCII with no
`"` or `\` for every code point of the repertoire (walked). **Rejected:** a standalone mark → `_`
(the `marksKept` variant: a macOS-decomposed `café.pdf` uploads as `cafe_.pdf`); whole-string NFD
or NFC first (`perNfdCp`: U+037E becomes `;` before any letter check, Hangul falls back three per
syllable); no output rule (`noOutputRule`: `filename=".."` re-created). This was the defect every
angle design shared — the exact rule on the sanitiser's input only — and no single attack held
both halves. Stated, not pinned as a defect: Jamo-spelled Hangul is one `_` per jamo.
Evidence: `design-4a.md` §0, §2 item 9, §7.3.

### 6 — The split bundle is named after its stem, and the rider rides

`SqlStep.zipName` (`:435-442`) strips the placeholder's separator **after** the extension is cut
(`orders-{key}.csv` → `orders.zip`), `dot >= 0` so a placeholder-only `{key}.csv` bundles as
`export.zip` (was `.csv.zip`), package-private so `SqlStepZipNameTest` pins it without a boot.
Rides in 4a because 4a's `splitBy` fixture would otherwise pin `__-.zip` (D2). Still filed: the
two-part extension (`orders-{key}.tar.gz`). Evidence: `design-4a.md` §1.3; the `zip*` variants.

### 7 — `uriLiteral` encodes what no URI can carry, including the eight graphics and a lone `%`

Everything RFC 3986 lets a reference spell stays — unreserved, gen-delims, sub-delims, an authored
`%XX` triplet — and everything else becomes UTF-8 octets: non-ASCII, space, controls, DEL, the eight
ASCII characters no RFC 3986 production admits (`" < > \ ^ ` { | }`) and a `%` that starts no
triplet (`/100%` → `/100%25`). Idempotent: applying it twice is applying it once, and every legal
authored reference is unchanged. `[` and `]` stay (legal in an IPv6 host) although the JDK refuses
them in a path — disclosed, not encoded. **This deviates from the measurement record's scope
wording "leave every ASCII byte alone" on nine characters, every one of them illegal in a URI.**
Settled by a probe, not a preference: the JDK client — the follower behind every redirect
integration test, `LoopbackCall` and the stack-shell relay — throws `IllegalArgumentException` on
`/landed{x`, `/landed100%` and `/landed"x` and lands on the encoded forms; curl lands on both;
`java.net.URI` rejects each raw. **Rejected:** printable ASCII as the allow-list (the three 4b
angle designs), keeping the eight (4a's contract design), passing a malformed `%` through (4b's
synthesis — the same principle that encodes the eight encodes the lone `%`, and the JDK probe covers
it). A HEXDIG is ASCII-only: `Character.digit` admits a fullwidth digit (`hexDigAnyScript`).
Evidence: `synth-4a/logs/readers.out` (`Follow.java`), `attack-4b-minimal-spec.md`,
`adjudicate/probe.log`.

### 8 — Encoded once, at `BasePath.url`, after the join; the seam stays null-transparent

`BasePath.url` percent-encodes the **joined** result (prefix, `/_as/<role>`, path, query, fragment;
both the asset and the ordinary branch; absolute and protocol-relative references included) and
nothing before it; a null path passes through as today (`BasePaths.join` returns it, and the
encoder's null rule is a caller error, so the seam guards it in one line). All 21 non-test
callers change, correctly: the nine `Location` writers, and the twelve non-Location outputs
(`ShellChrome:137/142/243/284`, `ConflictDialog:77`, `SessionExpiredDialog:39/105`,
`CopilotRoutes:78` hrefs; `FileImportProcessor:150`, `FileTransferStatusProcessor:110`,
`TransferCancelProcessor:64` JSON URLs; the OIDC `Set-Cookie` `Path=` at `OidcRoutes:127`) — each
correct by its own contract ([`base-path-emission.md`](base-path-emission.md) decision 1: encoded
once, when it becomes a wire URL; a cookie `Path` is matched against the request path as sent).
No test pins a non-ASCII output of any of them (census, 710 test files); the whole reactor ran
green under the built seam three times. **Rejected:** encoding in the two renderers and leaving
the seam (`sinkOnly` — green on every redirect row, red on the pipeline pin `P1`); encoding the
path only (`pathOnly`); `encodeSegment` on the whole target (`seg`: 51 reds including two existing
tests). Evidence: `design-4b.md` §1.3, §1.13, §3.3.

### 9 — A placeholder value is a path segment, everywhere

`RedirectRenderer`'s value arm (`:76-87`) becomes one call to `Interpolation.interpolateUrl`
(`URLEncoder` + `%20` for `+`, the `BasePath.encodeSegment` rule), so `/`, `?`, `#`, `+`, `//` in a
value are triplets and a value can never steer the target; the literal pass then leaves those
triplets alone — one encoding each. The same rule serves view links and (decision 11) declared
URI headers. **Rejected:** `URLEncoder.encode` as today (`plusSpace`: `a b` → `a+b`); `uriLiteral`
on a value (`valueUriLiteral`: `//evil.test` steers). Evidence: `design-4b.md` §1.5; U5/U14/U15,
W8/W9b.

### 10 — The four bypass writers encode at the write

`AttachmentUploadProcessor:107`, `AuthStep.activatedLocation:369-374`, and `ScimRoutes:116` and
`:170` each read a prefixed wire path back off the request and build the `Location` from it; each
wraps its built string in `uriLiteral` and must not pass the join a second time. Each is guarded
on the **real** class (a real `kind: attachment` document, a real `AuthStep("activate")`, the real
SCIM routes under a Japanese base path). **Rejected:** the census's "two bypass writers" — every
angle design inherited it, and `ScimRoutes` was found by three attacks (under every design's
backstop a SCIM create would have been a 500 after the row committed); extracting
`activatedLocation` for a unit row (a copy of the logic, not the writer). Evidence:
`design-4b.md` §1.4/§1.7/§1.10/§1.12; W15, W43, W45.

### 11 — A declared `headers:` `Location`/`HX-Redirect` is encoded by the compiler

`ResponseHeaders.apply` (`:64-68`): when the header is a URI-reference header and its declared value
is a String, each placeholder is a path segment (`interpolateUrl`) and the whole value is
`uriLiteral`'d once; every other header, and a nested map/list value, stays `Interpolation.interpolate`
as today. So the documented `Location: "/api/items/{steps.record.keys.id}"` recipe works for a
Japanese key, a whole-value placeholder can no longer steer off-site, and the ASCII recipe is
byte-identical. The prefix is still not acquired there — pre-existing, filed. **Rejected:** leaving
it, which under decision 13 turns the documented recipe into a 500 for a non-ASCII key. Evidence:
`design-4b.md` §1.8; J1/J2, W34/W34b/W35/W35b/W28 (`hdrRaw`, `hdrInterpRaw`, `hdrNameCase`,
`overbroadHdr`).

### 12 — The app-local gate refuses every control character

`BasePaths.isLocal` refuses every C0 control and DEL (was CR/LF only). A browser deletes a tab, CR
or LF from a URL before parsing it, so `/<TAB>/host/x` navigates to `//host/x` — off-site, past
the two prefix checks — and no control character has a place in a return target. Nine callers
(the login `next`, the OIDC return, the SAML RelayState, `location: back`'s `_return`, and their
siblings) fall back to their caller's default. A space stays local. Measured: Chromium 152 lands
off-origin on HEAD's raw-tab `Location` and stays on-origin after the fix (design evidence, never a
guard). This is a security fix and the CHANGELOG records it as one. Evidence: `design-4b.md`
§1.2, §9; C17, `LoginRedirectsTest`, U17, W25/W27 (`isLocalTab`).

### 13 — The backstop refuses, the seam encodes (D-L = b), and the C0/DEL widening rides

`RouteEdge.wireHeaders` (`:600-604`): every header value carrying a C0 control other than HTAB, or
DEL, is refused; a `Location` or `HX-Redirect` (the shared predicate) carrying anything outside
printable ASCII U+0021..U+007E — tab, space, control, DEL, non-ASCII — is refused. The refusal is
the #975 shape: `IllegalStateException` on the route's thread, a rendered **500** with the
`UNRENDERED_FAILURE` envelope (`TqlDomain.ROUTE`, by constant name), the refused header absent, one
`ERROR` line `Route <id> failed with nothing to render it` carrying `Response header '<name>' of
route <id> carries the control character U+XXXX; refusing to write it` or `… is not a URI-reference:
it carries U+XXXX un-encoded; refusing to write it` — before any byte is written, on the buffered
and the streamed shape alike. HTAB stays accepted in every other header (RFC 9110 field-content).
**Layering with 4a:** a declared download filename is folded by the helper and never reaches the
edge with a control; the edge's refusal is for values that bypass the helper — a hand-written
route, a `headers:` value, a future writer. **Layering with decision 11:** a declared String
`Location` is encoded before it reaches the backstop; only a nested value or a programmatic
writer can trip it. **Wider than D-L's letter** ("a byte ≥ 0x80"): tab and space are refused in a
URI header too, because that is exactly what a browser or the transport silently mangles and the
tab is the measured open redirect; the eight non-URI graphics are **not** refused (the encoder
removes them from every framework value; a hand-written one is not a wire hazard, and the JDK
follower refuses it loudly on the client side). **Rejected:** `≥ 0x80` only (`bsNonAsciiOnly`,
red on W29/W29b/W29c); encoding at the edge instead (D-L (c): the compiler's unit tests cannot see
the fix and the encoding moves to the wrong layer); refusing the eight at the edge (a 500 with no
measured defect behind it); trimming OWS off an authored literal (a silent repair of an authored
value — `location: "/foo "` now lands on `/foo%20`, stated under *What this breaks*). Evidence:
`design-4b.md` §1.9, §2 item 11, §9; W16-W23, W29*, W37, W41 (`noBackstop`, `noC0`, `nodel`,
`overWideC0`, `bs3xx`, `nohx`, `hdrNameCase`).

### 14 — The login bounce carries the query once

`ErrorResponseRenderer:251-253`: `uri()` → `path()` (the normalized path — dot segments resolved,
`//` collapsed, non-ASCII decoded); the suffix below appends the query once. Both topologies read
the same variable; `AuthStep.wirePath` had fixed the same bug one file over. Rides in 4b (D2, the
login fixture pins it either way). Evidence: `design-4b.md` §1.6; E1, W11/W12/W44/W32
(`noRider1`, `riderHosted`), the `StackIdentityIntegrationTest` row.

### 15 — `bom:` is a declaration on the `export:` block, refused by the linter where it cannot apply

`bom:` is a `Boolean` on `ExportSpec` (route and job step alike, `ImportSpec.headerRow`'s shape),
schema type `boolean`, **absent means false**; the YAML loader's coercions apply as for every other
`Boolean` key (measured table in `design-4c.md` §2). Honoured by the `csv` codec only. On
`format: excel` or `format: pdf` the key — declared with any value, `false` included — is refused
by **the linter** as `INAPPLICABLE_EXPORT_OPTION` (the existing TQL-YAML-1005 rule, its severity
error, its presence gate), from one shared helper called on both arms, with the message
`<label>bom: is a csv option - <format> output has no text stream to mark`. An unset `format:` on
a route is the csv default and is not refused; on a job step it is the existing missing-format
finding alone; a format the framework does not ship is not judged (a module codec reads
`spec.bom()` itself). **No boot-time check**: nothing runs the linter at boot, the shipped
`sheet:`-on-pdf arm has the same altitude, and closing that gap for inert export keys is slice 5's
charter (`audit-medium-leads.md:227`). **Rejected:** a value gate (`lint-true-only`: `bom: false`
on a workbook lints clean — the rule's own arms fire on presence, `ExportRules:71/:127`); refusing
unknown formats (`refuse-unknown-format`: `lintExportRowCap:263-266` answers for shipped formats
only); a boot refusal (slice 5's row, read); a case-folded `format:` (`FileCodecs.require` is
case-sensitive; `format: Excel` fails at boot as an unknown format, never as a silently unmarked
workbook — unfiled #21). Evidence: `design-4c.md` §1.4, §2, §9; matrix columns 17-29.

### 16 — The mark is octets on the stream, before any text, once per stream, never derived

`FileWriteSpec` gains a tenth component, primitive `boolean bom`, last; the three delegating
constructors pass `false`; `withFormatting` carries it (the line rebuilt on every route export —
`drop-withFormatting` is green on every codec unit test and red only on the route integration
test, which is why that test is mandatory). `ExportSpec.toWriteSpec` unboxes with
`Boolean.TRUE.equals`. `CsvFileCodec.write` writes `EF BB BF` to `out` before the writer exists
when `spec.bom()` — so an export with no rows is exactly three bytes (D1 ii), a `splitBy:` export
carries one mark per ZIP entry and none on the archive, `marked[3..] == plain` byte for byte over a
body wider than the 8 KiB encoder buffer, and nothing is derived from `locale:`, `timezone:`,
`Accept-Language` or the principal. `Content-Type` stays `text/csv; charset=utf-8`. A cell that
begins with U+FEFF is never inspected. **Rejected:** a 9-arg compatibility constructor (one more
place to forget a component; the one 9-arg site is a test); the mark in `ExportWrite` (green on the
wire, red at the codec — `SplitExport.write` calls the codec per entry); a stateful codec
(`mark-once-per-codec`). Equivalent, not rejected: writing U+FEFF through the writer
(`mark-via-writer`, byte-identical on every guard — the octets-on-`out` shape is the reason the
zero-row case needs no second path). Evidence: `design-4c.md` §1.1-1.3, §3; matrix columns 2-16.

### 17 — Four CHANGELOG entries, one per pull request, inside the measured boundary

Each pull request adds its entries at the top of its section under `## Unreleased`; the sections
read in Keep a Changelog order — `### Added`, `### Changed`, `### Fixed`, `### Security` (the
last is this repository's first, for the open redirect of decision 12). The boundary
(the measurement record's §4.3): "declared download filenames", never "any Content-Disposition"; the
symptom differed by range, never "Latin-1 used to work"; the Chromium loss carries its UI-language
qualifier; "an `export:` CSV", never "CSV downloads"; "the linter refuses"; "in every client
measured"; no migration steps (pre-1.0); no "slice" on a published page. Exact text under
[Docs and CHANGELOG](#docs-and-changelog).

### 18 — One design record, landed by PR S, flipped by each slice

This file is registered in `docs-site/nav.mjs` `EXCLUDED` and `ErrorIndex.INTERNAL_DOCS` by PR S,
which ships first; 4a, 4b and 4c flip their own status line and append to *Recorded deviations*
only if the build deviates. **Rejected:** one record per pull request (three more registrations
and three more `InternalDocsSyncTest` edits); landing it with 4a (S would then ship a fix with no
record to point at). The site's completeness check fails on an excluded entry whose file does not
exist, so the file and its two registrations are one commit. The javadocs of 4c point at
[`csv-import.md`](csv-import.md) decision 10, which exists, so no rename touches code.

### 19 — The audit record is amended once with the facts, and once per slice with the status

PR S carries every measured correction to [`audit-medium-leads.md`](audit-medium-leads.md) that is
true at HEAD (the widening, the writer census, the F128 severity supersession, the mis-filed
sibling, the sequencing note, the new defect rows, the four-PR slice row); each slice changes only
its own status words. Sequential branching from fresh `origin/main` is what keeps four pull
requests editing one file safe; nothing is edited in parallel. The exact lines are under
[The audit record](#the-audit-record).

## What this slice deliberately does not touch

- `response.*.headers:` `Content-Disposition` (the sixth emitter): the helper never runs there, no
  guard here reaches it, the CHANGELOG says "declared download filenames". Injection is filed; the
  C0 hang closes with decision 13.
- The mail leg (`MailNotifier` → jakarta.mail writes `filename*` with the JVM's default MIME
  charset): file-only; the HTTP encoder has no charset to disagree with.
- `StackRelay:509` (the gateway's own header writer) and a Unicode application name at the
  gateway: the router slice, after 4b.
- The `HX-Trigger` toast escape (`ResponseHeaders.java:34`): not ridden (D2) — a JSON-escaping
  rule in a different file.
- Studio's data-browser CSV and `response.file:` templates carry no `bom:` axis (U3).
- Browsers: design evidence only, never a guard — no browser exists in CI, and CDP
  `Browser.setDownloadBehavior` discards names a real Chromium keeps.

## The slices

| # | Pull request | Modules changed | Verify set (`-pl … -am`, then the full clean verify to a file) |
| --- | --- | --- | --- |
| S | Studio's data-browser download is a CSV; this record; the audit record's facts | `tesseraql-studio-runtime`, `tesseraql-docs-reference` (`ErrorIndex`), `docs/`, `docs-site/nav.mjs`, `CHANGELOG.md` | `tesseraql-studio-runtime,tesseraql-docs-reference` |
| 4a | A download keeps its name | `tesseraql-core`, `tesseraql-pipeline`, `tesseraql-runtime` (test), `docs/`, `CHANGELOG.md` | `tesseraql-core,tesseraql-pipeline,tesseraql-compiler,tesseraql-runtime,tesseraql-docs-reference` |
| 4b | A redirect lands where it says | `tesseraql-core`, `tesseraql-security` (test), `tesseraql-pipeline`, `tesseraql-compiler`, `tesseraql-scim`, `tesseraql-runtime`, `docs/`, `CHANGELOG.md` | `tesseraql-core,tesseraql-security,tesseraql-pipeline,tesseraql-compiler,tesseraql-scim,tesseraql-runtime,tesseraql-docs-reference` |
| 4c | An `export:` CSV can carry a byte-order mark | `tesseraql-core`, `tesseraql-yaml` (+ schema), `tesseraql-operations`, `tesseraql-excel` (test), `tesseraql-runtime` (test), `examples/` (demo + `.vscode` schema copy), `docs/` (+ two regenerated pages), `CHANGELOG.md` | `tesseraql-core,tesseraql-yaml,tesseraql-operations,tesseraql-excel,tesseraql-runtime,tesseraql-docs-reference,tesseraql-maven-plugin` |

`tesseraql-docs-reference` is in every set because `-am` builds dependencies, not dependents, and
its guards walk every module's `src/main` (`InternalDocsSyncTest`, `StatusMappingLedgerTest`,
`GeneratedReferenceTest`); `tesseraql-maven-plugin` is in 4c's because `ScaffoldDogfoodIntegrationTest`
pins the `.vscode` schema copy. Then the router slice, then slice 5 (which edits the same
`CsvFileCodec.write` and the same `file-transfers.md` list as 4c — the second to land rebases and
regenerates the reference).

The ritual for every pull request, in order: branch from fresh `origin/main` (`git reset --hard
origin/main` after entering the worktree); the edits; the guards; **build the broken variants from
the branch's own sources and run the bracket, reading the build stamp** (a variant built from a
pre-edit source, a mid-run recompile, or a failed install leaving the old jar are the three ways a
column lies); `./mvnw spotless:apply`; any regen; the verify set to a file
(`./mvnw -B -ntp clean verify -pl … -am > verify.log 2>&1; echo $?`); `cd docs-site && node
scripts/sync-content.mjs && node scripts/lint-prose.mjs` (neither runs in `mvn verify`); the
CHANGELOG entry; the audit-record amendments the pull request carries; the full clean verify to a
file before the pull request opens; on the pull request, confirm "Maven verify on Java 25" actually
ran (a conflicting pull request skips CI and looks green).

## The contracts and the guards, per pull request

Names are the repository's sentence style and are the shipped method names. "Red on" names the
built variant(s) each row was written for; HEAD is red on every row that is not marked as a
control or a pin. Hazard numbers are the measurement record's §7. The full matrices, with build and run
stamps and per-column provenance, are in each pull request's description.

### PR S

**Contract.** `studio.data.export` returns a `String` on every branch, never `null`, never a Map.
Normal: RFC 4180 as Commons CSV `RFC4180` writes it — header record first, `\r\n` after every
record including the last; an empty result set is the header alone; no mark. Disabled: exactly
`# The data browser is disabled.\r\n`. Error: `"# " + message + "\r\n"`. Wire: 200,
`text/csv; charset=utf-8`, `attachment; filename="data.csv"`, body = the String byte for byte, in
both topologies (hosted: `{"__value__": …}` across the loopback hop, unwrapped at
`WorkshopTargets:95-96`). The `startsWith("login_id,")` pin the record named is wrong on the fix
too: `select * from tql_users` on the identity pack's DDL puts `user_id` first.

| guard (`tesseraql-studio-runtime`) | assertion | red on |
| --- | --- | --- |
| `StudioIntegrationTest#uiDataBrowserExportsTheViewAsCsv` (`:3009`, extended) | `startsWith("user_id,login_id,")`, `contains("\r\nu1,admin,Administrator,")`, `endsWith("\r\n")` | V0 (the Map), V2 (trailing byte), V3 (LF only), V4 (a mark at byte 0), V5 (`strip()`), V6 (`byte[]`) |
| `…#uiDataBrowserExportRespectsAFilter` (`:3019`, extended) | `matches("user_id,login_id,[^\\r\\n]*\\r\\n")`, `doesNotContain("admin")` | V0, V2, V3 |
| `…#uiDataBrowserExportOfAnUnknownTableIsANote` (new) | `isEqualTo("# No such table: nope\r\n")` | V0, V1 (normal branch only), V1b (error branch still a Map), V2, V3, V9 (`"# " + ex`) |
| `…#uiDataBrowserExportOfAnUnreadableTableIsANote` (new; a table in a second schema the catalog walk lists and the search path cannot reach) | 200, `text/csv`, `startsWith("# Export failed: ")`, `endsWith("\r\n")` | V8 (`catch` narrowed to `IllegalArgumentException` — all-green on the four rows above) |
| `…#aCellWithQuotesAndNonAsciiIsWrittenAsRfc4180` (new; one row `He said "hi", <b>&é 受注` inserted, exported filtered, deleted in `finally`) | the exact two-record body with `""`-doubled quotes and the raw non-ASCII | VT2 (the template in escaped `[[…]]` mode — all-green on the four rows above), V4, V5, V6, V0 |
| `StackStudioIntegrationTest#aDataExportRidesTheDelegationAsAScalar` (new, real `MultiAppGateway` + `DevMode`, member `shop-a`) | 200, `text/csv`, `isEqualTo("# The data browser is disabled.\r\n")` | V0, V1, V2, V3, V7 (null note → 500), VH1 (the hop re-wraps), VH2 (the shell stops unwrapping), VH4 (a second envelope key), VH5 (the hop strips) |

Hazard 12 throughout. The last two guards are the two green-on-defect shapes the attack found and
are adopted; the hosted normal branch with a real CSV was run by the attack (three YAML lines plus
one table) and is cited, not shipped.

### PR 4a

**Contract.** Decisions 2-6. Grammar `attachment; filename="<fallback>"; filename*=UTF-8''<octets>`;
gate at U+007F on the sanitised name; `extValue` = RFC 8187 `attr-char`, `%` is data; UTF-8
computed, upper-case hex, code-point walk, U+FFFD, NPE on null (`sanitizeFilename(null)` stays
`null`); the fold of decision 4; the exact `.`/`..`/blank rule on both the sanitiser and the
fallback's output; the fallback alphabet of decision 5; `uriLiteral` of decision 7 lands here with
its unit rows and no production caller; no new error code, no log line, no edge change.

| guard | fixture → exact assertion | red on | hazard |
| --- | --- | --- | --- |
| core `ContentDispositionTest#everyQuotedStringBreakerIsReplaced` (kept) | `:16-20` verbatim | — (kept contract) | — |
| `#theSanitizerIsTransparentToEveryScript` (`:21-22` relabelled, U4) | Japanese, `café (1).csv`, Persian with ZWNJ, emoji with ZWJ, Devanagari virama+ZWJ are their own `sanitizeFilename` | joinersFolded | 1 |
| `#theAttachmentValueIsWholeAndQuoted` (kept `:27-28`) | the ASCII pin | unconditional | 20 |
| `#anAsciiNameKeepsTheValueItAlwaysHad` | `orders.csv`, `report..csv`, `100%.csv`, `a;b=c, d.csv`, `.hidden`, `caf%C3%A9.csv`, `~\|:*?<>.csv` → single-parameter | unconditional, bluntDots | 20 |
| `#aNameADirectoryEntryCannotCarryBecomesAnUnderscore` | `.`, `..`, ``, `   `, U+3000 → `_`; `report..csv`, `..csv`, ` ..` unchanged | bluntDots, noDotRule, emptyOnly, gateOnRaw | — |
| `#aLatin1NameCarriesAnAsciiFallbackFirstAndTheNameItselfSecond` | `café.csv` → `attachment; filename="cafe.csv"; filename*=UTF-8''caf%C3%A9.csv`; `Übersicht.csv`; `ÿ.csv` | **gateFF**, noStar, starFirst, lowerHex | 1-4 |
| `#aDecomposedSpellingHasTheSameFallbackAsTheComposedOne` | `cafe<U+0301>.csv` → `"cafe.csv"; …cafe%CC%81.csv`; `Vie<U+0323><U+0302>t` → `Viet`; `か<U+3099>` → `_` | **marksKept** | 4 |
| `#aLetterWithoutADecompositionFallsBackToAnUnderscoreNotARawByte` | `Bjørn.csv` → `"Bj_rn.csv"`; `Straße.csv` → `Stra_e.csv` | **fallbackFF**, gateFF | 4 |
| `#theFallbackHalfIsPrintableAsciiForEveryCodePoint` | every code point U+0080..U+10FFFF as `a<cp>b.csv`: `filename=` is `0x20..0x7E` without `"`/`\` | fallbackFF, gateFF, starFirst | 4 |
| `#theFallbackNeverIntroducesPunctuationTheNameDidNotHave` | U+037E, U+226E, U+2260, U+226F, U+1FEF → `a_b.csv`; U+212A → `Kelvin.csv` | **punctBase**, perNfdCp | — |
| `#theFallbackDecomposesCanonicallyNotByCompatibility` | `ﬁle.csv` → `_le.csv`; `①Ａ.csv` → `__.csv` | **nfkd** | — |
| `#aNonLatinNameFallsBackToOneUnderscorePerCodePoint` | `受注一覧.csv` exact; `한글.csv` → `__.csv`; `न<U+093E>म.csv` → `__.csv` | **perNfdCp**, marksKept, qFallback, utf8Boundary | 2 |
| `#anAstralCharacterIsOneCodePointOnBothHalves` | `a😀b.csv` → `"a_b.csv"; …a%F0%9F%98%80b.csv` | **charWalk**, **perUnitFallback** | 4 |
| `#aLoneSurrogateIsTheReplacementCharacterNotAQuestionMark` | `a\uD83Db.csv`, `a\uDE00b.csv` → `"a_b.csv"; …a%EF%BF%BDb.csv` | **surrogateQ** | — |
| `#theFallbackNeverNamesADirectoryEntry` | `..<U+0301>` → `filename="_"; filename*=UTF-8''..%CC%81`; `.<U+0301>`; marks only → `_` | **noOutputRule**, bluntDots, emptyOnly, marksKept | — |
| `#everyControlAndFormatCharacterIsFoldedAndNothingElseIs` | every code point U+0000..U+10FFFF: `a_b.csv` iff `Cc`, `Cf` minus the joiners, `Zl`, `Zp`, `"`, `\`; plus 24 named members | **c0Partial, c1Partial, c1Through, enumeratedCf, bidiRloOnly, tagsKept, separatorsKept, joinersFolded, slashFolded, foldMissing** | 4, 13 |
| `#aFoldedCharacterReachesNeitherHalfOfANonAsciiName` | `受注".csv`, `受注\.csv`, `受注<U+202E>gpj.exe`, `受\r\n注.csv`, `受注<U+0000>.csv` folded in both halves | **fallbackUnsanitised, starUnsanitised**, foldMissing | 4 |
| `#aControlIsFoldedBeforeTheGateSoAnAsciiNameStaysSingleForm` | `a<U+0092>b.csv`, `a\tb.csv`, `a<U+0085>b.csv`, `a<U+007F>b.csv`, `a<U+001B>b.csv` → `attachment; filename="a_b.csv"`; `inv<U+202E>gpj.exe`, `inv<U+061C>gpj.exe`, `inv<U+2060>gpj.exe` → `"inv_gpj.exe"` | **gateOnRaw**, c1Through, c0Partial, enumeratedCf, bidiRloOnly, unconditional | 4 |
| `#aJoinerIsPartOfTheNameOnTheEncodedHalf` | `می<U+200C>خواهم.csv` → `"________.csv"; …%E2%80%8C…`; the emoji ZWJ sequence | **joinersFolded**, charWalk, perUnitFallback | — |
| `#theEncodedHalfEscapesEveryDelimiterOfItsOwnGrammar` | `受注 (1);x='%*.csv` → `…%20%281%29%3Bx%3D%27%25%2A.csv`; `受注;filename=evil.exe.csv` | **semicolonAttr, percentAttr, alphaMissing** | — |
| `#aNullNameIsAProgrammingError` | `sanitizeFilename(null)` null; `attachment(null)` NPE `filename` | **nullString** | — |
| `#anAstralFormatCharacterFoldsToOneUnderscore` | `tag<U+E0041><U+E0042>.pdf` → `tag__.pdf` | **tagsKept**, enumeratedCf | — |
| core `PercentEncodingTest#theAttrCharListIsExactlyRfc8187OverAllOfAscii` | the 74-character string is its own `extValue`; every other ASCII code point `%XX` | commaAttr, semicolonAttr, percentAttr, alphaMissing, lowerHex | — |
| `#theUriLiteralListIsExactlyWhatRfc3986AdmitsOverAllOfAscii` | both sides over all of ASCII (lone `%` included); an authored reference unchanged; the eight → triplets | uriColon, uriTildeDropped, uriKeepsEight, uriLonePercentKept | — |
| `#aUriLiteralIsIdempotentOnAnAuthoredReference` | `/caf%C3%A9/J-1001?x=1&y=%20#top` unchanged; a mixed value encoded once and again unchanged | uriEncodesPercent, uriKeepsEight, uriLonePercentKept | — |
| `#aPercentThatStartsNoTripletIsEncoded` | `/100%` → `/100%25`; `/a%zz` → `/a%25zz`; `%41%4a%4F` unchanged; `/a%１２` → `/a%25%EF%BC%91%EF%BC%92` | uriLonePercentKept, uriEncodesPercent, **hexDigAnyScript** | — |
| `#anAstralCharacterIsFourOctetsAndALoneSurrogateIsTheReplacementCharacter` | `a😀b` on both lists; high, low and reversed-pair surrogates → `%EF%BF%BD`; U+1D800 → `%F0%9D%A0%80` | charWalk, surrogateQ, **surrogateCast** | — |
| `#theEncoderAgreesWithTheJdkOnEveryCodePoint` | every code point U+0080..U+10FFFF equals `%XX` of `getBytes(UTF_8)`; the seven length boundaries by name | **utf8Boundary, utf8Boundary4**, charWalk, lowerHex | — |
| `#nullIsACallerError` | both methods NPE on null | — (the `requireNonNull` line) | — |
| pipeline `SqlStepZipNameTest#theBundleIsNamedForTheStemWithoutTheSeparatorThePlaceholderLeaves` | `orders-{key}.csv`, `daily-orders-{key}.csv`, `orders_{key}.csv`, `orders.{key}.csv`, `受注-{key}.csv`, `orders-{key}` → `orders.zip` / `daily-orders.zip` / `受注.zip` | **zipStripBeforeCut, zipNoAnchor, zipDashOnly** | 21 |
| `#aPlaceholderOnlyFilenameBundlesAsExport` | `{key}.csv`, `-{key}`, `.{key}.csv` → `export.zip` | **zipDotGtZero, zipNoExportDefault** | 21 |
| runtime `DownloadFilenameIntegrationTest` (a copy of `examples/user-admin-app` + eleven `query-export` routes, one `splitBy` route, the shipped `inventory-app` attachment declaration read at test time, a job; Testcontainers, port 0, JDK client pinned `HTTP_1_1` with a 30 s timeout; one h2c client) | | | |
| `#anAsciiExportNameIsTheValueItAlwaysWas` | `orders.csv` → `attachment; filename="orders.csv"` | unconditional | 20 |
| `#aLatin1ExportNameCarriesBothHalvesBecauseTheGateIsAsciiNotLatin1` | `café.csv` → both halves, every char < 0x80 | HEAD (the JDK's `café.csv` — hazard 1 in the flesh), gateFF, noStar, starFirst, lowerHex | 1-4 |
| `#aLatin1LetterWithoutADecompositionFallsBackToAnUnderscoreNotARawByte` | `Straße.csv` → `"Stra_e.csv"; …Stra%C3%9Fe.csv` | **fallbackFF** (the only wire row that sees it) | 1, 4 |
| `#aJapaneseExportNameIsCarriedInFilenameStarOnBothTransports` | `受注一覧.csv` over HTTP/1.1 and h2c (`HTTP_2` asserted) | noStar, starFirst, qFallback, lowerHex | 17 |
| `#anAstralCharacterIsOneCodePointNotTwoUnits` | `a😀b.csv` | charWalk, perUnitFallback | 4 |
| `#aRouteIdIsTheDefaultNameAndIsSpelledTheSameWay` | `id: 受注出力`, no `filename:` → `"____.csv"; …` (`RouteCompiler:1650`) | noStar, … | — |
| `#aQuoteInsideANonAsciiNameReachesNeitherHalf` | `受注"一覧.csv` → `"_____.csv"; …%E6%B3%A8_%E4%B8%80…` | **fallbackUnsanitised** (a broken quoted-string with a 200), **starUnsanitised** (`%22` in `filename*`) | 4 |
| `#aControlOrBidiCharacterIsFoldedBeforeTheWire` | `a<U+001B>b.csv` → `attachment; filename="a_b.csv"` (presence by equality — HEAD ships a 200 with no header); `inv<U+202E>gpj.exe` and `inv<U+061C>gpj.exe` → `"inv_gpj.exe"` | **c0Partial** (absent), **enumeratedCf** (U+061C), foldMissing, bidiRloOnly, gateOnRaw | 13 |
| `#aJoinerInsideAWordSurvivesToTheEncodedHalf` | `می<U+200C>خواهم.csv` | **joinersFolded** | — |
| `#aSplitExportNamesItsBundleAfterTheStemWithoutADanglingDash` | `受注-{key}.csv` + `splitBy: status` → `application/zip`, `"__.zip"; …%E5%8F%97%E6%B3%A8.zip` | **zipStripBeforeCut** (`__-.zip`) | 21 |
| `#anUploadedNameComesBackOnTheDownloadAndOnTheListing` | multipart upload then the download header and the LIST row: `請求書.pdf`, `café.pdf`, `cafe<U+0301>.pdf` → `"cafe.pdf"`, `請求<U+202D>exe.pdf` → `"___exe.pdf"`, `Bjørn.pdf`, `a<U+0092>b.pdf` → `"a_b.pdf"` (the end user's path: Netty passes C1, overrides and marks) | **marksKept** (`cafe_.pdf`), **starUnsanitised**, **bidiRloOnly**, **c1Through**, foldMissing, fallbackFF, gateFF, gateOnRaw | 1, 4 |
| `#aJobExportReServedByTheOpsConsoleKeepsItsName` | run `user.exportNamed`, then `GET /_tesseraql/ops/batch/transfers/{id}/file` → the Japanese stem in both halves | noStar, … | 1 |

The bracket of record: 31 unit rows × (HEAD, FIX, 50 one-edit variants) — FIX 0 red, HEAD 27 red
(green only on the four kept contracts), every variant red on at least one row, the eighteen
singletons red on exactly the row built for them; 12 wire rows × 52 boots of the real runtime —
FIX 0 red, HEAD 11 red (the ESC route reads "no Content-Disposition"), twenty variants red at the
wire. Thirty variants are caught by the unit rows only, and why the wire is blind to each is stated
(the encoder's arithmetic and lists; the fallback's algorithm; the fold's rarer members; the
URI-literal list, which has no production caller until 4b; the rider's other shapes). Not built:
`defaultCharset` (no site in this encoder); a ≥ 512-char h2c fixture (the output is pure ASCII and
the Huffman leg was measured on both transports); browsers.

**Existing tests that change (4a).** `ContentDispositionTest:21-22` moves and is relabelled;
`:16-20` and `:27-28` kept verbatim. `RouteRecipeIntegrationTest:119-120`,
`StudioIntegrationTest:2072-2073/:2101-2102` (the exact ASCII pins) and the nine `contains(<ASCII
name>)` readers are untouched and green. `ExportRowCapIntegrationTest:81-82` keeps `contains(".zip")`
while `items-{key}.csv` now bundles as `items.zip`. Nothing goes red and stays red.

### PR 4b

**Contract.** Decisions 2, 7-14. The URI-literal list; encoded once at the seam, the four bypass
writers and the declared URI headers; a placeholder value is a segment; the gate refuses every
control; the backstop's two checks, status, envelope and log line; the bounce carries the query
once; ordering — `BasePaths.join`, then `withStudioMember`, then the encoder, and the backstop last
on the route's thread before any byte is written. An absolute target's host is percent-encoded,
not IDNA-converted (filed); OWS is not trimmed (stated).

| guard | fixture → exact assertion | red on |
| --- | --- | --- |
| core `PercentEncodingTest` (+17 rows: C1-C5, C12, C15, C16, C6 as equality, C13, C7, C7b, C8, C10, C11, C14, C19) | `/café` → `/caf%C3%A9`; `/受注一覧`; `/a😀b` → four octets; `/a b` → `%20`; C0, DEL alone, TAB, C1 encoded; every URI character left alone (`isEqualTo`); the eight graphics → triplets; an authored triplet, beside non-ASCII, idempotent; a lone surrogate → `%EF%BF%BD`; upper-case hex; twice equals once; an absolute reference by the same rule; `isUriReferenceHeader` on four spellings, `X-Toast`, `Link`, null | e9, gateFF, charWalk, **spacekeep**, **delkeep**, **tabkeep**, double, **plusenc**, **asciiNine**, **surrq**, lowerHex, **hdrNameCase**, **nohx** |
| core `BasePathsTest#isLocalRefusesEveryControlCharacter` | `/\t/evil`, `/\t\\evil`, CR/LF, DEL, VT → false | **isLocalTab** |
| `#isLocalKeepsAcceptingLocalPathsAndRefusingTheOldTricks` (control) | `/a b?x=y#z`, `/受注一覧?page=2`, `/` true; `//evil`, `/\evil`, `relative/path`, null false | — |
| security `LoginRedirectsTest#rejectsOffSiteAndMalformedTargets` (+2 entries) | the two tab shapes → `isSafe` false | isLocalTab |
| pipeline `BasePathTest#urlEncodesTheJoinedResultOnce` | bind `/受注`, `url(exchange, "/a b/é")` → `/%E5%8F%97%E6%B3%A8/a%20b/%C3%A9` | **sinkOnly**, pathOnly, seg, spacekeep, e9, gateFF, raw, lowerHex |
| `#urlEncodesTheAssetBranchToo` | `/assets/app.css` under the prefix | **asset**, sinkOnly, pathOnly, seg |
| `#anAsciiUrlIsTheSameStringItWasHandedAfterTheJoin` (control) | `/apps/shop-a/items?page=2#row-1` equal; `url(null, null)` null | — |
| compiler `RedirectRendererTest` (+17 rows, +2 list entries) | a literal Japanese / Latin-1 / astral `location:`; pre-encoded not encoded twice, alone and beside a Japanese segment; query and fragment left alone; `?q=a+b` keeps its `+`; an expression value with a space → `%20`, in Japanese encoded once, with a slash stays a segment, cannot become an off-site target; `back` with a Japanese / encoded / mixed `_return`; `back` refuses the two tab shapes; a Japanese base path; the htmx caller; an absolute redirect with a non-ASCII path | e9, raw, lowerHex, seg, double, **plusenc**, **plusSpace**, **valueUriLiteral**, isLocalTab, **pathOnly**, **absonly** |
| compiler `JsonResponseHeadersTest` (+2) | a declared `Location` with a Japanese key → `/api/items/%E5%8F%97%E6%B3%A8-001` (+ the `HX-Redirect` twin, the lower-case key, `X-Msg` untouched); a placeholder is a segment, `//evil.test/x` → `%2F%2Fevil.test%2Fx` | **hdrRaw**, **hdrInterpRaw**, **hdrNameCase**, **nohx**, **overbroadHdr** |
| compiler `ErrorResponseRendererTest#theLoginBounceCarriesTheQueryExactlyOnce` (+1) | 401 on `/plain/secret?q=1&r=2` → `Location` exactly `/_tesseraql/login?redirect=%2Fplain%2Fsecret%3Fq%3D1%26r%3D2` | **noRider1** |
| runtime `RedirectLocationIntegrationTest` (new; three boots — root, `basePath: /受注` with SCIM, root with a hosted member and a real `AuthStep("activate")`; landing routes at distinct statuses 225-230; JDK client `HTTP_1_1`, `followRedirects(NEVER)` except the one Japanese follow; a raw socket for the tab rows) | W1-W47 as in `design-4b.md` §3.5: every literal / `_return` / expression / base-path / declared-header / login-round-trip / upload / activation / SCIM row asserts the exact `Location` or `HX-Redirect` and, for the landing rows, the distinct status | e9, raw, lowerHex, seg, double, plusenc, plusSpace, valueUriLiteral, pathOnly, absonly, asciiNine, hdrRaw, hdrInterpRaw, hdrNameCase, nohx, overbroadHdr, isLocalTab, noRider1, **riderHosted**, **attachRaw**, **authStepRaw**, **scimRaw** |
| runtime `RouteEdgeHeaderGuardIntegrationTest` (+10) | a raw non-ASCII `Location` / Latin-1 / `HX-Redirect` / `location` spelling / 201 → **500**, no header; a tab or space in a URI header → 500 (raw socket); an encoded `Location` passes (control); non-ASCII on any other header passes (control); buffered `X-Toast: a<VT/DEL/NUL>b` → 500 within the timeout; the streamed shape → 500 with `Content-Type` present and `Content-Disposition` absent; a tab in an ordinary header stays accepted, buffered and streamed | **noBackstop**, **bsNonAsciiOnly**, **bs3xx**, **nohx**, **hdrNameCase**, **noC0**, **nodel**, **overWideC0** |
| runtime `StackIdentityIntegrationTest#theHostedLoginBounceCarriesTheQueryExactlyOnce` (+1); `StackActivationIntegrationTest#anActivationRedirectToAJapanesePageIsPercentEncoded` (+1, `installApp` gains `web/受注/get.yml`) | the hosted bounce with a query, once; `/shop-a/_as/shop-a.sales/%E5%8F%97%E6%B3%A8` | riderHosted; authStepRaw |

The bracket of record: 110 guard rows and 3 disclosed rows × (HEAD, FIX, 37 variants), two real
runtime boots per column — FIX 0 red; HEAD 89 red, its 21 greens exactly the designated controls
and idempotence pins; every variant red on at least one row once the encoder is 4a's
(`defaultCharset` has no site). Three single-pin encoder variants (`tabkeep`, `spacekeep`,
`delkeep`, `surrq`) are red on core rows only, because the gate and the backstop each refuse the
same byte independently (hazard N9) — the core row is the contract. The three disclosed rows: the
RFC 8288 `Link` header (red on HEAD and on the fix as first designed — now ridden, see *Settled
after the design was written*), OWS on an authored
literal (green, documents the change), the wire-spelled `_return` under a non-ASCII prefix (red on
HEAD and on the fix — the router slice). Maven on the whole overlaid tree: the touched modules'
unit suites (core 413, yaml 905, security 120, identity 72, pipeline 27, operations 104, compiler
314, scim 49) and 226 runtime tests in 31 integration classes green; the same shippable tests on a
HEAD tree: 16 unit rows red and 39 runtime failures plus 3 hangs, every red a designated one.

**Existing tests that change (4b).** `LoginRedirectsTest:24` (+2 entries), `RedirectRendererTest:136-137`
(+2 entries, +17 methods, three helpers before `:159`), `JsonResponseHeadersTest` (+2 before
`:108`), `ErrorResponseRendererTest` (+1 before `:554`), `RouteEdgeHeaderGuardIntegrationTest`
(`start()` `:44-68` gains 16 pipelines, +10 methods, `get()` pins `HTTP_1_1`),
`StackIdentityIntegrationTest` (+1 before `:106` — anchor **above** the existing javadoc, or doclint
`-Werror` fails on a dangling comment), `StackActivationIntegrationTest` (`installApp` `:447-478`,
+1). `PercentEncodingTest` gains 17 rows on the class 4a landed.

### PR 4c

**Contract.** Decisions 15-16. Key, applicability, bytes, per stream, every writer on one thread,
never derived, never inspected, headers unchanged, out of scope by decision — as `design-4c.md`
§2.

| # | guard | fixture → assertion | red on | hazard |
| --- | --- | --- | --- | --- |
| G1 | core `FileWriteSpecTest#perRequestFormattingKeepsTheByteOrderMark` | 10-arg spec, `bom=true` → `withFormatting("ja","Asia/Tokyo").bom()` true | **drop-withFormatting** | 11 |
| G2 | `#compatibilityConstructorsLeaveTheMarkOff` | 4/6/7-arg → `.bom()` false | **delegation-true** | — |
| G3 | yaml `ExportSpecTest#aDeclaredMarkReachesTheWriteSpec` | `bom=TRUE` → `toWriteSpec(null,null).bom()` true | **drop-toWriteSpec** | — |
| G4 | `#anAbsentMarkIsOff` | `bom=null` → false | **absent-true** | — |
| G5 | `#aDeclinedMarkIsOff` | `bom=FALSE` → false | **presence** | — |
| G6-G8 | yaml `AppLinterRouteExportTest#aByteOrderMarkOnAWorkbookRouteIsAnInapplicableOption`, `…OnAPdfRoute…`, `#aDeclinedByteOrderMarkOnAWorkbookIsStillAnInapplicableOption` | `format: excel`/`pdf` + `bom: true`; `excel` + `bom: false` → a 1005 error naming `bom:` and the format | no-lint-arm, lint-warning, lint-excel-only, lint-pdf-only, step-arm-only, route-arm-after-template-return, **lint-true-only** (G8) | — |
| G9-G11 | `#aByteOrderMarkOnACsvRouteIsAKnownAndCleanKey`, `#aByteOrderMarkWithoutAFormatIsTheCsvDefaultAndClean`, `#aByteOrderMarkOnAModuleFormatIsNotJudged` | `csv` + `bom: true`; no `format:`; `parquet` → no finding names `bom` | **refuse-csv-too**, **refuse-null-format**, **npe-setof** (NPE), **refuse-unknown-format** (G11) | 22 |
| G12-G15 | yaml `AppLinterExportStepTest#aByteOrderMarkOnAWorkbookStepIsAnInapplicableOption`, `…OnAPdfStep…`, `#aByteOrderMarkOnACsvStepIsClean`, `#aByteOrderMarkOnAStepWithoutAFormatStillReportsTheMissingFormat` | the job arm's 2×2 and the two clean shapes; `Step 'report': ` label | route-arm-only, **job-arm-under-template**, lint-excel-only, lint-pdf-only, refuse-csv-too, refuse-null-format, npe-setof | — |
| G16 | yaml `SchemaSyncTest#everyFixedShapeScalarIsTypedAsItsModelHoldsIt` (new) | every fixed-shape `$defs` scalar property's `type` matches its record component's Java type | **schema-wrong-type**, schema-stale | 22 |
| G17 | operations `CsvFileCodecTest#writeUsesHeaderLabelsAndColumnOrder` (`:167-181`, extended) | byte 0..2 is `E5 95 86` (`商`), then the existing String assertion | **always-bom** | 11 |
| G18 | `#writeOpensWithTheUtf8MarkWhenDeclaredAndOnlyThere` | 1 200 rows, > 16 KiB: `marked` starts `EF BB BF` and `marked[3..]` **equals** `plain` | **late**, **trailing**, **perrow**, mark-after-header-flushed, always-bom | the shared premise |
| G19 | `#aJapaneseLocaleDoesNotImplyAMark` | `locale="ja"`, `bom=false` → starts `E5 95 86` | **derive-from-locale**, always-bom | contract 6 |
| G20 | `#anEmptyExportWithTheMarkIsExactlyTheMark` | zero rows, no declared columns, `bom=true` → exactly three bytes | **mark-with-header**, **mark-after-header-unflushed**, mark-after-header-flushed | D1 ii |
| G21 | `#eachSplitDocumentOpensWithItsOwnMark` (`@TempDir`) | real `SplitExport.write`: the ZIP starts `50 4B`; each entry equals `MARK ++ "name\r\n…"` | **mark-once-per-codec**, trailing, perrow, mark-after-header-flushed | one per entry |
| G22 | runtime `ExportByteOrderMarkIntegrationTest#aDeclaredMarkOpensTheDownloadAndChangesNothingElse` | `/api/items/marked` and `/plain`, 2 000 rows: `plain.length > 16384`, marked starts `EF BB BF`, `marked[3..]` equals plain, `text/csv; charset=utf-8`, `items.csv` | **drop-withFormatting**, drop-toWriteSpec, absent-true, always-bom, late, trailing, perrow, mark-after-header-flushed | **11 — the mandatory route test** |
| G23-G25 | `#anUndeclaredExportStaysUnmarked`, `#aDeclinedMarkStaysOff`, `#aLocaleDoesNotImplyAMark` | bodies start `Name\r\n` | absent-true, always-bom, **presence** (G24), **derive-from-locale** (G25) | — |
| G26 | `#anEmptyMarkedExportIsExactlyTheMark` | `/empty` → exactly three bytes | drop-withFormatting, drop-toWriteSpec, mark-with-header, both mark-after-header variants | D1 ii |
| G27-G28 | `SchemaSyncTest#everyFixedShapeBlockMatchesItsModelExactly`, `SchemaDescriptionCoverageTest` (existing) | — | **schema-stale**, **schema-no-description** | 22 |

The bracket of record: 28 rows × (HEAD, fix, 30 broken variants, 1 control) — fix 0 red; HEAD 19
red; every broken variant red on at least one guard and every guard red on at least one built
variant; `mark-via-writer` (the control) 0 red because it is an equivalent implementation. Without
a built variant: `GeneratedReferenceTest` (red on a one-page regen), `ScaffoldDogfoodIntegrationTest`
(red-by-reversion without `-am`), `GalleryAppsIntegrationTest`, `StatusMappingLedgerTest`,
`InternalDocsSyncTest`, `SchemaCitedCodeTest`.

**Existing tests that change (4c).** `CsvFileCodecTest:167-181` extended; `FileWriteSpecTest:9-30`
+2; `AppLinterRouteExportTest:18-105` +6; `AppLinterExportStepTest` +4; `SchemaSyncTest` +1;
`JxlsFileCodecTest:204-205` (`, false`, compile only). New: `ExportSpecTest` (yaml, `model`),
`ExportByteOrderMarkIntegrationTest` (runtime). `QueryJsonIntegrationTest:272-282` untouched and
green.

## Docs and CHANGELOG

### PR S — `CHANGELOG.md`, `### Fixed`, first bullet

> - **Studio's data-browser download is a CSV.** The "Download CSV" button on the data browser
>   saved the CSV wrapped in a Java map's `toString()` — the file began `{csv=` and ended with a
>   final row holding a lone `}`, so a spreadsheet read the first header cell as `{csv=user_id` and
>   showed one extra row. The provider now returns the CSV text itself, as the OpenAPI and htmx
>   contract downloads already did, on both the standalone and the hosted (`tesseraql dev`)
>   Studio. The "data browser is disabled" and "no such table" notes download unchanged, as
>   one-line `#` comments.

### PR 4a — `CHANGELOG.md`, `### Fixed`, two bullets at the top

> - **A download keeps its name.** A declared download filename with any character outside ASCII
>   — an `export.filename`, the route id it defaults to, a `response.file:`/`response.stream:`
>   filename, a job step's export re-served by the operations console, a split export's bundle,
>   and the name a user uploaded to a `kind: attachment` document — reached the wire one byte per
>   UTF-16 unit: `?` for anything above U+00FF, and a raw Latin-1 byte below it that each client
>   decoded its own way (a Japanese-UI Chromium mis-named `Übersicht.csv`; `curl -OJ` wrote an
>   undecodable byte). `受注一覧.csv` downloaded as `????.csv`. The header now carries RFC 6266's
>   two forms for any name outside US-ASCII: an ASCII `filename=` first, then
>   `filename*=UTF-8''…` (RFC 8187) with the name itself, which browsers prefer. A name that is
>   already printable ASCII is sent exactly as before.
>
>   What changes for a client that reads only `filename=` (`curl -OJ`, httpie, Python's
>   `get_filename()`): it now sees the ASCII form — a letter keeps its base letter without its
>   diacritic, every other non-ASCII character is an underscore (`café.csv` → `cafe.csv`,
>   `Übersicht.csv` → `Ubersicht.csv`, `Straße.csv` → `Stra_e.csv`, `受注一覧.csv` →
>   `____.csv`) — where it used to see raw or `?`-mangled bytes. Control characters, invisible
>   format characters (bidirectional overrides, zero-width space, byte-order mark) and the
>   quoted-string breakers are folded to `_` in both forms before either is built; the
>   zero-width joiners a Persian word or an emoji sequence is spelled with are kept. A name that
>   is blank, `.` or `..` becomes `_`. This covers declared download filenames; a
>   `Content-Disposition` an application writes itself through `response.*.headers:` is not
>   touched.
>
> - **A split export's bundle is named after its stem.** `filename: orders-{key}.csv` with
>   `splitBy:` downloaded as `orders-.zip`: the placeholder's separator was stripped before the
>   extension had been cut, so it was never found. It is now `orders.zip`, and a placeholder-only
>   `{key}.csv` bundles as `export.zip`.

### PR 4b — `CHANGELOG.md`

Under `### Fixed`, three bullets at the top:

> - **A redirect to a route with a non-ASCII path now lands on that route in every client
>   measured.** Every `Location` and `HX-Redirect` the framework writes — a literal `location:`,
>   `location: back`, the import and bulk-report redirects, the post-sign-in return, an attachment
>   upload's created resource, a role activation, a SCIM create, the application's base path — was
>   written one byte per character, which turned a Japanese target into `/????` (the application
>   root, answering 200) and a Latin-1 one into a single raw byte that twelve of fourteen
>   followers, both browsers included, could not land — most requested `/caf%E9` and got a 404.
>   The target is now percent-encoded once, as its UTF-8 bytes, at the one place a framework URL
>   acquires its prefix; a target that is already encoded is left alone, and a placeholder value
>   is a path segment wherever it appears. The same seam encodes the shell's links, the transfer
>   status URLs and the OIDC cookie path — correctly, under the base-path rule that a URL is
>   encoded once, when it becomes a wire URL. A `Location` or `HX-Redirect` declared under
>   `response.*.headers:` is encoded the same way, so the documented `Location` on a 201 recipe
>   works for a non-ASCII key.
> - **The login bounce carries the original query string once.** An unauthenticated navigation to
>   `/page?q=1` was bounced to sign-in with `redirect=/page?q=1?q=1`, and the doubled value survived
>   sign-in into the post-login redirect of every application, hosted or not.
> - **A response header carrying a control character is refused as a 500 instead of hanging the
>   connection.** Only CR and LF were refused; every other C0 control and DEL reached the
>   transport, which refused them where nothing could answer — a buffered response held the
>   caller's connection open to their own timeout, and a streamed download went out as a 200 with
>   its `Content-Disposition` and `Content-Type` silently dropped. A tab stays accepted. A
>   `Location` or `HX-Redirect` that reaches the edge with a tab, a space or a character outside
>   ASCII is refused the same way, so a redirect that would have landed on the wrong page fails
>   loudly instead.

A new `### Security` section after `### Fixed`:

> - **A sign-in return target carrying a tab no longer sends the browser off-site.** The app-local
>   gate that guards the login `redirect`, a `location: back` return field, the OIDC return URL and
>   the SAML RelayState refused a protocol-relative or backslash target and a line break, but not a
>   tab. A browser deletes a tab from a URL before parsing it, so a crafted `/<tab>/host/page`
>   passed the gate and Chromium landed on the other host after sign-in. The gate now refuses every
>   control character, and every redirect target the framework writes is percent-encoded before the
>   wire.

### PR 4c — `CHANGELOG.md`, `### Added` and `### Changed` above `### Fixed`

> ### Added
>
> - **An `export:` CSV carries a byte-order mark when asked.** `bom: true` on a route's or a job
>   step's `export:` block opens a `csv` export with the UTF-8 mark (`EF BB BF`), so a spreadsheet
>   that sniffs the mark decodes the file as UTF-8 rather than in its system code page. The default
>   is unchanged, and an export that does not declare it is byte-identical to before: a mark is a
>   declaration a reader must expect, and PostgreSQL `COPY … HEADER MATCH`, Python's `csv` module
>   and Apache Commons CSV take it as part of the first header cell. A split export carries one
>   mark per ZIP entry, an export with no rows is the mark alone, and the mark is never derived
>   from a locale or a client. The linter refuses `bom:` on `excel` and `pdf` (`TQL-YAML-1005`).
>   Studio's data-browser download and `response.file:` templates are not `export:` blocks and
>   carry none. The procurement demo's shipments export declares it, since its partner names are
>   Japanese.
>
> ### Changed
>
> - **`FileWriteSpec` has a tenth component, `bom`, and `ExportSpec` a matching `Boolean bom`.**
>   A codec that constructs the core record passes it; one that only reads the record is
>   unaffected.

### Published pages (every sentence under the 60-word limit; no "slice")

- 4a — `docs/attachments.md:86-87`: "Otherwise the blob streams back with the stored content type
  and a sanitized `Content-Disposition` header. A name US-ASCII can spell is sent as
  `filename="…"`. Any other name is sent twice, as RFC 6266 asks: an ASCII `filename` fallback
  first, then `filename*` carrying the name itself, so a browser saves `請求書.pdf` under that
  name."
- 4a — `docs/file-transfers.md:72-73`, after "…`Content-Disposition` download filename.": "A
  filename outside US-ASCII is sent in RFC 6266's `filename*` form beside an ASCII `filename`
  fallback, so browsers save it under its own name and a client that reads only `filename` gets an
  ASCII one."
- 4a — `docs/unicode-identifiers.md:204` (site-excluded): "CSV/file export headers," → "CSV/file
  export headers (landed with `download-name-and-bytes.md`: `DownloadFilenameIntegrationTest` pins
  a Japanese, a Latin-1, an astral, a joiner-bearing and a control-bearing name through
  query-export, a split export, an uploaded attachment and the ops console's re-serve, as the exact
  RFC 6266 value),".
- 4b — `docs/unicode-identifiers.md:204`: inside 4a's parenthesis, before its closing bracket, "…
  as the exact RFC 6266 value; `RedirectLocationIntegrationTest` pins the redirect `Location` half
  the same way)". And `:211-214` open decision 1: append "**Settled.** The route matcher decodes
  (track 5); the literal tail of a `location:` was emitted raw until the redirect seam
  percent-encoded it (`download-name-and-bytes.md`, decisions 7-8)."
- 4b — `docs/response-shaping.md:288`, after the example: "A `Location` or `HX-Redirect` declared
  here is a URL: each placeholder is inserted as one path segment and the whole value is
  percent-encoded once before it is sent, the same way a `redirect:` target is. Any other declared
  header is emitted as written."
- 4b — `docs/hypermedia-ui.md:191`, after "… is emitted verbatim.": "A header value is refused with
  a 500 when it carries a control character other than a tab, and a `Location` or `HX-Redirect` is
  refused when it carries a tab, a space or a character outside ASCII — the framework's own
  redirects encode theirs."
- 4b — `docs/base-path.md:181`, after "… the framework's one redirect.": "That moment is also where
  the URL is percent-encoded: a non-ASCII path, a space or a control character leaves as UTF-8
  percent-triplets, and an already-encoded value is left as it is."
- 4c — `docs/file-transfers.md`, a bullet before the Excel bullet at `:127` (written **before** the
  reference regen, because the generator harvests the citing page): "`bom: true` opens a `csv`
  export with the UTF-8 byte-order mark (`EF BB BF`), so a spreadsheet that sniffs the mark decodes
  the file as UTF-8 instead of its system code page. It is off by default, because a mark is a
  declaration a reader must expect: PostgreSQL `COPY … HEADER MATCH` and Python's `csv` module take
  it as part of the first header cell. It is never derived from `locale:`; a split export marks
  every file in the bundle, and an export with no rows still carries it. The linter refuses it on
  `excel` and `pdf` (`TQL-YAML-1005`), which are not text streams."
- 4c — `docs/csv-import.md:17-19` gains "*Amended again: the writing side's mirror, `bom:`, is
  recorded under decision 10.*"; and before `:672` the paragraph of `design-4c.md` §5.3 (the
  asymmetry: a read refuses `import.encoding:` because the bytes say what they are; a write accepts
  an opt-in `bom:` because the bytes are what the consumer will sniff; neither side guesses).
- 4c — `docs/jobs.md:767`: "… a `download`-timed follow-up on a step, a workbook option on a pdf,
  or `bom:` on a workbook or a pdf"; `docs/printable-documents.md:147`: "… (`sheet:`,
  `startCell:`) or the csv-only `bom:`"; `docs/procurement-demo.md:197`: `file-export` →
  `query-export` (one word; the route is a `query-export`).
- 4c — the two regenerated pages `docs/reference-yaml-surface.md` (+2 rows) and
  `docs/reference-error-codes.md` (the 1005 row gains its third message and the
  `file-transfers` cookbook link) — **both committed**; a one-page regen is a red
  `GeneratedReferenceTest` in CI after a green local run without `tesseraql-docs-reference`.

### The audit record

`docs/audit-medium-leads.md`, lines on `0f25a51cf`. **PR S** carries the facts:

- `:29` F125 row: `| F125 Content-Disposition has no RFC 6266 | **LIVE (widens)** | medium |
  filename half: PR 4a; the Location half — a silent wrong page, and a post-sign-in return to the
  home page — is the more severe half and is PR 4b (docs/download-name-and-bytes.md); the headline
  reachable path is the end user's own uploaded filename via the shipped kind: attachment |`.
- `:46` F128 row: `| F128 CSV export has no BOM option | **LIVE (widens)** | \`dx\` as filed; not
  low — the procurement demo's shipments export hands a user Japanese CSV data mark-less, and
  \`query-export\` has no author-level workaround until F82 slice 2 | PR 4c: \`bom:\` on the
  \`export:\` block, default \`false\` |`.
- `:105-110` ("F125 is wider than its helper"): "`RouteEdge.headers()` (`:567-573`, the `add` at
  `:571`) funnels compiled-route responses only; eleven other write paths exist (assets, SSE,
  health, admission, the gateway, the MCP transport). Any char above U+00FF became `0x3f` in any
  header value and U+0080..U+00FF went out as its raw Latin-1 byte — a live, client- and
  UI-language-dependent break; the fix gate is `> U+007F` on both halves. 20
  `Location`/`HX-Redirect` write sites: one funnel (`BasePath.url`, nine writers) plus four raw
  prefixed writers (`AttachmentUploadProcessor:107`, `AuthStep:414` via `activatedLocation`,
  `ScimRoutes:116/170`), four ASCII-by-construction (`AuthStep.pickerLocation`, `OAuthRoutes:389`,
  `SamlAcsRoutes:216`, `IdempotencyProcessors:34`'s replay), one relay (`CopilotProxyRoutes:67`)
  and the gateway's `StackRelay:509` (the router slice's). `BasePath.encodeSegment` encodes a
  segment, not a path; the outbound mirror of `UnicodePaths` is the core `PercentEncoding.uriLiteral`
  (landed by 4a, used by 4b). A Japanese target lands on the application root with 200 in every
  client. The `?` at `ErrorResponseRenderer:278` is a deliberate delimiter, but the same method
  doubles the query string (4b). The edge's CR/LF throw is widened to every C0 control and DEL in
  4b; a tab stays accepted except in a `Location`/`HX-Redirect`."
- `:141-149` (slice 5's unfiled items): add "Slice 5 and 4c edit the same `CsvFileCodec.write`
  method (`:101` vs `:104-116`) and the same `docs/file-transfers.md:116-132` bullet list — the
  second to land rebases and regenerates the reference; no shared record component."
- `:171-172` "Defects surfaced" list, appended: 11 Studio's "Download CSV" was `Map.toString()`
  since #220 on all three branches, the shipped guard's three `contains()` green on it — **fixed in
  PR S (#1301)**; 12 the doubled login query (`ErrorResponseRenderer:252-257`) — PR 4b; 13 the
  C0/DEL edge gap (every C0 control but CR/LF and DEL hangs a buffered response and strips a
  streamed one; TAB passes), replacing HANDOFF #5 — PR 4b; 14 the tab open redirect through
  `BasePaths.isLocal` (security) — PR 4b; 15 the RFC 8288 `Link` header built from the decoded
  `request().uri()` (`PageHeaders:41-54`, `</???page=2>` on every paged list under a non-ASCII
  route path) — open, see the record; 16 `BasePaths.relative` on a wire-spelled `_return` under a
  non-ASCII base path doubles the prefix — router slice; 17 a declared `headers:` `Location` never
  acquires the base prefix — filed; 18 a non-ASCII `tesseraql.app.name` is hosted but unaddressable
  at the gateway (TQL-APP-4040) and `root.redirect` loops — router slice; 19 route shadowing by sort
  order (a Japanese literal segment beside `{param}` is unreachable) — router slice; 20 the
  documented `HX-Trigger` toast mangles non-ASCII — its own small pull request or the edge slice;
  21 ZIP entry names mangled by Info-ZIP `unzip` 6.00 (host byte) — export hygiene; 22 the
  32768-char xlsx cell trio — export hygiene; 23 `tesseraql lint` is silent on an unknown
  `export.format` — slice 5 / export hygiene; 24 a zero-row CSV export writes no header row —
  export hygiene.
- `:205-207` F128 severity: "Superseded 2026-09-12: the CP932 decode half was run (the JVM's
  `windows-31j` and glibc agree on every mapped character and on the first bad byte; the exact
  mojibake string is decoder-specific and is never asserted); the Excel step stays cited from
  Microsoft's current support page; the LibreOffice run was the headless no-options API path and
  is not user-facing evidence; a default-on mark is measured harmful to eleven reader families.
  `dx` stands on those grounds, and the fix is an opt-in `bom:` declaration."
- `:208`: "F82's `Location:`/`response.headers` sibling of F125 — never driven through a real
  route" → "F125's Location half — driven through real routes by five records and fourteen
  followers; PR 4b, not a slice-10 docs sweep".
- `:226` slice 4 row: `| 4 | A download keeps its name and its bytes | F125, F128 | S + M + M + S |
  Not one helper: a sixth emitter (response.*.headers:) and three default-name derivations. Four
  PRs (docs/download-name-and-bytes.md): S Studio's CSV was a Map on three branches — SHIPPED
  #1301; 4a conditional filename* with both halves, ASCII fallback first, a control/format fold,
  rider #4 zipName; 4b the Location half across core/pipeline/compiler/scim/runtime plus the
  wireHeaders backstop, the app-local gate refusing controls, the doubled login query; 4c bom: in
  both tesseraql-defs-v1.schema.json copies, a ten-component FileWriteSpec, ExportSpec, one helper
  on the existing TQL-YAML-1005 rule on both arms, two regenerated reference pages,
  ScaffoldDogfoodIntegrationTest in tesseraql-maven-plugin; Studio's CSV is unreachable by bom:. |`.
- `:227` slice 5: append "Shares `CsvFileCodec.write` and the `docs/file-transfers.md` bullet list
  with 4c; the second to land regenerates the reference. The lint/boot gap for `bom:` on
  `excel`/`pdf` is this slice's, stated in 4c as lint-only."
- `:232` slice 10: remove the F125 sibling; "F82 slice 2 is the `RouteCompiler:1388`/`ViewBinding:219`
  TCCL codec discovery (`tesseraql dev` refuses `format: excel` or an app codec on a `query-export`
  with TQL-LD-2801 while serving them on file-export; lint green)".
- `:244-247` decision 3: record the user's 2026-09-09 answer ("its own campaign") and the lines
  above, so it is not re-filed a third time.

**PR 4a**: `:29` append "— 4a SHIPPED #nnnn"; `:226` mark 4a SHIPPED. **PR 4b**: `:29` append
"— 4b SHIPPED #nnnn, with the tab open redirect it uncovered"; `:226` mark 4b SHIPPED; rows 12-14
of the surfaced list → "fixed in PR 4b (#nnnn)". **PR 4c**: `:46` → "SHIPPED #nnnn"; `:226` mark
4c SHIPPED.

## What this breaks

- A consumer that reads only `filename=` and repaired the raw Latin-1 byte (httpie, Python,
  `java.net.http`, Vert.x) now sees `cafe.csv` where it saw `café.csv` (U4; the CHANGELOG records
  it). Every non-ASCII declared download name changes — that is the fix. A TAB, any other C0,
  DEL, C1, a format character, `"`, `\`, a blank, `.` or `..` in a declared name now folds on
  every emitter. `attachment(null)` throws (no caller).
- Every split bundle's name changes (`<stem>-.zip` → `<stem>.zip`; `{key}.csv` → `export.zip`).
- A hand-written framework route, a future writer, or a `headers:` value that reaches the edge
  with a control character, or a `Location`/`HX-Redirect` with a tab, a space or a non-ASCII
  character, is a 500 where it was mangled or hung. A quoted `location: "/foo "` lands on
  `/foo%20` (a 404) where every client trimmed it. An authored non-ASCII host is percent-encoded,
  not IDNA-converted (WHATWG and curl follow it; the JDK client refuses it). `IdempotencyProcessors`
  replaying a `Location` stored before the deploy answers one 500 per stale key (keys expire). A
  registered OAuth `redirect_uri` with a raw non-ASCII value is a misconfiguration the backstop now
  reports as a 500 where it shipped `?`.
- The twelve non-Location outputs of `BasePath.url` change bytes for non-ASCII content only; the
  login bounce resolves dot segments and collapses `//` (`/a/../b?x` returns to `/b?x`).
- `FileWriteSpec`'s canonical constructor gains a tenth parameter and `ExportSpec`'s a
  fourteenth — source- and binary-visible to an out-of-tree codec that constructs the record (none
  in the tree outside one test). `bom:` on `excel`/`pdf` turns from an unknown-key warning into a
  refusal (`tesseraql lint` exit 1); no shipped YAML declares it.
- Studio's disabled/error notes are now a pinned contract rather than an accident.

## Filed, not fixed

Every unfiled defect the measurement surfaced, with its destination (the measurement record's §6):

- **Router slice** ("Unicode names at the router", after 4b): a non-ASCII `tesseraql.app.name` is
  hosted but unaddressable at the gateway and `root.redirect` loops (#12); route shadowing by sort
  order (#13); `StackRelay:509`'s raw header write; `BasePaths.relative` on a wire-spelled
  `_return` under a non-ASCII base path (the doubled prefix, HEAD and 4b alike — the fix is the
  inbound decode before the compare, beside `CookiePath.bind`); the `Set-Cookie Path=` on a
  non-ASCII base path (#2, unreachable from `dev`/`host`, file-only); reconciling
  `unicode-identifiers.md:160` with `ApplicationName.java:59` and `base-path-emission.md`.
- **Edge slice** ("A header value is refused before it can hang the connection"): the asset and
  SSE bypass checks (`AssetRoutes:343`, `SseRoutes:145`) and the MCP `HttpTransport:68` (#17, #23);
  a literal-value lint at `ExportRules:189`'s seam for `export.filename`, `response.stream.filename`,
  `redirect.location` and `response.file.contentType` (#7 — `charset=Shift_JIS` over UTF-8 bytes;
  an authored `location:` with OWS); the `headers:` `Content-Disposition` injection and F125
  mangling (#15 — the helper does not run there); the `HX-Trigger` toast escape (#16 — one flag on
  a dedicated mapper, its own small pull request or here); an IDN host in an absolute `location:`
  (`java.net.IDN` is JDK and could live in core when a shipped path authors one); `[`/`]` in a path
  and the JDK follower; the declared `headers:` `Location` never acquiring the base prefix.
- **Export-hygiene slice**: the ZIP host byte (#18 — `SplitExport` is in dependency-free core; a
  hand-written central-directory platform byte or moving the writer is a design decision); the
  surrogate split in `SplitExport.safe()` and the masked TQL-LD-2857 (#3); `FileCodecs.discover`'s
  last-put-wins, under which a module codec named `csv` silently replaces the built-in and `bom:
  true` reaches a codec that may ignore it (#6); the 32768-char xlsx cell trio (#20); the unknown-
  format lint silence (#21, also the lint's case-sensitivity on `format:`); the zero-row export
  with no header row (#24 — a marked empty export is exactly the mark until then); the two-part
  extension in `zipName` (`orders-{key}.tar.gz`); the `defs-v1.schema.json:97` description split
  (#19).
- **Slice 5**: the lint/boot gap — `bom:` on `excel`/`pdf` is refused at lint only; an app that
  skips `tesseraql lint` writes an unmarked workbook.
- **Rides in 4b** (settled 2026-09-12): the RFC 8288 `Link` header (`PageHeaders:41-54`) is F125
  outside the seam — `</???page=2>; rel="next"` on every paged list under a non-ASCII route path;
  `uriLiteral(target)` on its two lines and one wire row.
- **File-only**: `security.responseHeaders` absent on query/file-export downloads (documented
  deliberate at `docs/route-defaults.md:99-100`, #8); `ErrorIndex` indexing a literal code in a
  comment (#9 — a slice-4 trap: refer to codes by constant name in `src/main`); the two BOM
  sniffers (#10); the mail leg's JVM-default RFC 2231 charset (#22); `%`+HEXDIG in an ASCII
  `filename=` (RFC 6266 Appendix D's advice would move the ASCII wire); U+034F and the Hangul
  fillers (`Default_Ignorable` but neither `Cc` nor `Cf`, and Java has no API for the property);
  Jamo-spelled Hangul's three underscores per syllable; the `%3F` lone-surrogate spelling in
  `KeyedUrls.encode` and `BasePath.encodeSegment` (unreachable); `HX-Location` (nothing emits it);
  Studio's `listTables` being schema-blind on PostgreSQL and its `IllegalStateException` note
  carrying the driver's multi-line message (both pre-existing, surfaced by S's attack).
- **Already its own campaign**: query-export/import-view codecs discovered on the TCCL
  (`RouteCompiler:1388`, `ViewBinding:219`) — F82 slice 2, decision 3 of the audit record.

## Traps and guard hazards this design added to the measured twenty-four

- **N5** — a control fixture bundling two classes (C0 + DEL) is green on a fast-path/loop
  disagreement; pin DEL alone.
- **N6** — a harness "control" row absent from the shipped file is not a guard.
- **N7** — a name-keyed check must be pinned with the non-canonical spelling (`location`,
  `hx-redirect`); one shared predicate closes the compiler/edge disagreement by construction.
- **N8** — a `{…}` in a YAML `location:` literal is a placeholder: a fixture for the eight non-URI
  graphics must not use braces (a round of 4b's bracket was red on FIX for that alone).
- **N9** — a variant that removes the encoder's TAB rule is green on every wire row because the
  gate and the backstop each refuse a tab independently; the encoder's rule needs its own core row.
- **N10** — the exact `.`/`..` rule on the sanitiser's *input* does not protect the fallback's
  *output*: `..<U+0301>` re-creates `filename=".."` unless the rule is applied again after the
  marks are dropped. Every angle design of 4a inherited this.
- **N11** — a mark written after the row loop, at the end, or per row is green on every
  `startsWith`/`copyOf(bytes, 3)` guard under the writer's 8 KiB buffer; only whole-body equality
  over a fixture wider than the buffer is red. Every angle design of 4c inherited this.
- **N12** — a `bom: false` fixture that reads bytes, and a `locale: ja` fixture that reads bytes,
  are the only witnesses of the declined half and of "never derived"; no angle design had either.
- **N13** — a Studio guard with no cell that needs quoting and no non-ASCII cell is green on the
  template's escaped mode; a guard whose only error fixture throws `IllegalArgumentException` is
  green on a narrowed `catch`.
- **N14** — the `.vscode` schema copy is regenerated by `ScaffoldDogfoodIntegrationTest` **in the
  reactor** (`-am`); without it the command reverts the copy to the stale `.m2` schema with a green
  test. The reference regen rewrites two pages, and the docs column depends on the citing page
  existing first.
- **N15** — inserting a test method between an existing javadoc and its method fails doclint
  `-Werror` ("documentation comment is not attached to any declaration"); anchor above the javadoc.
- **N16** — a surefire `.txt` from a failed compile fakes a pass; read the run's own `Tests run:`
  line or the XML. `-Dtest='A+B'` selects nothing; use commas.
- **N17** — two encoders with the same allow-list disagreed on three rules (a lone `%`, `null`, the
  same instance); the disagreement was named by running both test files against both classes,
  not by reading.

## Recorded deviations

- From D-L (b)'s letter ("a byte ≥ 0x80"): the backstop refuses a tab and a space in a URI header
  too (decision 13; settled 2026-09-12, kept).
- From the measurement record's §4.1 wording ("leave every ASCII byte alone"): the eight non-URI
  graphics and a lone `%` are encoded (decision 7).
- From U3's guard text (`startsWith("login_id,")`): the first cell is `user_id` (PR S).
- From the measurement record's "two Map-wrapping branches": three (PR S).
- From the measurement record's "two bypass writers": four (decision 10).
- From the measurement record's §4.1 4b shape ("the 4a IT gains redirect routes"): a separate
  `RedirectLocationIntegrationTest` with three boots; `-Dtest=RedirectLocationIntegrationTest` is
  the bracket's rerun.
- From the measurement record's §4.1 4a harness ("through `examples/inventory-app`"): the IT reads the shipped
  `inventory-app/attachments/reports.yml` into the user-admin copy and fails loudly if it drifts;
  the processor is one class whatever app declares `kind: attachment`, and the gallery app needs
  DuckDB plus an extension bundle to boot.

## Settled after the design was written

Two questions the design left to the user were answered on 2026-09-12, before any of the four
pull requests was built:

1. **The RFC 8288 `Link` header rides in 4b.** `PageHeaders:41-49` builds `</???page=2>;
   rel="next"` on every paged list under a non-ASCII route path — F125 outside the seam, red on
   HEAD and on 4b's fix as first designed. It was not among D2's enumerated ride-alongs, so the
   record had filed it. The user chose to ride it: `uriLiteral(target)` on its two lines plus one
   wire row (`theNextLinkOfAJapaneseRouteIsPercentEncoded`, red today), the same encoder and the
   same family of seam. 4b's CHANGELOG may therefore say "every framework-built URL", not only
   "every redirect".
2. **The backstop keeps refusing a tab and a space in a URI header**, beyond D-L (b)'s letter
   ("a byte ≥ 0x80"). The tab is the measured open redirect (Chromium 152 lands off-origin on a
   raw-tab `Location`); the space is what every client silently trims. Neither adds a 500 to any
   shipped path: the encoder removes both from every framework value, and a declared String
   `Location` is encoded before the edge. Recorded as the deviation it is, under decision 13.

## Read the decisions, not the plan

`REMEDIATION-PLAN.md` and the slice-4 row of [`audit-medium-leads.md`](audit-medium-leads.md) as
first written are stale on everything this record's status header lists. The measurement record
this design was built from (`work/slice4-attack/MEASUREMENT.md` — `work/` is not committed; the
record is standalone and the user holds it) overturned
forty-one of its own predecessors' claims and lists them in its §1.8; nothing here is built on
one of those. Where a decision above cites a variant name, that variant was built as one edit of
the fix's own source and run in the same stamped bracket as the fix — the matrix in the pull
request is the evidence, and a green guard that was never red on a built defect is not one.
