# View composition and field presentation

> **Status: complete.** All waves landed 2026-08-08/09 — wave 0 (#635), wave 1 (#636),
> wave 2a (#637), waves 2b/2c, 3a, 3b, 4, and the finishing slice (extension intel,
> docs) in the follow-on PRs. The user-facing documentation lives in
> declarative-views.md; only historical rationale remains here. This is an
> internal design document (docs-site `EXCLUDED`); the user-facing documentation stays in
> [declarative-views.md](declarative-views.md) and
> [declarative-validation.md](declarative-validation.md) and is rewritten as the waves
> land. The second chapter of declarative views: views become name-referenced first-class
> entities, compose (fragment negotiation + embedding), and the field-presentation and
> field-masking gaps close on both the read and write side. Every breaking change here is
> deliberate pre-1.0 work, sequenced to land **before** the Phase 34 schema-freeze
> declaration — the `v` model and the fragment signatures are public API, and this is the
> last window in which they can be corrected rather than versioned.

## Motivation

Declarative views (Phase 39) made the YAML view the default authoring surface and demoted
Thymeleaf to the escape hatch at the bottom of the customization ladder. Both ends of that
ladder are solid; the middle is missing:

- **Fragment-mode views** are planned but absent — an htmx target region still needs a
  hand-written fragment template, which is exactly the busywork views exist to remove.
- **Sharing is colocated-only.** Nothing indexes view documents, nothing checks id
  uniqueness, and the mechanics around a shared document (slot resolution, lint,
  ejection) are per-route in ways that make sharing quietly wrong rather than illegal.
- **Composition is two ad-hoc vocabularies.** A dashboard composes `panels:`, a detail
  composes `children:`, and neither can say the obvious thing: *embed that view here*.
- **Presentation knowledge is unshareable.** Field domains stop at validation; "an SKU
  renders as a code input" must be restated per view, and the read side (list columns,
  detail values) cannot reference domains at all.
- **Masking is asymmetric.** `FieldPolicyApplier` runs for JSON responses only — the same
  row renders masked in JSON and raw in HTML.
- **Hiding a form field is cosmetic.** A form's `fields:` subset trims rendering only;
  the command route's `input:` block still accepts every writable field, and
  `writable:` has no per-role form.

## What the code says today (defects this design also fixes)

The design review surfaced concrete defects; wave 0 exists to clear them before any new
vocabulary is added.

1. **View documents drop unknown keys silently.** `domains/` rejects unknown keys
   (`TQL-FIELD-4602`); `ViewSpec` does not. Live evidence:
   `examples/procurement-app/web/dashboard/dashboard.view.yml` writes `label:` on stat
   panels — `Panel` has no such component, the key is discarded, and the rendered titles
   actually come from the message-key fallback.
2. **Doc/code mismatches.** [declarative-views.md](declarative-views.md) promises child
   and panel `source:` may name an `http:` source, but `ViewBinding`/`AppLinter` accept
   only `queries:` (TQL-VIEW-3308 fires); the same doc says "Ejection is not offered for
   dashboards" while `ViewEjector` implements it; and the coverage section claims "one
   item per view document" while the implementation keys coverage by route.
3. **The fragment anchors are wrong for embedding.** `form.html` declares
   `th:fragment="view(v)"` on the `<form>` element, leaving the card, title, and
   `notFound` state outside the fragment; `dashboard.html` leaves the chart `<script>`
   pair outside. Inserting either fragment into another page silently drops chrome and
   renders charts as bare tables.
4. **Sharing hazards.** Slot references resolve against the *route* directory, so a
   shared document resolves different files per route; lint findings repeat once per
   referencing route; `ViewEjects` flips one route and silently leaves the other rendering
   the view it just forked.
5. **Residual over-posting surface.** Input whitelisting and the mass-assignment guard
   are sound; the residue is precisely the inputs a route declares writable but a given
   form omits — they bind and flow to SQL with no server-side notion that this form never
   offered them.
6. **Already true, undocumented:** a domain's `enum` flows through the load-time merge
   into `input:` and `ViewFields` derives a `select` — the enum-as-options loop
   ([field-domains.md](field-domains.md) open question 2) is closed by construction and
   should be documented as such.

## Design

### Wave 0 — truth restoration

No new features; make the existing surface say what it does.

- **Strict view documents.** Unknown keys anywhere in a `kind: view` document — top
  level, fields, columns, children, panels, series — are a build error (new code in the
  `TQL-VIEW-33xx` family; final numbers assigned against the registry at
  implementation). This is the same posture `domains/` already takes, and it is the
  safety net every later vocabulary addition relies on. Existing silent errors (the
  procurement `label:` panels) surface and are fixed in the same slice.
