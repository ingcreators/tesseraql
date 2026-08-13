# Silent tolerance sweep

> **Status: design.** A four-part audit (2026-08-09) swept the load/compile path,
> the HTTP+security runtime, the batch/ops surfaces, and the authoring tooling for
> *silent tolerance* — places where invalid, unknown, or failing input is swallowed,
> defaulted, or dropped instead of surfaced. The sweep was prompted by three such bugs
> the view-composition and contract-cleanup campaigns had just fixed (view documents
> ignoring unknown keys → `TQL-VIEW-3314`; `readOnlyBehavior: ignore` binding the value
> it claimed to drop; a missing `domains/` tree dropping every `domain:` reference).
> Every finding below was independently re-verified against the code, its callers, and
> its tests; the ones that turned out to be documented, deliberate degradation are
> recorded in [Out of scope](#out-of-scope) with the evidence that cleared them.

## Why now

Two of the three seed bugs were about to be frozen into the 1.0 compatibility contract
(roadmap Phase 34). The same is true of most of what the sweep found: an authorization
arm that fails open on a typo, a schema that accepts a misspelled security block, a
renamed key that vanishes without a word — these are exactly the things a compatibility
contract exists to pin, and each is cheap today and a contract break after the freeze.
The observability and tooling findings are not contract-shaped, but they share the same
defect signature (a failure with no log, no metric, no status field, no diagnostic) and
the same fix vocabulary (fail loud, or make the failure observable), so they ride the
same campaign.

The unifying principle, stated once so every track can point at it: **the framework
already knows how to fail loud — it just does it inconsistently.** `ViewSpec.rejectUnknown`
names a stable code and the accepted vocabulary; `FileScopes` throws on every undeclared
scope; `OutboxDispatcher` logs, records a status, and raises an alert on every
dead-letter; `ManifestLoader.loadConfig` fails fast on a typo'd env profile. Each track
below is a place where a sibling code path does the opposite, and the remedy is to make
it match the loud sibling — not to invent a new mechanism.

---

## Wave S — security fail-open (bugs, one-shot pre-1.0)

Every item is a security control that a typo, an omitted key, or an unusual-but-valid
value silently disables. All are bugs against the framework's own docs or its own
sibling path; none warrants a dual-emit window.

### S1 — a scope `when:` typo turns a restricting arm into match-everyone

`CompiledScopeResolver.rule(WhenCondition)` returns `null` when `role`/`permission`/`claim`
are all null, and `CompiledArm.matches()` treats a null rule as *unconditional — matches
every principal, including an anonymous (`null`) one*. `WhenCondition` is
`@JsonIgnoreProperties(ignoreUnknown = true)`, so `when: {roles: [admin]}` (plural typo),
`when: {rol: admin}`, or any unrecognized predicate deserializes to `(null,null,null,null)`.
The only validation, `AppLinter.lintWhen`, checks `set > 1` — never `set == 0`. Scope arms
compose *additively* and a matching `apply: all` arm short-circuits to `(1=1)`, so the
typo does not narrow visibility — it grants **full-table access to everyone, anonymous
included**. This is the seed `domains/`-drop bug on the authorization path.

**Fix**: a present-but-empty `when:` (zero recognized predicates) is a hard error, distinct
from an absent `when:` (the legitimate catch-all arm). Carry the "present but empty" signal
from parse (`MatchArm.when()` is nullable-by-design and loses it today), and add an
`AppLinter` finding for `set == 0` and for unknown keys inside `when:`.

### S2 — per-tenant batch silently falls back to the shared pool

`TenantDataSources.dataSourceFor(tenantId, fallback)` returns the `main` pool when a tenant
has no configured pool — contradicting the class's own javadoc and its sibling `resolve()`,
which throw `TQL-TENANT-4031` "rather than silently falling back to a shared pool,
preventing cross-tenant data exposure". Both call sites are the per-tenant batch fan-out
(`TesseraqlRuntime` scheduled/triggered jobs and `runJobForAllTenants`). In
`database-per-tenant` mode `TenantRegistry.tenantIds` draws from `tenancy.tenants` /
`tenancy.registry.sql` *before* the pool keys, so a tenant present in the registry but
absent from `tenancy.datasources` runs its job against **main**, stamped with that tenant's
`TenantContext` — reading and writing another tenant's rows, with no tenant predicate
because per-tenant isolation is structural.

**Fix**: make the batch fan-out call the throwing `resolve()` whenever isolation mode is
per-tenant. The fallback is *load-bearing for shared-schema* fan-out (documented: shared
pool scoped by the `tenant.id` bind), so the fix is mode-conditional — and
`TenantDataSources` must retain the mode it currently discards after `load()`.

### S3 — JWT `exp` enforced only when it is a JSON number

`JwtAuthenticator.validateClaims` checks `exp`/`nbf` only `if (claims.get("exp") instanceof
Number)`. A token with **no** `exp`, or with `exp` as a JSON *string* (`"exp":
"1700000000"` — emitted by several IdPs), skips the expiry check entirely and is accepted
forever. Bearer auth is stateless, so no session TTL backstops it.

**Fix**, split in two by breaking-ness: **(a) non-breaking** — coerce a numeric-string `exp`/`nbf`
and validate it (a leaked token that carries a string expiry stops being immortal); **(b)
breaking, config-gated** — a `requireExp` flag (rejecting a token with no `exp`) defaulting
to the safe value, since making `exp` mandatory unconditionally breaks machine-to-machine
issuers and every fixture token. Ship (a) now; (b) is a defaults decision recorded for the
Phase-34 freeze.

### S4 — route-local `csrf:` is unvalidated *and* defaults to off

Two defects in `SecuritySpec`, one worse than the audit's original framing:

1. **Value asymmetry (the reported one).** Config-side `csrf` outside `auto|required|off`
   is rejected with `TQL-SEC-4132`; the identical route-local value gets no check — anything
   that is not `off`/`required` falls through to the `auto` branch. `csrf: requred` (typo)
   on an `auth: bearer` route silently resolves to no enforcement. `AppLinter` has zero
   `csrf` coverage; the schema enum is editor-only.
2. **`csrf == null` returns false (found while verifying S4).** `SecuritySpec` reads an
   *absent* `csrf:` as **off**, contradicting its own javadoc, which calls `auto` the
   default. A `auth: browser` route with no `csrf:` key, in an app with no matching
   `security.defaults` rule, gets **no CSRF validator wired**. The bundled apps are safe
   only because their config supplies a `csrf: auto` defaults rule; user apps inherit the
   unsafe reading.

**Fix**: validate the route-local `csrf:` value in the linter/parser with `TQL-SEC-4132`
(not at `csrfEnforced`, which is legitimately called on null-csrf specs), and correct the
absent-⇒-`auto` default. The default correction is a genuine behavior change for any app
relying on the current silence — a pre-1.0 break, called out as such.

### S5 — `inputPolicy.unknownFields` value typo disables the mass-assignment guard

`InputPolicy.rejectsUnknownFields()` is `unknownFields == null || "reject".equals(unknownFields)`.
The default (absent key) is correctly fail-*closed* — the audit's "fail-open default"
framing was wrong, and is corrected here. But any value other than the exact lowercase
`"reject"` (`"Reject"`, `"rejct"`, `"deny"`) yields `false` and admits every undeclared body
field at `RequestBinder.guardMassAssignment`. Nothing validates the value: no lint mentions
`inputPolicy`, and the schema types it `additionalProperties: true` with no enum (contrast
the sibling `readOnlyBehavior`, whose `switch` sends any unrecognized value to `default ->
throw`).

**Fix**: schema enum + an `AppLinter` value-vocabulary check. Must preserve the legitimate
`ignore` opt-out the bundled ops-console route relies on.

### S6 — security-relevant booleans accept only the literal `true`

`AppConfig` stringifies YAML scalars and the runtime reads security switches via
`.map(Boolean::parseBoolean)`, which returns **false** for everything that is not
case-insensitively `"true"` — so `yes`, `on`, `1`, `y` all silently evaluate to false. The
bug bites every flag whose default is `true`: a YAML-1.1-idiomatic `yes` *disables* the
protection. Confirmed security-relevant, default-true sites: `studio.readOnly` (→ Studio
becomes writable), `security.credentialThrottle.enabled` (→ brute-force throttle off),
`tenancy.required` (→ deny-by-default defeated), `saml.requireSignedLogout` (→ unsigned
logout accepted), DuckDB object-store `useSsl` (→ plaintext). ~40 call sites total across
nine modules; the fail-*closed* ones (default-false) are listed in the design appendix but
are not the fix target.

**Fix**: one strict `AppConfig.boolean(key, default)` helper that accepts
`true/false/yes/no/on/off/1/0` (case-insensitive) and throws `TQL-YAML-11xx` on anything
else; migrate the security-relevant sites first, the rest mechanically. Accepting the extra
truthy spellings (rather than rejecting them) is the better fix for `${env.X}`-interpolated
values, where `ENABLED=1` is idiomatic.

---

## Wave K — silent tolerance on the load path (strictness + lints)

The load path accepts unknown keys, renamed keys, misnamed files, and no-op assertions
without a word. The remedy is uniformly the `ViewSpec` pattern — a tree-walking lint over
the already-loaded document families that names a stable code and the accepted vocabulary,
run as a **lint** (reportable, position-carrying, promotable to error at the freeze) rather
than a Jackson strict-parse.

**Why a lint and not `FAIL_ON_UNKNOWN_PROPERTIES`.** Removing the 44
`@JsonIgnoreProperties(ignoreUnknown = true)` annotations is not viable: (1) `loadMcp`
parses every `mcp/**` document through `RouteDefinition` and then reads `description`/`uri`/
`mimeType`/`ui` *from the raw tree* — `RouteDefinition` has no such components, so strict
parse breaks 100% of MCP documents; (2) only `web/` has tolerant-load recovery, so one stray
key anywhere else would fail whole-app startup *and* fail the linter that should report it,
with a Jackson byte-offset message instead of a TQL code; (3) the forward-compat promise
("newer keys never break older editors") is written into the schema `$comment` and the
backlog. A tree-walking lint delivers strictness with a stable code, a vocabulary-naming
message, position reporting, and severity control — none of which Jackson strict offers.
Deriving the key vocabulary reflectively from the record components (rather than
hand-maintained sets like `ViewSpec`'s) keeps it from drifting from the model.

### K1 — unknown keys accepted across every non-view document family

44 model records carry `ignoreUnknown = true`; no lint checks unknown keys for routes,
jobs, workflows, scopes, MCP, attachments, or consumers. A typo'd `securty:` block on a
route drops auth silently — `RouteCompiler.applySecurity` returns early on null security and
wires no authenticate/authorize/csrf step. (Path-matched `security.defaults` backfill
mitigates this *specific* case for apps that configure it; the default configuration does
not.)

**Fix**: `lintUnknownKeys` — a tree-walk over `web/**`, `batch/**`, `mcp/**`, `workflow/**`,
`scope/**`, `attachments/**`, `consume/**`, comparing each document's keys against
reflected model vocabulary. New code **`TQL-YAML-1043`**, severity `warning` initially,
promotable to `error` at the v1 freeze.

### K2 — renamed pre-1.0 keys vanish with no diagnostic

The eleven contract-cleanup renames (`page:`→`pagination:`, job `params:`→`input:`, root
`policy:`→`admission:`, `http-call:`→`httpCall:`, `expect.rows`→`rowCount`, poll `source:`/
push `target:`→`transport:`, column `header:`→`label:`, workflow `notify:`→`reminders:`,
decision `out:`→`outputs:`/`id:`→`keyColumn:`, view `view:`→`recipe:`, panel `kind:`→
`chart:`) produce **no** diagnostic today (the view family is the sole exception — its
`recipe`/`chart` renames are already caught by `TQL-VIEW-3314`). An app upgrading from 0.13
loses each block silently. The decision `source.id:` case is actively dangerous:
`effectiveKeyColumn()` defaults to `"id"`, so the dropped key is *masked* by a default that
joins the wrong column.

**Fix**: `lintRemovedKeys` — a fixed old→new map, new code **`TQL-YAML-1044`**, severity
`error` from the start (an old spelling is never a forward-compat newer key), with a message
that names the replacement (`"page: was renamed to pagination: before v1"`). The
`source.id:` case additionally gets `TQL-DECISION-4718` because of the dangerous default.

### K3 — misnamed / misfiled documents are silently skipped

`web/` loads only exact-HTTP-method stems; every other tree loads any `*.yml` but nothing
loads a `.yaml`, and `domains/`/`rules/`/`decisions/`/`calendars/` are non-recursive
(`Files.list`) so a `domains/hr/fields.yml` is invisible while `batch/hr/params.yml` is found.
No lint reports an unclaimed YAML file. `post.yaml`, `Get.yml`, `psot.yml`, `domains/x/y.yml`
— the document simply does not exist, and the author gets a 404 with nothing pointing at the
filename.

**Fix**: `lintUnclaimedFiles` — diff `ManifestLoader.buildIndex` (which already walks and
hashes every file, so no new I/O) against the union of loaded `source()` paths, reporting
any `*.y{a,}ml` under a known tree that no loader claimed. New code **`TQL-APP-4205`** (the
`APP-42xx` app-tree-layout band). The single highest-value, zero-false-positive catch is a
`.yaml` extension in a `.yml`-only tree.

### K4 — the residual load-path items

Verified individually; grouped because each is a small, self-contained fix:

- **`constraints:` bypasses `domains:` strictness** — `SimpleYamlParser` rejects unknown
  keys inside `domains:` (`TQL-FIELD-4602`) but runs `constraints:` through an
  `ignoreUnknown` mapper; a `feild:` typo drops the field mapping. Apply the same key check.
- **Unknown mcp `kind:` becomes a tool** — the loader's `else` branch turns `kind: resourse`
  into a callable tool exposed to the model. Enumerate the legal kinds; new code
  **`TQL-MCP-1003`**.
- **`lintI18n` early-returns when `messages/` is absent** — which also skips the
  message-key *reference* check, so a rule declaring `message: order.qty.tooLarge` with no
  catalog gets zero findings (same empty-tree-skip shape as the seed bug). Hoist the
  reference loop above the early return.
- **`TQL-SQL-2103` file-existence misses `queries.*.file`** — checked for `sql.file` and
  `steps.*.file` but not `queries.*.file`; the bind analyses that walk `queries` `continue`
  past a missing file. A typo'd query file produces no diagnostic until request time. Extend
  the check.
- **SQL-injection lint runs on routes only** — `lintEmbeddedVariables` (the guard requiring
  embedded `/*# {x} */` placeholders to be `enum`-constrained) is called only from
  `lintRoute`, not `lintTool` or `lintConsumer` — the two surfaces whose SQL is driven by
  LLM arguments and message payloads, i.e. the *highest*-risk injection surfaces. Extract a
  shared `lintSqlDocument` (also covering optimistic-locking, tenant-predicate, and negative-
  timeout checks) and call it from all three.
- **Negative `timeoutSeconds` clamps to unlimited** — `Math.max(0, …)` turns `timeoutSeconds:
  -1` into `0` = no timeout, the inverse of the author's intent; the lint checks negatives
  only on `definition.sql()`, not `steps`/`queries`/tools/consumers/the config key. Reject
  negatives (`TQL-YAML-1021`) instead of clamping.
- **Unterminated `${…}` emitted literally** — `AppConfig` appends an unterminated placeholder
  verbatim instead of throwing; every other resolution failure raises `TQL-YAML-1101`. One-line
  `throw`, reuse `1101`.
- **`menu.yml` / `flags.yml` wrong-shaped root → EMPTY** — a `menu:` authored as a map (or
  `flags:` as a list) yields silent-empty navigation / all-flags-off, with no lint. Error on
  a present-but-wrong-shaped root; lint menu item-key vocabulary.
- **`instanceof` value coercion** — `UiSpec` (`prefersBorder: "true"`, wrongly-nested `csp:`)
  and even the otherwise-strict `ViewSpec` (`sortable: "yes"` passes `rejectUnknown` but
  coerces to null) drop wrong-typed values silently. `ViewSpec` validates *keys* but not
  *values* — a gap in the `TQL-VIEW-3314` promise. Reject wrong-typed scalars.
- **Suite `Expectation` no-op case** — `Expectation` is `ignoreUnknown` + all-optional, so
  `expect: { rowcount: 3 }` (typo) asserts nothing and passes green while counting as
  covered. The precise, false-positive-free rule: flag an `expect:` block that is *present*
  but yields zero assertions (omitting `expect:` entirely is the supported "just runs"
  idiom and must stay legal). New code **`TQL-YAML-1403`**.
- **Documentation drift** — `ColumnSpec` javadoc still teaches the removed `header:` key;
  `DecisionsDocument` `@param id` names the removed component. Pure-doc fixes, direct fallout
  from the rename sweep not touching javadoc.

---

## Wave O — operational failures made observable

These are not contract-shaped: they are failures — dropped messages, half-imported files,
leaked blobs, fail-open jobs — that reach an operator with no log, no metric, no status
field, and no alert. The fix vocabulary is the `OutboxDispatcher` triad: **log at
warn+, record a status, raise an alert.**

### O1 — queue dead-lettering is completely unobservable

`QueueConsumer.deliver()` catches every failure and calls `store.markFailed`, which flips a
`tql_event` row to `DEAD` at `maxAttempts` — but the class has **no logger and no meter**,
nothing reads `DEAD` `tql_event` rows (the ops console and its alert cover only the *outbox*
table), and `EventChannelStore` has no `recent`/`countByStatus` at the interface level. A
consumer that throws on every message silently discards the entire inbound stream; the
operator learns from the business. (`EventChannelStore`'s own javadoc promises dead-letters
"stay visible to operators" — nothing makes them visible.)

**Fix**: mirror the outbox pattern end to end — `recent`/`countByStatus`/`redeliver` on the
store, `/_tesseraql/ops/events` read route + console page, a dashboard counter raising new
code **`TQL-OPS-9008`**, and a logger/meter in the consumer. `tql_event` already carries
`app_name`, so no migration is needed.

### O2 — an all-rejected file import reports success and archives as done

With `onError: skip`, a polled file whose rows all fail is marked `COMPLETED`, archived to
`move:` (not `moveFailed:`), and resets the consecutive-failure streak that would have raised
`TQL-OPS-9007`. The `COMPLETED`-on-skip status is the *documented HTTP contract* and is
test-pinned, so the fix belongs in the **poll path**, not `runImport`: `awaitImport` already
holds the `TransferStatus` (which already carries `errors`), so checking `!errors.isEmpty()`
(or rows-applied == 0) routes the file to `moveFailed:` and fires `status.failed(...)`. No
schema change. Two adjacent fixes ride along: the rejected-row count is capped at 100 with
the true count never persisted (add a `rejected_count` column via a new per-vendor V2
`alter table`), and `TransferSummary` has no `errors` field so the console renders a fully
rejected import as `COMPLETED · 12 rows` (add a `rejected` field to the record, the query,
and the page).

### O3 — retention deletes attachment metadata before the blob, then swallows the blob failure

`JdbcAttachmentStore.deleteOlderThan` deletes the metadata row, returns the storage key, and
`RetentionSweeper` then deletes the blob inside `catch (RuntimeException ignored)` — so a
failed blob delete is an **unrecoverable orphan** (the key exists nowhere else) and the sweep
still reports "removed N attachment(s)". Reordering to blob-then-metadata needs a new store
method (`listOlderThan` / `deleteByIds`), which the interface does not expose. The minimal
non-breaking fix keeps the `int` return but logs and counts the swallowed failures at WARN;
the reorder is a larger follow-up.

### O4 — an unmatched CSV/Excel header yields a full file of nulls

`Tables.positions()` returns `-1` for a header label absent from the file, and the codecs map
`-1` to `null` for every row — a supplier renaming `Order No` → `OrderNo` imports the full
row count with a silently null column. "Column intentionally absent" is **not** a documented
feature (no `optional:`/`required:` flag exists), so failing a declared-but-unmatched header
is safe. Apply only to explicitly declared columns (derived-from-header columns always match
by construction).

### O5 — a holiday-source read failure runs the job on a filtered day

`CalendarDecisions.resolve()` returns `null` on any exception → `decide()` returns
`Decision.RUNS` (fail-open) with a WARN and nothing else — no execution marker, no alert; a
settlement job runs on a bank holiday and looks like a normal `COMPLETED` run. `TQL-BATCH-4201`
already catches *unknown calendar names* at build, so the residual live risks are precisely
the DB read failure and the CLI `JobCommand` unknown-name path (which prints nothing, unlike
its own read-failure path). Record the fail-open on the execution and raise an ops alert.

### O6 — an `http:` source degrade / `select:` miss is silent

`HttpSourceProcessor` has no logger and no meter: an `onError: empty` degrade stores
`<name>.error` in the context only (visible only if a template renders it), and a `select:`
path typo returns null → zero rows, indistinguishable from a genuinely empty upstream. Add a
logger and a `tesseraql.httpsource.degraded` counter (the project's own `Meter` SPI, looked
up exchange-side as `RouteTelemetry` does); rate-limit or DEBUG the select-miss line since it
fires per request.

### O7 — dedup/claim recorded before the alert is sent

`AlertNotifyRouteBuilder` does `notified.add(code)` before `store.insert` — an insert failure
leaves the code marked, so the alert is never re-sent while it stays raised. `JobSlaSweeper`
does `tryClaimFiring` before `sink.alert` — a failed alert burns the cluster-wide claim, so
that business date's SLA miss never alerts again. Fix: side-effect first, record the dedup
key/claim only on success (for the SLA sweeper, release the claim on failure or claim in the
alert's transaction).

### O8 — a malformed batch-run body is swallowed and the job runs parameterless

`OperationsRouteBuilder.parseBody` returns `Map.of()` for an unparseable body identically to a
legitimately empty one, then answers `202 Accepted` — an operator's manual rerun with a
typo'd `businessDate` runs against the default scope and looks accepted. Throw new code
**`TQL-BATCH-4043`** (→ 400) for an unparseable *non-empty* body; keep `Map.of()` for the
genuinely empty one. The same `catch → Map.of()` shape recurs in
`JdbcFileTransferService.fromJsonParams`/`fromJsonErrors` (corrupt transfer JSON → after-SQL
runs with NULL binds, a wrong-scope update; the *write* side already throws `TRANSFER_ERROR`,
so the read side should too) and in `TesseraqlRuntime.parseJsonObject` (a malformed recorded
test body becomes a green test asserting nothing).

### O9 — coverage / regression gates pass silently on a corrupt file

Three gates turn into no-ops on corruption: `ReportHistory.read` returns an empty list on
`IOException` → the coverage-regression gate passes unconditionally (and the corrupt file is
then overwritten); `AppTestRunner.loadManifest` returns null on any `RuntimeException` → **24**
`ManifestCoverage` kinds silently drop out of the report; `CoverageMojo.loadConfig` returns
null → the resolver falls to the mojo's default thresholds (which default to **0%**) *and*
drops every per-kind threshold, so an unreadable config reports "coverage gate: passed". The
remedy must separate **missing** (first run, legitimately fine) from **corrupt/unreadable**
(warn or fail) — `ReportHistoryTest.recoversFromACorruptHistoryByStartingFresh` pins the
current conflation and must be rewritten. The `DocService.schemaCorrupt()` predicate is the
pattern.

### O10 — the smaller confirmed items

Each verified, each a self-contained fix: `AttachmentScanSweeper` swallows a failed blob
delete while recording the verdict `infected` (the malware stays in the bucket);
`LiveEvents` silently drops undeclared `?topics=` values (a typo'd topic opens a healthy-
looking stream that never fires — reject with 4xx); `datasourceValid` discards the exception
so a health `DOWN` carries no cause; `IamAdminRouteBuilder` bulk-disable ignores update counts
and reports the requested size (a stale id "succeeds"); SCIM `listGroups` silently ignores the
`filter` param (return 400 `invalidFilter`, asymmetric with Users) and `count`/`startIndex`
default silently on non-numeric input with no upper clamp; `AssetsRouteBuilder` caches the
ETag per path forever with no invalidation, so a changed asset serves a stale `304`
indefinitely (key on mtime+size); `SecurityConfigFactory.parseRule` drops an unrecognized
rule shape → an all-misspelled policy becomes silent deny-all with no diagnostic; `parseIndex`
returns `-1` on a bad menu index and the handler reports success doing nothing.

---

## Wave T — authoring tooling that loses or hides data

The tooling surfaces silently drop constructs they don't understand, hide actionable errors
behind resilient-looking empty states, and conflate "nothing here" with "broken". The fix
vocabulary is **preserve-or-refuse** (never lossily rewrite) and **name the actual problem**
(never render a corrupt file as an empty one).

### T1 — Studio's compile-before-write is a no-op for most document kinds

`StudioService.preview()` validates only `.sql`, `web/**/*.yml` (as a *route*), `.html`, and
`.tpl`; everything else falls through to `PreviewResult.valid("text", …)`, and `applyDraft`
gates on `preview.valid()` — so a broken `decisions/`, `domains/`, `rules/`, `calendars/`,
`batch/`, `tests/`, `config/`, or `*.view.yml` draft is written straight to the source of
truth and fails only at the next reload. Worse: a `web/**/*.view.yml` view document *matches*
the route branch and false-greens as a route (because `RouteDefinition` is `ignoreUnknown`
and a view carries `version/id/kind/recipe`), so `ViewSpec`'s own checks never run.

**Fix**: give `preview()` a parser branch per known document kind (all entry points exist:
`parseDecisions`, `parseDomains`, `parseRuleSets`, `parseCalendars`, `parseJob`,
`ViewSpec.parse`, `TestSuiteLoader.load`, `configOnly`) and make the default arm
`invalid("unknown", …)` — `applyDraft` must refuse a kind it cannot validate. Tighten the
route branch to `isRouteYaml` and add a `.view.yml` branch. Complication: `preview` validates
*unsaved editor text* while the parsers read the file; either add String overloads or stage a
temp file. Cross-document semantics (view ids from filenames, `DecisionSets` cross-references)
can only be fully validated app-wide, so single-doc preview validates *syntax and local
shape*, with app-wide checks staying at apply/reload.

### T2 — the mail composer deletes scaffold checksums and comments on save

`MailComposer.parse()` strips HTML comments before matching, so a commented template reports
`composable`, and `write()` never re-emits them — saving a scaffolded mail template silently
deletes its `<!-- tesseraql-scaffold-checksum: … -->` marker (which, per `ScaffoldChecksum`,
"hands the file over to the user permanently") plus every author comment. The sibling
`PageBuilder` on the same Studio page captures a verbatim prefix/suffix precisely to avoid
this. **Fix**: capture the leading comment prelude as a verbatim prefix on `Composition` and
re-emit it (parse must stay comment-tolerant — a test pins that); a comment *between* blocks
still needs the block loop's `isBlank()` checks widened to `isPassive`-style, or the whole
save refused when `template != write(parse(template))`.

### T3 — the extension's MCP-config writer destroys other servers on a JSONC file

`.vscode/mcp.json` is JSONC (VS Code ships it with `// comments` and trailing commas), but
`mcpConfig` does `JSON.parse` and, on failure, rebuilds the file from an empty root — so
confirming the (misleadingly worded) "already has a different 'tesseraql' entry — overwrite?"
dialog **discards every other MCP server and all comments**, contradicting the shipped
"merge-preserving, never overwrite a foreign entry" contract. A test currently *pins the data
loss*. **Fix**: distinguish a parse failure from an entry conflict; on parse failure never
write — return the existing content unchanged with a distinct `unparseable` flag and a dialog
that says "open it and add the entry manually". A comment-preserving *merge* would need
`jsonc-parser` (the extension's first runtime dependency — a packaging decision), so the
no-dependency refuse-to-clobber fix ships first.

### T4 — one broken document makes all editor intelligence vanish

`SymbolsCommand` uses the *strict* `ManifestLoader.load`, so a single unparseable route
document throws and emits no symbols; the extension discards the error and leaves `byHome`
unpopulated, so completion and go-to-definition for `policy:`/`domain:`/`use:`/`workflow:`/
`calendar:`/`job:` silently vanish app-wide with only a hidden output-channel line — the user
concludes the extension is broken. The tolerant loader (`load(home, brokenList)`, used by
`RouteReloader`) already exists. **Fix**: load tolerantly in `SymbolsCommand`, emit what
parses plus a `broken[]` array (stably ordered — the document is contractual), and surface one
actionable status-bar warning via the `reportCliProblem` pattern. Note `sharedDefinitions`
parses `domains/`/`rules/`/`decisions/`/`calendars/` per file with no per-file try, so those
need tolerance too.

### T5 — draft apply hides failure reasons and fails open on a corrupt sidecar

`applyAllDrafts` folds "does not compile", "read-only", and "conflict" into one `skipped`
counter with the message discarded, and the page labels every leftover "conflicting" — a
broken draft is left behind mislabelled with no reason. Separately, `draftConflicts` returns
`false` when the `.meta` sidecar is corrupt (the `IOException` is swallowed), so concurrent-
edit detection **fails open** into a silent clobber. **Fix**: return per-draft outcomes with
their error and render a failed-list distinct from conflicts; treat an *unreadable* sidecar as
a conflict (a *missing* one is documented back-compat for pre-feature drafts — log, don't
block).

### T6 — DocService renders corrupt overlays as empty states

`schemaDiffDdl()` returns null for both "schemas match" and "baseline corrupt/unreadable",
rendered as `-- No schema changes since the baseline.` — an operator with a corrupt baseline
is told the database matches and generates an empty migration (and a corrupt *current* schema
reads as parity against nothing). `report()`, `history()`, and `apiChangelog()` share the
shape. The `schemaCorrupt()` predicate already exists for exactly one of the five surfaces.
**Fix**: replicate it as `reportCorrupt()`/`historyCorrupt()`/`apiChangelogCorrupt()`/
`schemaBaselineCorrupt()` and render distinct copy — cheap, no signature changes, no test
rewrites.

### T7 — the MCP dev tools report failures as successful results

`McpDevTools.ops_status` returns a `TqlException` as a normal `{"note": …}` result with
`isError = false` (an agent cannot tell "ops schema broken" from "no events"), and
`scaffold_crud` returns a `blocked` report with `isError = false` while the CLI exits 1 on the
same condition. Both opt out of the working `McpToolResult.error` mechanism. **Fix**: set the
error flag; have `McpToolResult` carry structured content *and* the error flag so the blocked-
file data survives.

### T8 — the doc-reference completeness gaps

`SchemaReference` renders a property with no description as an empty table cell — 43% of the
rendered YAML-surface reference documents nothing, and adding a new undescribed key never
fails the build. **Fix**, two independent pieces: render `—` (one-line, like
`ErrorIndex.meaningCell`, ships now); and a completeness gate with a ratcheting high-water
allow-list so new undescribed properties fail while the ~102-row backlog of descriptions —
which are also the editor hovers — burns down. The prose is a real content project, sequenced
after the render fix.

### T9 — the minor tooling items

`ScaffoldCommand` prints eject failures/blocked-skips to stdout with a non-zero exit (move the
failure branches to stderr; keep the `wrote/flipped` lines on stdout); `writeOverlaySection`
re-serializes `config/overlay.yml` and drops comments on a single-key edit with no warning,
unlike its sibling `routeFormSave` (add the note, and surface it in the UI since overlay is a
hand-maintained file); `McpDevTools` silently falls back from an unresolvable configured
datasource to the embedded DB, hiding the real config error from the agent.

---

## Slices

Each wave ships as its own slice (or a small stack), each leaving the tree releasable. Waves
are independent and can interleave; within a wave, order is roughly worst-first.

1. **Wave S design + S1–S2** — the two authorization fail-opens (scope `when:`, tenant pool),
   the sharpest of the campaign.
2. **S3–S6** — JWT `exp` coercion, `csrf` value+default, `inputPolicy` enum, strict boolean
   helper. The config-gated breaking halves (require-`exp`, `csrf` default flip) are called out
   as Phase-34 defaults decisions.
3. **Wave K strictness core** — `lintUnknownKeys` (1043) + `lintRemovedKeys` (1044) +
   `lintUnclaimedFiles` (4205), the three tree-walking lints that share the `ViewSpec` pattern.
4. **K4** — the residual load-path items (constraints strictness, mcp kind, i18n reference
   loop, queries file-existence, shared SQL lint for tools/consumers, timeout, placeholder,
   menu/flags, value coercion, suite no-op 1403, doc drift).
5. **Wave O ops triad** — O1 (queue events surface, 9008), O2 (poll-path reject routing +
   rejected count + summary), O3–O7 observability, O8 (batch 400, 4043).
6. **O9–O10** — coverage/regression gate corruption split, the smaller confirmed items.
7. **Wave T preservation** — T1 (preview gate) + T2 (mail prelude) + T3 (mcp.json refuse) as
   the data-loss trio; T4–T9 as follow-ups.
8. **Editor + docs catch-up** — extension intel for any new lint codes; regenerate
   `reference-error-codes.md` and `reference-yaml-surface.md`.

## Out of scope

Investigated by the sweep and **cleared** — documented, deliberate degradation, not defects:

- **SAML `sp.acsUrl` unset skips the Recipient check.** Documented at `docs/saml.md` as a
  recommended optional hardening control ("without it those are off"), to support
  IdP-initiated-only deployments. Making `acsUrl` required is still rejected — but the genuine gap,
  the absence of a warning, **was closed**: lint `TQL-SEC-4092` now says the control is off, the
  same way `TQL-SEC-4065` does for the analogous optional mTLS `trustBundle`. That asymmetry
  (mTLS warns, SAML silent) was the tell that this belonged in scope after all.
- **`MultiAppGateway` entitlement skip on a missing tenant header, and XFF pass-through.**
  Both carry inline design citations: the gateway is a convenience aggregator, not a security
  boundary (per-app tenancy resolution is the authoritative check, `required: true` by
  default), and the address budget is documented "secondary — XFF-first, spoof-rotation
  accepted; the login key is the one that must hold". Denying on a missing header would break
  every legitimate pre-tenant request.
- **`AssetsRouteBuilder` keying its ETag on mtime+size.** The silent-tolerance defect here — an
  ETag cached per path forever, so an edited asset kept answering `304` — was fixed by hashing
  the bytes actually served on each request. Re-keying on `mtime+size` would only *reduce*
  correctness (a content hash cannot miss an edit that leaves the timestamp alone) in exchange
  for skipping a read; that is a caching-cost decision, not a tolerated failure, so it is out of
  this campaign's scope.
- **mTLS PKIX skipped when `trustBundle` is unset.** Already covered by lint `TQL-SEC-4065` and
  documented as trusting the edge. (The SAN *type* confusion it aggravated — email/URI/DNS matched
  interchangeably — was **taken back in scope and fixed**: `san:` is replaced by the type-qualified
  `sanDns`/`sanUri`/`sanEmail`/`sanIp`, and the untyped key is `TQL-SEC-4066` at lint and at boot.
  A clean pre-1.0 break, not a compat alias: a config that keeps working while meaning something
  weaker than it reads is the exact failure mode this campaign exists to remove.)
- **Removing the 44 `@JsonIgnoreProperties` annotations.** Rejected in favor of the
  tree-walking lint — see the Wave K preamble for the three concrete blockers (MCP documents,
  no tolerant-load recovery, the forward-compat schema promise).
- **Studio route-form blank-name field drop.** The documented deletion gesture ("Clear a name
  to drop the field") over a fixed-slot table with no add/remove button — an emptied slot *is*
  the delete, and the result lands in a diffable draft, not the source. Not a silent loss.

## Related designs

[contract-bugfixes.md](contract-bugfixes.md) (the sibling pre-1.0 bug sweep this extends),
[vocabulary-cleanup.md](vocabulary-cleanup.md) (the renames Wave K's `TQL-YAML-1044` makes
diagnosable), [view-composition.md](view-composition.md) (the `TQL-VIEW-3314` strictness
pattern Wave K generalizes), [multi-tenancy.md](multi-tenancy.md) (the isolation contract S2
enforces), [file-transfers.md](file-transfers.md) (the transfer status contract O2 respects).
