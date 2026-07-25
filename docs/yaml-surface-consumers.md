# YAML surface consumer audit

> **Status: slice 1 shipped, 2–5 designed.** [config-consumers.md](config-consumers.md) closed
> "the scaffolder emits a config key nothing reads". The 2026-07-25 contract-deviation sweep found
> the same failure class one layer up, in the **YAML model itself**: five record components across
> the spec records are parsed, accepted without error, and never consumed. Two of them are
> documented as working features with shipped examples using them. This document extends that
> document's guard from config keys to model fields, and records the audit.

The failure class, restated for this layer: an app author writes a key the parser accepts, the
generated YAML reference or a doc page describes it, and no framework code reads it. This is the
`camel-file forceWrites` shape — the option Camel's own AI audit found dead since 2.20, documented
with a durability guarantee it never provided. The user believes they configured a behavior; the
runtime holds a different one, and the surface confirms the illusion.

The sweep enumerated ~250 record components across the 40 model records and traced each to a
production consumer — route compilation, runtime, lint, OpenAPI, docs generation, scaffolding, or
Studio. Five are dead. Everything else is alive.

## The audit

| Field | What the author reasonably believes | Reality | Disposition |
| --- | --- | --- | --- |
| `policy.concurrency.rejectStatus` | "overload returns the status I chose" | Two repo-wide hits, both the declaration. `ConcurrencyLimiter` has no status field and hard-throws `RATE 4291` → 429. The javadoc documents a "default 429", implying a configurability that never existed | **Retire** |
| `input.<field>.items` (+ `InputItems.type` / `enumValues`) | "array elements are typed and enum-checked" | `InputBinder.coerce` has no `array` case, so `type: array` falls to `default -> raw` and a JSON array reaches `params.*` as the string `"[a, b]"`. `OpenApiGenerator` emits no `items` schema; `McpInputSchema` advertises array as `"string"` | **Wire** — see below |
| `jobs: params:` (`JobDefinition.params`) | "job parameters are declared, typed, and validated at trigger time" | `.definition().params()` — zero hits repo-wide. `JobExecutor` uses entirely the caller's map; the operations route passes the raw JSON body unvalidated; the scheduler fires `Map.of()` | **Wire** — see below |
| `security.provider` | "this route authenticates against the named provider" | `applySecurity` branches on `auth`/`csrf`/`policy` only; `TesseraqlAuthProducer` selects purely from `getAuth()`. Read only by the portal's route-spec generator and Studio's security panel — **display-only**, so both surfaces actively confirm the illusion. No lint validates the name either | **Retire** |
| `response.stream.contentType` | "the download is served with this content type" | Only `filename()` is read; the real header comes from the codec. Display-only in the portal | **Retire** |

Two of these are worse than merely dead, because the surface promises them:

- **`items` is documented and shipped.** [reference-yaml-surface.md](reference-yaml-surface.md)
  documents `items` and its sub-table as a supported key, and `examples/user-admin-app` ships
  `type: array` in a production route. That example works only by accident: its SQL binds
  `body.memberIds` rather than `params.memberIds`, so it never touches the coercion path that would
  have failed it.
- **`jobs: params:` is documented and shipped.** [jobs.md](jobs.md) promises that declared `params:`
  document the expected names and types, and `examples/user-admin-app/batch/directory-sync/job.yml`
  ships a `params:` block that is parsed and discarded. A `required: true` there is silently
  unenforced.

The dispositions follow [config-consumers.md](config-consumers.md)'s rule unchanged: **wire it when
the promise is worth keeping, retire it outright when it is not** — never leave a field that
promises and does nothing. Per rule 10 (no compat goal before 1.0) the three retirements are
deletions, not deprecations, with CHANGELOG entries.

`rejectStatus` retires rather than wires because a 429 is the correct answer to a concurrency
rejection and a configurable status invites an app to return something a load balancer will
mis-handle. `security.provider` retires because provider selection is a realm/config concern, not a
per-route one — the honest fix is to stop displaying it. `response.stream.contentType` retires
because the codec owns the content type by design and a declaration that disagrees with the bytes
is a bug generator.

