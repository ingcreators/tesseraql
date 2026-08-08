# Vocabulary cleanup — one word per concept before the v1 freeze

> **Status: design accepted; slices 1–4 pending.** Wave C of the pre-1.0
> contract-consistency sweep (after [contract-bugfixes.md](contract-bugfixes.md)
> waves A and B): the YAML-surface and HTTP-wire renames that make one concept one
> word everywhere. Every item is a one-shot pre-1.0 break under the
> latest-release-only support posture; Phase 34 freezes whatever spelling exists, so
> this is the last window in which a rename costs an afternoon instead of a major
> version. Decisions below are locked — implementation inherits them.

## Why now

The YAML surface grew feature by feature, and several concepts picked up a second
name along the way: the same input-field contract is `input:` on a route and
`params:` on a job; the same transport enum is `source:` on a poll and `target:` on
a push; the same column heading is `header:` in an export and `label:` in a view;
the same "expected row count" is `expect.rows` in a binding and `rowCount` in a
suite. None of these is a bug — which is why waves A and B did not touch them — but
each one is a thing every author has to memorize twice, and the v1 schema freeze
makes them permanent.

## Slice 1 — key renames on route/job documents

| Today | v1 | Why |
|---|---|---|
| job `params:` (map of inputField) | `input:` | literally the same `Map<String, InputField>` a route declares as `input:`; `params:` keeps its other meaning (bind expressions / literal values) everywhere else, making the field-contract use the outlier |
| root `policy:` (admission) | `admission:` | `policy` names three unrelated things in one document (admission, `security.policy` authorization id, `response.json.fields.*.policy` unmask); the admission block takes its own doc's name |
| `page:` (pagination block) | `pagination:` | a `recipe: page` route that paginates writes `page:` three lines from `recipe: page`, meaning something unrelated |
| `csrf:` (route boolean; defaults string-enum `"auto"/"true"/"false"`) | enum `auto \| required \| off` on both | one key, two types across two documents that merge per key; and YAML parses bare `csrf: true` as a boolean, so the correct-looking spelling was schema-invalid in a defaults rule |
| `http-call:` | `httpCall:` | the only kebab-case key on a camelCase key surface (values are kebab, keys are not); matches the Java field |
| `expect.rows` (integer) | `expect.rowCount` | `rows` is a list of records everywhere else (wave A track D fixed the wire; this fixes the YAML); adopts the suite's exact vocabulary |
| chunk `onError: {skipLimit}` (object) vs import/http-source `onError:` (string enum) | chunk becomes `onError: skip` + sibling `skipLimit:` | a key whose *type* depends on where it sits cannot be frozen |
| poll `source:` / push `target:` (same `local\|sftp\|ftps` enum) | `transport:` on both | one transport enum, one name; frees `source:` for "where the rows come from" (its other four meanings) |
| export/import column `header:` | `label:` | the same human heading a view column already calls `label:`; view columns also gain the bare-string shorthand export columns always had, removing the same-key-different-polymorphism split |
| workflow `notify:` (fixed two-slot record) | `reminders:` | route `notify:` is a map keyed by notification id; the workflow key is a different structure under the same name (an author's `notify: {overdue: …}` on a workflow is silently dropped) — the rename matches the Java field and ends the collision. The route/job schema description also corrects its inverted "keyed by channel" claim |
| `assign:` (`{file, params}`) vs `onBreach.reassign:` (bare filename) | `reassign: {file, params}` | one assignee-resolution contract, one shape |

## Slice 2 — value vocabulary and document discipline

- **Enum value casing**: multi-word enum *values* go kebab-case, matching the recipe
  vocabulary that dominates the surface: `auth: apiKey` → `api-key`;
  `runOn: businessDay | firstBusinessDayOfMonth | lastBusinessDayOfMonth` →
  `business-day | first-business-day-of-month | last-business-day-of-month`;
  `shift: nextBusinessDay | previousBusinessDay` → `next-business-day |
  previous-business-day`. Single words (`mtls`, `skip`, `keyset`) are untouched.
  Keys stay camelCase — the split is keys-camel / values-kebab, stated in the
  schema `$comment`.
- **Decisions vocabulary**: row `out:` → `outputs:` (the contract already says
  `outputs:`; `out:` was a truncation); input `match: orgSubtree` and source
  realization `subtree:` unify on **`subtree`**; `source.id:` → `source.keyColumn:`
  (a column name under a key called `id`, beside documents where `id:` is the
  document identifier).
- **Typed `binds:`**: `binds: [sku, delta]` (names-only list) becomes
  `binds: {sku: integer, delta: number}` — a mapping, so the load can check the
  wiring against the referencing route's domain-resolved input types. The stated
  precondition ("the type layer rides once domains give inputs stable types") was
  met when field domains shipped. List form is dropped outright (rule 10).
- **`version:` is required everywhere**: every schema lists it in `required`;
  `ViewSpec.parse` starts validating it (today a view never checks); `tests/*.yml`
  gains `version: tesseraql/v1` (the tests schema had no such key at all — the one
  document family that would otherwise be unversionable at 1.0).
- **View discriminator**: `view:` → `recipe:` (`kind: view` + `recipe: list | form |
  detail | dashboard`), the same grammar routes and jobs already use. The root-level
  view keys stay root-level — a flat view document is ergonomic, and routes also keep
  content keys at root; only the discriminator was misnamed. The panel-level
  `type`/`kind` collision resolves as `panels[].kind` → `panels[].chart` (`bar |
  line | combo | …`), with `panels[].type` staying the panel role.
- **`id:` vs `name:`**: the rule is stated, not churned: an *addressable* thing
  declares `id:` (documents, steps, states, transitions, dispatches); a *data
  element* declares `name:` (columns, fields). Test cases keep `name:` as the one
  documented exception — it is both address and prose title, and renaming it would
  rewrite every suite for no authoring win.

## Slice 3 — the HTTP wire

- **Timestamps are ISO-8601 UTC strings** on every framework JSON surface. The ops
  API currently mixes three encodings — `Instant.toString()` (executions, outbox),
  raw `…EpochMs` longs (`/ops/slow-sql`, `/ops/pinning`, `/ops/traces`), and
  `java.sql.Timestamp.toString()` (`/ops/audit`); `/ops/traces/tree` emits the same
  instant in two encodings at once. Conversion happens at the render boundary
  (as `traceTree`'s ISO field already does); `…EpochMs` never reaches JSON again.
- **One `Content-Type` constant** — `application/json; charset=utf-8` — for every
  JSON writer (file-transfer 202s, attachment list/upload, MCP HTTP were bare
  `application/json`, so one route could answer with and without charset depending
  on success vs error).
- **`Location` on 201/202**: the attachment upload 201 and the SCIM 201 (an RFC
  7644 §3.3 violation today) carry `Location`; the file-import/export 202 carries
  `Location` to the status resource (the body keeps `statusUrl`/`fileUrl`);
  `POST /ops/batch/jobs/{id}/run` — the same accepted-poll-later semantics —
  answers **202** with `Location` to the execution detail instead of a bare 200.
- **`Retry-After` from the renderer**: every 429/503 the framework envelope renders
  carries `Retry-After` (the login throttle was the only surface setting it; the
  route rate limiters and lane saturation answered the same statuses without it).
- **One htmx redirect helper**: `RedirectRenderer`'s negotiation (htmx ⇒ `204 +
  HX-Redirect`, else `303 + Location`) is extracted and used by `LoginRouteBuilder`
  (whose logout/login redirects never checked `HX-Request`), `IamAdminRouteBuilder`
  (which documents the gap in a comment), and `CopilotRouteBuilder` (which
  re-implements it by hand).
- **`GET /_tesseraql/logout` is removed**: a state-changing, CSRF-exempt GET. The
  shell's logout affordance becomes the POST form the sibling
  `logout-device`/`logout-others` already use.
- Deliberately *not* here: framework-API pagination (adding `X-Total-Count`/`Link`
  to the capped ops/studio/inbox arrays is additive and can land any time, 1.0
  included); success-payload `ok`-convention unification (a judgment call with no
  wrong answer being frozen); the suite outcome rows vs HTTP envelope divergence,
  which is two deliberate contracts — the row idiom exists so refusals are
  assertable as data — and gets documented as such in testing.md rather than unified.

## Slice 4 — the editor catches up

The extension's completions, snippets, and symbols mention renamed keys
(`http-call`, job `params:`, `overlap:` values, decision `out:`); they move to the
v1 spellings, and the gallery/scaffold regenerate. Version bump rides the next
ext tag the user names.

## Out of scope

- Directory plurality (`workflow/` vs `attachments/`) — cosmetic, and a directory
  rename breaks every app checkout for zero authoring win; the layout doc gains the
  full list instead.
- The decision-table launch-vocabulary questions (step `when:` scope, `decision.*`
  in response shaping, multi-datasource lint) and the other open *decisions* — they
  are wave D (closure), not renames.
- Ambient SQL namespaces (`principal.*`/`audit.*`/`tenant.id`): declared the
  contract as-is; a `ctx.*` unification would touch every `.sql` file in every app
  for uniformity alone.