- **HTTP sources become legal child/panel sources**, as the documentation already
  promises: TQL-VIEW-3308 validates `source:` against `queries:` ∪ `http:`; the
  `{rows}` shape `HttpSourceProcessor` publishes is exactly what `sourceOf` reads, so
  the runtime change is validation-only.
- **Fragment anchor correction.** `tql/view/form.html` moves its `view(v)` anchor to the
  outer `<section>` (card, title, header slot, `notFound` included);
  `tql/view/dashboard.html` moves the Plot bundle + `charts.js` `<script>` pair inside
  the fragment, gated on `v.hasChart` as today. Breaking to the public fragment
  contract; recorded in the CHANGELOG. L2 overrides of these two files must re-anchor —
  the TQL-VIEW-3307 signature lint catches stale overrides.
- **Documentation truth**: the dashboard-eject sentence and the coverage sentence in
  [declarative-views.md](declarative-views.md) are corrected (coverage stays
  route-exercised until wave 1 gives view documents an identity; the doc then flips back
  with the implementation).

### Wave 1 — the view registry

Views become what domains already are: **name-referenced, app-level, indexed at load**.

- The manifest loader indexes every `*.view.yml` under the app home (colocated files
  stay exactly where they are; authoring conventions do not change). The view `id` — 
  explicit, or defaulted from the filename as today — must be **unique app-wide**;
  a duplicate is a build error.
- **`response.html.view:` takes the id, not a path** (breaking). The path form is
  removed, not deprecated — pre-1.0, one gallery+scaffolder migration, and the reference
  becomes rename-proof and location-independent.
- **Slot references resolve against the view file's directory**, then `templates/`
  (breaking). Today they resolve against the route directory, which makes a shared
  document resolve different fragments per route — indefensible once sharing is legal.
- **Shared views become supported.** Multiple routes referencing one id is legal; the
  documented constraint is that DOM ids and message-key namespaces derive from `v.id`
  and are therefore shared. Per-route source/search/sort validation (3308/3309/3310)
  runs per referencing route as today; *document-shape* lint findings report once per
  document, not once per route.
- **Ejection respects sharing.** `ViewEjects` refuses to eject a view referenced by more
  than one route, listing the routes; forking a shared view is an explicit
  copy-then-eject, never a silent side effect.
- **Coverage keys by view document** — one item per document, exercised when any
  referencing route is invoked by a declarative suite — making the existing doc claim
  true.

### Wave 2 — composition

#### 2a. Fragment mode is content negotiation, not a key

A view-backed route serves **both** shapes from one URL:

- `HX-Request: true` → the view fragment, no shell — an htmx target region.
- Direct navigation → the shell-wrapped page, exactly as today.

Every view-backed response carries `Vary: HX-Request`. The explicit control is
`response.html.shell: auto | always | never`, default **`auto`** (the negotiation
above); `always` restores today's behavior, `never` declares an htmx-only region
endpoint. This removes the standing workaround — hand-written fragment templates whose
only purpose is "this URL must not return a full page" — and makes every view URL
deep-linkable and partially-updatable at once. Defaulting to `auto` is a behavior
change and the most visible break in this design; it is also strictly the hypermedia-
correct default, and wave 0's anchor fixes are its prerequisite.

#### 2b. Embedding: views embed views

`panels:` and `children:` stop being closed vocabularies and gain the one entry type
they were missing:

```yaml
# dashboard — a panel that is a view
panels:
  - { type: stat, source: sql, column: total }
  - { type: view, view: requests.recent }        # embedded list view

# detail — children reference views; inline columns stay as the shorthand
children:
  - { view: requests.history, source: history }
  - { source: notes, columns: [ {name: body} ] } # shorthand, unchanged
```

- **The route remains the sole data owner.** An embedded view reads the *host route's*
  context through its `source:`; there is no per-view query execution. Source
  validation (3308) runs against the hosting route.
- **Embedding depth is 1.** An embedded view that itself declares `children:` with view
  references or `type: view` panels is a build error. This keeps the model assembly,
  ejection, and the reader's mental model flat.
- **Model assembly** becomes recursive one level: the embedded view's `v` model is built
  from the same context and attached to its panel/child entry. The `v` shape addition is
  public API, CHANGELOG-tracked.
- **Route `model:` entries are no longer discarded** when `view:` is set (breaking, but
  strictly additive in capability): they merge into the template model alongside `v`,
  which becomes a reserved name (declaring `model.v` is a build error).