### What wiring means for the two survivors

**`items`** needs the array to be a real input type: `InputBinder.coerce` gains an `array` case
that splits or accepts a JSON array, coerces each element to `items.type`, and applies element-level
`enum`; `OpenApiGenerator.fieldSchema` emits the `items` schema; `McpInputSchema` advertises
`"array"` with its item type. The existing example then works on purpose rather than by accident.

**`jobs: params:`** needs a binding step: the job runner validates the caller-supplied map against
the declared parameters (name, type, `required`) before the first step runs, failing with a
field-error payload the operations API can render, and the declared set becomes what the Studio job
page and the docs portal show. This is where a job gains the input contract every route already has.

## An adjacent surface lie

`ErrorIndex` renders error codes by appending the raw integer key while `TqlErrorCode.toString()`
pads to four digits, so [reference-error-codes.md](reference-error-codes.md) carries one row reading
`TQL-LD-1` for a code the runtime emits as `TQL-LD-0001`. Every other code is already ≥4 digits, so
this is the only affected row — and it is self-contradictory, because the row's meaning text is
harvested from a javadoc that spells `TQL-LD-0001`. A user who hits the maxRows overflow and
searches the reference finds nothing. One-line fix in the generator, folded into whichever slice
lands first.

## The guard: extend the drift test to the model

The audit must not be a one-time cleanup, for the reason config-consumers gives: the next model
field will be added, documented, and left unwired, and nothing will notice. The mechanism is the
same one, pointed at a different source of truth.

1. **A consumer registry for model fields.** `YamlSurfaceConsumers` maps every record component of
   every spec record to its consuming class — `"PollSpec.moveFailed" → PollingRouteBuilder.class` —
   with an explicit `DISPLAY_ONLY` marker for fields whose only legitimate consumer is the portal or
   Studio, so "display-only" becomes a declared decision rather than an accident.
2. **The drift test reflects over the records.** A test walks every record in the model package via
   reflection, asserts each component is present in the registry, and fails the build on a new field
   with no registered consumer: *wire it or don't declare it*.
3. **The registry is honest, not decorative.** As in the config-key guard, a second assertion probes
   each registration — the named consumer's source must actually reference the accessor — so a
   registry entry cannot be a wish. The `DISPLAY_ONLY` marker is probed against the portal/Studio
   generators specifically, which is what would have caught `security.provider`: it *has* consumers,
   and all of them only print it.
4. **The generated reference follows the registry.** `ReferenceGenerator` marks a `DISPLAY_ONLY`
   field as such in the published YAML surface, so a reader can tell "documented and enforced" from
   "documented and shown".

Point 3 is the one that matters most here. A naive "is this accessor called anywhere?" check passes
for three of the five dead fields, because docs and Studio read them. The distinction the guard has
to encode is between a consumer that *changes behavior* and one that *renders text*.

**Correction, from measuring it.** A bytecode scan of all 26 built modules — every constant-pool
method and field reference into `io.tesseraql.yaml.model` — puts the model at **294** record
components, and finds **43** with no reference from outside the model package at all. Those 43 are
not 43 dead fields. Nearly all of them are read by a *derived accessor on their own record*, which
is what the rest of the framework calls:

| Component | Read by | Called from |
| --- | --- | --- |
| `PollSpec.move`, `PollSpec.moveFailed` | `effectiveMove()`, `effectiveMoveFailed()` | `PollingRouteBuilder` |
| `ImportSpec.columns`, `.sheet`, `.headerRow`, `.startRow` | `toReadSpec()`, `effectiveHeaderRow()`, `effectiveStartRow()` | `PollingRouteBuilder`, `RouteCompiler` |
| `CacheSpec.etag`, `.visibility` | `etagEnabled()`, `effectiveVisibility()` | the response pipeline |
| `ResponseSpec.*.status` (four records) | `effectiveStatus()` | the response pipeline |
| `InputPolicy.unknownFields`, `.readOnlyFieldBehavior` | `rejectsUnknownFields()`, `readOnlyBehaviorOrDefault()` | input binding |
| `InputField.writable` | `isWritable()` | input binding |
| `HttpSourceSpec.*` | `toCall()`, `degradesToEmpty()` | the HTTP source |
| `ColumnSpec.*` | `toMapping()` | file read/write specs |

