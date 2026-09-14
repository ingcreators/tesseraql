# Field domains

> **Status: complete.** The `domains/` documents, the `domain:` reference with load-time
> merge, the constraint catalog, and the lint family are implemented (user-facing docs in
> [declarative-validation.md](declarative-validation.md#field-domains)); every follow-up slice
> has shipped — including the Studio authoring surfaces: a Domains reference page in the
> documentation portal (declarations, constraint chips, referencing routes, the constraint
> catalog, and an unreferenced marker mirroring `TQL-FIELD-4611`), a per-column domain chip on
> the schema table pages, a domain select in the route form, and the validation builder
> pointing single-field constraints at domains before a cross-field rule is written. The scaffolder slice is shipped: `scaffold crud`
> generates `domains/<table>.yml` from column metadata and routes that reference it, so the
> DDL→validation link is live, and the inventory gallery app hand-adopts a shared `sku` domain
> that had already drifted across two routes. OpenAPI emission is shipped: a pure `domain:`
> reference becomes a named component schema (`domain.<name>`, `$ref`'d from every operation),
> while a route that tightens a domain key keeps its inline schema so the contract never hides
> an override. The documentation portal's route pages surface the reference: each input's
> constraint chips lead with its `domain`, and the route-spec JSON carries the field.

A **field domain** is a named, application-level definition of a business field — its type,
constraints, format, and data classification — declared once and referenced from any route's
`input:` block. Today the knowledge "an SKU is an uppercase code of at most 40 characters" is
restated in every route that accepts an SKU; each restatement can drift independently. A domain
moves that knowledge to one place, while each route keeps saying what is genuinely *operational*
about the field — whether this particular operation requires it, defaults it, or accepts it at all.

It builds on subsystems already in the framework:

- **[Declarative validation](declarative-validation.md)** — the per-field `input:` constraint
  vocabulary (`type`, `min`/`max`, `minLength`/`maxLength`, `pattern`, `format`, `enum`) and the
  `TQL-FIELD-4220` error model. Domains add no new constraint kinds; they name and share the
  existing ones.
- **[Response shaping](response-shaping.md)** — field policies (`classification`, `mask`) key off
  the same field identity; a domain is the natural home for "this field is personal data".
- **[Scaffolding](scaffolding.md)** — the CRUD scaffolder already derives `maxLength` from
  VARCHAR column sizes, but inlines the literal into each generated route. With domains it emits
  the derivation once, and regeneration keeps the DDL link live.
- **The `scope/` document convention** ([organizational data scoping](data-scoping.md)) — the
  precedent for named, reusable app-home definitions referenced from many routes.

## The model

Domains live in a `domains/` directory in the app home, next to `scope/`. Each file holds a map of
named domains; files merge into one app-wide namespace, and a name declared twice is a build
error.

```yaml
# domains/catalog.yml
version: tesseraql/v1
domains:
  sku:
    type: string
    maxLength: 40
    pattern: "[A-Z0-9-]+"
  quantityDelta:
    type: integer
    min: -10000
    max: 10000
  email:
    type: string
    format: email
    maxLength: 254
    classification: personal
    mask: fixed
```

A route references a domain with the `domain:` key and states only its operational choices:

```yaml
# web/products/adjust/post.yml
input:
  sku:   { domain: sku, required: true }
  delta: { domain: quantityDelta, required: true }
  note:  { type: string, maxLength: 200 }   # route-local fields remain exactly as today
```

### A domain backed by a code catalog

A field whose legal values are a code master's rows says so once:

```yaml
domains:
  取引区分:
    type: string
    maxLength: 2
    codes: 取引区分        # a single-key catalog (docs/lookups.md)
```

The route's `input:` then references the domain as usual, and the binder accepts only that
catalog's **active** codes — a retired one renders on last year's rows and is refused on new
ones. The violation is the `enum` field error, because a catalog is a dynamic enum and clients
should not have to learn a second shape.

**A miss is re-checked before it is refused.** The catalog is held for its TTL, so a code an
operator added a minute ago would otherwise be rejected for the rest of the hold; a value the
held copy does not carry is re-read from the source first. The cost lands only on the
rejection path.

The line against `enum:` is the one [lookups.md](lookups.md) draws: an `enum` is *contract* —
it rides into OpenAPI and gates the embedded-variable allow-list (`TQL-SQL-2109`) — so it
cannot change without a deploy. A catalog is *data*, maintained by whoever owns the master.
Use `enum:` when code branches on the values; use `codes:` when the business adds them.

## What belongs to the domain, what belongs to the route

The line is a design invariant, not a convention:

- **Domain keys — the field itself:** `type`, `min`, `max`, `minLength`, `maxLength`, `pattern`,
  `format`, `enum`, `items`, `classification`, `mask`. These are true of the field wherever it
  appears.
- **Route keys — this operation's use of the field:** `required`, `requiredWhen`, `default`,
  `writable`. A create operation requires the SKU; a search filter does not. These keys are not
  accepted inside a domain definition, so a domain can never silently make a field mandatory
  across the application.

A route may restate a domain key to specialize it. Restating is legal in both directions but
linted asymmetrically: tightening (`maxLength: 20` under a 40-character domain) is silent;
loosening (raising a bound, widening an `enum`, dropping `mask`, downgrading `classification`)
produces a warning, because a loosened copy is exactly the drift domains exist to prevent. A
restated `format:` is a third case, neither tightening nor loosening: the API takes ISO and the
legacy column holds `20240103`, so a `result:` entry restating the pattern over its domain's is
not a finding.

### A domain on the way back out (`result:`)

The same domain describes the column a query reads back. A binding's `result:` entry
(documented in [response-shaping.md](response-shaping.md#declaring-a-columns-kind-result))
is a field like an `input:` entry, so it may say `domain: order_date` and inherit the domain's
`type:` and `format:` — the date a legacy column stores as text is the business field the
request binds, declared once. Two things follow:

- A domain may carry `type: json`, the read-only kind: legal on a domain and on a `result:`
  entry, refused on an `input:` that binds it (`TQL-YAML-1064`), since no request binds JSON
  until an input has a validation story of its own.
- The domain's constraint keys — `maxLength`, `minLength`, `pattern`, `enum`, `min`, `max` — are
  **not applied on read**. A read declaration says what the column's text is, not what it may
  be; validating what the database returned against the domain is a different feature. The
  loader merges a domain into a `result:` entry by the read keys alone — `type`, `format`,
  `locale`, `description` — so a constraint key *written on the entry itself* can only have been
  written there, and is refused (`TQL-YAML-1064`).
- A domain may carry `locale:`, the language tag its `format:` parses in on the way back out
  (`de-DE` for a column holding `1.234,50`). It is **not applied on `input:`**, which parses in
  the request's own negotiated locale — the mirror of the constraint keys on read: legal on the
  domain, not merged into an input, and refused when written on an input entry.

## Resolution is compile-time

Domain references resolve in the route compiler, before input binding. The resolved route carries
plain, fully-populated `input:` fields — the runtime binder, the `TQL-FIELD-4220` error model,
OpenAPI emission, and validation coverage all consume what they consume today, unchanged. There is
no runtime lookup, and the compiled artifact shows effective values, keeping generated artifacts
reproducible and reviewable. A `result:` entry's `domain:` resolves the same way, in the manifest
loader, so the compiled binding carries the domain's `type:` and `format:`.

## Constraint catalog

The same declare-once principle covers database constraint mapping. Today `errors.constraints`
entries (`uq_users_email` → field `email`, code `duplicate`) must be repeated in every route that
writes the table. The domains document gains a sibling top-level map:

```yaml
# domains/catalog.yml (continued)
constraints:
  uq_products_sku:
    field: sku
    code: duplicate
    message: products.sku.duplicate
```

Routes inherit the catalog automatically; a route-local `errors.constraints` entry overrides the
catalog entry for that route. The catalog is keyed by physical constraint name, so one rename in
a migration is one edit in one file.

## Tooling

- **Scaffolder** — generates a `domains/` file from column metadata (VARCHAR size → `maxLength`,
  NOT NULL informing the route's `required`), and emits routes that reference the domains.
  Re-scaffolding after a DDL change updates the domain, not N routes.
- **Editors** — `domains/*.yml` has its own JSON Schema, associated by a scaffolded app's
  `.vscode/settings.json`, so completion and validation work offline in any editor with a YAML
  language server. A domain's value is an input field, so its schema carries a copy of the route
  schema's field definition that `SchemaSyncTest` keeps identical.
- **OpenAPI** — a domain becomes a named component schema; routes `$ref` it instead of inlining
  identical inline schemas per operation.
- **Studio** — the validation rule builder and schema overlay offer domains for selection; the
  documentation portal lists domains with the routes that reference them.
- **Lint** (`TQL-DOMAIN-*` family; final numbers assigned against the registry at
  implementation): unknown domain reference (error), duplicate domain name (error), loosening
  override (warning), domain declared but never referenced (info). A reference from a `result:`
  entry counts as a reference.

## Out of scope

- **Domain inheritance or composition.** Domains are flat. If two domains share constraints, they
  are written twice; nesting buys little and costs the reader the ability to see a field's rules
  in one place.
- **Named cross-field rule sets.** Sharing `validate:` rules (expression/SQL rules) across routes
  is a separate design; those rules bind to route parameters and SQL files, so their reuse story
  is not a field-identity story. Revisit once field domains are in use.

## Open questions

1. Should a bare-string shorthand (`sku: sku`) be accepted where the field name equals the domain
   name, or is the explicit `domain:` key always required? Leaning explicit-only: the shorthand
   collides with a future scalar syntax and saves little. The `result:` block asks the same
   (`result: [ordered_on]`) and stays in step: neither surface decides it alone
   (temporal-semantics.md decision 21).
2. ~~Should `enum` domains double as the source for form `<select>` options in declarative
   views?~~ **Resolved — it already does, by construction**: the load-time merge populates the
   route's `input:` with the domain's `enum`, and view form derivation renders a `select` from
   it (documented in declarative-views.md as of docs/view-composition.md wave 3a, which also
   added the `widget:` domain key for the general presentation-hint case).

## Related designs

[Route defaults](route-defaults.md) applies the same declare-once principle to per-route blocks
(security headers, auth modes); [ambient parameters](ambient-params.md) applies it to SQL
parameter wiring. The three are independent slices and ship separately.