#### 2c. Declarative parts on hand-owned templates — the ladder round-trip

A `template:` route may bind view models without owning a view:

```yaml
response:
  html:
    template: overview.html
    views: [requests.recent, requests.stats]   # published as views['<id>']
```

The template inserts `~{tql/view/list :: view(${views['requests.recent']})}` wherever it
likes. This is the piece that makes L3 non-terminal: **ejecting a composite view emits
exactly this shape** — the host layout pins into the template, while embedded views stay
declarative and keep deriving from their routes. The ladder's biggest cost today —
ejection freezes everything — drops to "ejection freezes the layout you ejected".

### Wave 3 — domain presentation and read-side masking

- **`widget:` becomes a domain key.** "An SKU is a code input" is declared once. The
  chain is four known edits: `DOMAIN_KEYS`, the `InputField` component,
  `mergedWith` carry-through, and `ViewFields` precedence — per-view `fields:` override
  > domain widget > type-derived default, with the existing `WIDGETS` guard unchanged.
  Presentation hints are not contract: **excluded from OpenAPI emission**, and exempt
  from the loosening lint (there is no "looser" widget). Ejection follows for free —
  `ViewEjector` consumes the same derived `FieldDef` list.
- **Read-side columns and fields gain `domain:`** — explicit opt-in on `columns:` and
  detail `fields:` entries; no name-based inference, ever. The reference brings the
  domain's presentation hints and, decisively, its `classification`/`mask` to the read
  side.
- **HTML output masking.** `HtmlResponseRenderer` applies `FieldPolicyApplier` — the
  same resolution order JSON already uses (`visible: false` → unsatisfied `policy` →
  `mask`/`classification`) — during `v` assembly: list cells, detail values, stat
  panels. This closes the JSON-masked/HTML-raw asymmetry and implements the read-side
  masking [declarative-views.md](declarative-views.md) marks as planned.
- **Documentation**: the enum→select loop is documented as shipped behavior, closing
  [field-domains.md](field-domains.md) open question 2.

### Wave 4 — write-side field policy

Hiding a field must mean the server does not accept it. One declaration drives both:

```yaml
# web/employees/{id}/update/post.yml
input:
  name:   { domain: personName, required: true }
  salary: { domain: salary, policy: hr.write }   # only principals satisfying hr.write
```

- **`policy:` is a route-level operational key on `input:` entries** — like `required`
  and `writable`, it is *not* accepted inside a domain (the domain/route invariant
  holds: a domain can never silently gate a field application-wide).
- **Enforcement at the binder boundary.** A field whose policy the current principal
  fails is treated exactly as `writable: false`: the `effectiveInputPolicy`
  reject/ignore/warn path applies, evaluated with the same `PolicyEngine` + `Principal`
  the read side's `FieldPolicyApplier` takes. Server truth first; everything else is
  derived from it.