Defaulting through a derived accessor is the model's normal idiom, not an exception, so step 3 as
written above would report roughly forty correctly-wired fields as unwired — and a guard that cries
wolf forty times is one whose failures get waved through, which is worse than no guard.

So a registration names the consumption *path*, not only a class: either an external consumer that
calls the accessor, or a derived accessor on the same record plus the external consumer that calls
*it*. The probe follows that one hop and no further; a chain longer than one hop inside the model is
itself worth a look. `DISPLAY_ONLY` is unaffected — it is about which consumers exist, not how they
reach the field.

This also re-scopes slice 2: the registry is ~294 entries with about forty needing the two-part
form, and the honest first step is generating the draft from the same bytecode scan rather than
hand-authoring it, since the scan is what the drift test will run anyway.

## Slices

1. ~~**The three retirements** plus the `ErrorIndex` padding fix.~~ **Shipped.**
   `policy.concurrency.rejectStatus`, `security.provider`, and `response.stream.contentType` are
   gone from the model, and with them the display-only readers that made two of them look real:
   the portal route page's `provider:` badge and the route spec's stream content type. The
   error-code index now zero-pads, so the row a user searches for after hitting the maxRows
   overflow reads `TQL-LD-0001` — the code the runtime actually emits — instead of `TQL-LD-1`.
   **Not** in this slice, deliberately: `items` and `jobs: params:`, because those two are
   documented with shipped examples and need implementing rather than deleting (slices 3–4).
2. **The registry and its drift test** (guard steps 1–3), seeded with the surviving ~245 fields.
   Landing this before the wiring work means the two wired fields arrive with their registrations
   already required.
3. **`jobs: params:` wiring** — the larger of the two, since it introduces job input validation and
   touches the operations API, the scheduler, and Studio's job page.
4. **`items` wiring** — array coercion, element validation, OpenAPI `items`, MCP array type.
5. **`DISPLAY_ONLY` in the generated reference** (guard step 4).

## Lint and tooling

- No new lint codes: a field with no consumer is a build failure in the framework's own test suite,
  not an app-authoring diagnostic. Apps never see the guard.
- `jobs: params:` wiring introduces a job-parameter validation failure, which reuses the existing
  field-error envelope rather than inventing one.
- The retirements need CHANGELOG entries naming each key, because an app that sets one today will
  fail to parse afterwards rather than silently ignoring it — which is the point.

## Out of scope

- **Config keys.** [config-consumers.md](config-consumers.md) owns those and its guard already
  ships; this document deliberately mirrors it rather than merging into it.
- **SQL binds and expression namespaces.** A bind that resolves to null is a different failure class
  (silent null rather than dead declaration), handled in
  [route-governance-parity.md](route-governance-parity.md).
- **Fields that are alive but under-consumed** — e.g. a constraint honored by the binder but not
  rendered by the portal. That is a parity gap, tracked in
  [shared-definitions-reach.md](shared-definitions-reach.md), not a dead field.

## Open questions

1. Should `DISPLAY_ONLY` exist at all, or should every model field change behavior? The honest cases
   are few (a `title:` or `description:` genuinely only renders). Leaning: keep it, because the
   alternative is that display-only fields go unregistered and the guard develops holes — but require
   a one-line justification string on each, which is what makes `security.provider` obviously wrong
   when someone tries to write it.
2. Does `items` justify a full array input type, or should `type: array` be retired alongside the
   others and array input handled by the SQL binding (`body.memberIds`) as the shipped example
   already does? Leaning wire — MCP tools make typed array inputs materially more valuable than they
   were when the field was added, since a model reads the schema to construct the call.
3. Should the reflective drift test cover `tesseraql-core` value records too, or stop at the YAML
   model package? Leaning stop at the model: core records are internal API, and the failure class
   here is specifically "an app author can write it".