- **Rendering derives from the same evaluation.** `ViewFields.derive` filters the form's
  field list by the same policy check, so the field a principal cannot write is the
  field their form does not show — the form-derivation principle ("the HTML constraint
  and the server validation are the same declaration") extended to authorization. The
  per-role form stops requiring N command routes.
- **OpenAPI is unchanged**: policy-gated fields remain declared — the contract does not
  vary by role. Per-role contract views are out of scope.

### Finishing wave

- Scaffolder migration + `scaffold-demo` regeneration
  (`-Dtesseraql.scaffold.regenerate=true`; never hand-edited) + gallery migration to id
  references.
- Editor extension catch-up (established pattern, tail of the campaign): view-id
  completion for `view:`/`views:`/panel-`view:`, `shell:` / `policy:` / domain
  `widget:` intel, embedding snippets.
- Documentation rewrite: [declarative-views.md](declarative-views.md) gains the
  composition chapter and the corrected ladder; [declarative-validation.md](declarative-validation.md)
  gains `policy:`; [app-layout.md](app-layout.md) finally mentions `*.view.yml` — and
  `domains/` and `rules/`, both currently missing from its canonical tree.

## Breaking changes (all pre-1.0, all before the schema freeze)

| # | change | migration |
| --- | --- | --- |
| 1 | `response.html.view:` path → id reference | gallery (18 references) + scaffolder; mechanical |
| 2 | slot resolution base: route dir → view-document dir | all gallery slots are colocated; near-zero blast radius |
| 3 | unknown keys in view documents rejected | surfaces existing silent errors (that is the point) |
| 4 | fragment anchors move (`form.html`, `dashboard.html`) | fragment contract break, CHANGELOG; L2 overrides re-anchor (3307 catches) |
| 5 | `shell: auto` default (HX-Request negotiation) | behavior change; `Vary: HX-Request` emitted; `shell: always` opts out |
| 6 | route `model:` merges alongside reserved `v` | strictly additive; `model.v` becomes an error |
| 7 | children/panels accept view references | inline shorthand kept; migration zero |

## Machine-checkable surface

New codes continue the `TQL-VIEW-33xx` family (final numbers assigned against the
registry at implementation): unknown key in a view document (error), duplicate view id
(error), unresolved view id (error, replacing the path variant of 3302), embedded-view
depth exceeded (error), `model.v` reserved-name collision (error), shared-view eject
refusal (error, CLI/Studio surface), `views:` entry naming an unknown id (error).
Write-side `policy:` reuses the policy-reference validation the security lints already
apply elsewhere. Existing per-route checks (3303/3304/3305/3308/3309/3310) are
unchanged in meaning; document-shape checks report once per document after wave 1.

## Guards

- **Strict parsing**: fixture views with stray keys at every nesting level fail; the
  procurement fixture is corrected in the same commit that makes it an error.
- **Registry**: duplicate-id and unresolved-id build failures; shared-view lint reports
  once; shared-view eject refuses with the route list.
- **Negotiation ITs**: one URL, both shapes — `HX-Request` present/absent → fragment vs
  shell — plus the `Vary` header on every view response; `shell: always|never`
  overrides.
- **Embedding ITs**: dashboard-with-embedded-list and detail-with-view-children render;
  depth-2 fails at build; embedded chart panels load their scripts (the wave-0 anchor
  fix regression-tested from the embedding side).
- **Round-trip**: ejecting a composite view emits a `views:`-binding template that
  `PageBuilder.parse` accepts (shape 1), byte-safe wrapper preserved.
- **Masking parity**: one fixture route with `mask`/`classification`/`policy` fields
  asserted equal between the JSON response and the rendered HTML (list, detail, stat).
- **Binder policy gate**: a policy-failing principal posting the gated field hits the
  reject/ignore/warn matrix; the rendered form omits the field; a policy-passing
  principal round-trips it.
- Gallery suites + dialect-gated suites stay green throughout; `scaffold-demo`
  regenerates byte-identically after the scaffolder migration.

## Slices

1. **Wave 0** — strict parsing + http sources in 3308 + fragment anchors + doc truth.
2. **Registry** — load-time index, id references, slot re-anchoring, shared views,
   eject refusal, per-document lint/coverage (+ gallery/scaffolder migration).
3. **Negotiation** — `shell: auto|always|never`, `Vary`, fragment serving.
4. **Embedding** — panel `type: view`, view-referencing children, recursive model,
   `model:` merge + reserved `v`, `response.html.views:` on template routes, composite
   eject.
5. **Domain presentation** — domain `widget:`, `domain:` on columns/detail fields,
   OpenAPI exclusion, enum→select documentation.
6. **HTML masking** — `FieldPolicyApplier` in `v` assembly, parity guard.
7. **Write-side policy** — `input.policy:`, binder enforcement, `ViewFields` filter.
8. **Finishing** — scaffolder/gallery regen, extension catch-up, documentation rewrite.

Slices 1–2 are prerequisites for everything after them; 3–4 and 5–7 are independent
tracks and can interleave. Each slice ships alone and leaves the tree releasable.

## Explicitly rejected

- **A YAML layout language** (`recipe: composite` with free-form rows/columns). Layout
  freedom is what L3 + the visual page builder are for; wave 2c makes that path
  round-trippable instead of terminal. The declarative layer stays semantic — what
  data, which operations, which constraints — and delegates looks to the pattern
  fragments and hc.
- **Per-view query execution.** The route owns data acquisition; views consume named
  sources. Splitting execution per view would fork transaction, security, and
  pagination semantics for no authoring gain.
- **Name-based column→domain inference.** Explicit `domain:` only; matching result-set
  column names to domain names is spooky action and breaks on the first alias.
- **`policy:` inside domains.** Operational keys stay on routes; the domain/route
  invariant is load-bearing for the whole domains design.
- **Per-role OpenAPI variants** for policy-gated fields.

## Related designs

[declarative-views.md](declarative-views.md) (the shipped first chapter and the
user-facing home for everything here), [field-domains.md](field-domains.md) (the
registry precedent and the domain/route invariant), [response-shaping.md](response-shaping.md)
(`FieldPolicy` vocabulary this design extends to HTML and to the write side),
[page-builder.md](page-builder.md) (the L3 surface wave 2c hands off to).
