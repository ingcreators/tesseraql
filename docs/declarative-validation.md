# Declarative validation

A [command](transactional-writes.md) declares its business-rule validation in YAML: cross-field
rules in the core expression language plus validation SQL — SELECTs whose returned rows are
the violations (uniqueness, existence, balance checks) — executed inside the command's
transaction, before a single step writes. Violations come back as a field-scoped
`422 Unprocessable Entity` with a stable error model: rule ids, field paths, rule codes, and
message keys, localized at render time through the app's message catalogs
([internationalization.md](internationalization.md)). Input constraints (`input:` type,
required, range, enum) still reject malformed requests with `400` at bind time; `validate:`
is the business-rule layer behind them.

## Input constraints

The declared-input vocabulary covers what LOB forms actually need, so simple rules never
leak into SQL. On any `input:` field:

```yaml
input:
  email:
    type: string
    format: email          # email | uuid | url — semantic validators for string fields
    pattern: ".+@corp[.]example"   # anchored regex (full match); lint pre-compiles it
    minLength: 6
    maxLength: 320
  price:
    type: number
    min: 0.5               # decimal-exact bounds: 0.49 violates min: 0.5
    max: 9999.99           # and 10000 violates max: 9999.99 — no float drift
  note:
    type: string
    requiredWhen: params.kind == 'noted'   # conditional requiredness, the core
                                           # expression language over the request context
```

- For `date`/`datetime`/`number` fields, `format:` remains the locale-aware **parse
  pattern**; for string fields it is one of the semantic validators above.
- `requiredWhen` is pre-compiled at build (bad syntax fails the build; `TQL-YAML-1014` in
  lint) and evaluated after every input is coerced, against the same `params.*`/`path.*`/
  `body.*` namespaces expressions use elsewhere. An absent field whose condition holds is
  rejected exactly like `required: true`.
- **Typed path parameters**: a path segment declared under `input:` (the scaffold's
  `{id}` routes do this) is coerced and validated like any input, and the `path.*`
  namespace carries the typed value; an undeclared path parameter stays a raw string.
- Every rejection is the field-scoped `TQL-FIELD-2001` shape with a stable code
  (`pattern`, `minLength`, `email`, `uuid`, `url`, …) and a localizable
  `tql.input.<code>` message (en/ja built-ins included), rendered inline on the htmx
  path like every other field error.
- The constraints ride into the generated OpenAPI (`pattern`, `minLength`/`maxLength`,
  `minimum`/`maximum`, `format: email|uuid|uri`, enums) — the contract and the
  enforcement are one declaration.

## Field domains

The same business field crosses many operations, and restating "an SKU is an uppercase code of at
most 40 characters" in every route invites drift. A **field domain** declares the field once,
app-wide, under `domains/`; routes reference it and state only what is operational:

```yaml
# domains/catalog.yml
version: tesseraql/v1
domains:
  sku:
    type: string
    maxLength: 40
    pattern: "[A-Z0-9-]+"
  email:
    type: string
    format: email
    maxLength: 254
    classification: personal
    mask: fixed
```

```yaml
# web/products/adjust/post.yml
input:
  sku: { domain: sku, required: true }
```

The line between the two is enforced, not conventional: a domain may carry the field itself
(`type`, bounds, `pattern`, `format`, `enum`, `items`, `classification`, `mask`), and is rejected
if it declares the operational keys (`required`, `requiredWhen`, `default`, `writable` —
`TQL-FIELD-4602`), so a domain can never silently make a field mandatory across the application.
A route may restate a domain key to specialize it; tightening is silent, loosening draws lint
`TQL-FIELD-4610`. Unknown references fail the load (`TQL-FIELD-4601`), duplicate names across
files fail the load (`TQL-FIELD-4600`), and a domain nothing references is flagged
(`TQL-FIELD-4611`).

Resolution happens when the app manifest loads: routes carry fully-populated fields afterwards,
so binding, this page's error model, OpenAPI emission, and validation coverage are unchanged.

A domains document may also carry the app-level **constraint catalog** — database constraint
names mapped once instead of per route:

```yaml
constraints:
  uq_products_sku:
    field: sku
    code: duplicate
```

Every route inherits the catalog; a route-local `errors.constraints` entry overrides the
catalog's mapping by name. One rename in a migration is one edit in one file.

## The validate block

```yaml
version: tesseraql/v1
id: members.register
kind: route
recipe: command-json

input:
  email:
    type: string
    required: true
  startDate:
    type: string
  endDate:
    type: string

validate:
  uniqueEmail:                       # the rule id; also the default rule code
    file: check-email.sql            # a SELECT returning violations
    params:
      email: body.email
    field: email                     # the field path violations are reported against
    code: duplicate
    message: members.email.duplicate # a message key (see internationalization.md)
  dateOrder:
    when: body.endDate != null       # optional guard; a falsy guard skips the rule
    rule: body.endDate >= body.startDate   # must hold for the input to be valid
    field: endDate
    code: end-before-start

sql:
  file: insert-member.sql
  mode: update
  keys: [id]
  params:
    email: body.email

response:
  json:
    status: 201
    body:
      memberId: sql.keys.id
```

Rules evaluate in their authored order and **all of them run** — the response carries every
violation, so a form repaints once. Each rule declares exactly one of:

- `rule:` — a cross-field expression in the core expression language: comparisons, `&&`/`||`/`!`, dotted paths over `params`, `body`, `query`, `path`,
  `principal`, `tenant` (`params` and `query` name the same map — the examples here use
  `params`). The language is whitelist-only — no method calls, no side effects.
- `file:` — a validation SQL file, a plain SQL-tool-runnable 2-way SELECT. It executes on
  the command's connection, inside the transaction, so it sees a consistent snapshot (and
  may lock rows with `FOR UPDATE` for balance checks). A non-SELECT fails at route build
  time: validation must not write.

## The expression language

`validate:` rules, `requiredWhen`, `response.html.headersWhen` guards, and workflow guards
share one deliberately small, side-effect-free expression language. It covers the
arithmetic and string logic LOB rules actually need:

- **Operators** (by precedence): `||`, `&&`, `==`/`!=`, `<`/`>`/`<=`/`>=`, `+`/`-`,
  `*`/`/`/`%`, unary `!`/`-`, and `(...)` grouping. Arithmetic is decimal-exact
  (`BigDecimal` — `qty * price <= budget` carries no float drift); `+` concatenates when
  either side is a string; a `null` operand propagates `null`.
- **Functions** (whitelist-only — unknown names and wrong arities fail the build):
  the built-ins `length(s)`, `lower(s)`, `upper(s)`, `trim(s)`, `contains(s, sub)`,
  `startsWith(s, p)`, `endsWith(s, p)`, `matches(s, regex)`, `abs(n)`, `round(n)`,
  `floor(n)`, `ceil(n)`, `min(a, b)`, `max(a, b)`, `coalesce(a, b)`, plus any
  [custom functions](#custom-functions) installed from the app's modules. Built-in
  predicates are null-safe (`false` on null), transforms propagate `null`.
- There is no method invocation, reflection, or assignment.

```yaml
validate:
  overBudget:
    field: total
    code: over-budget
    rule: params.qty * params.price <= params.budget
  corpMail:
    field: email
    code: corp-mail
    rule: matches(lower(trim(params.email)), '.+@corp[.]example')
```

## Custom functions

When a rule needs one predicate the built-ins cannot express — a checksum, a code-format
rule, a business-calendar check — you do not have to fall back to validation SQL or a full
runtime extension. Implement the `ExpressionFunction` SPI (`tesseraql-core`, dependency-free),
one class per function:

```java
public final class IsKatakana implements ExpressionFunction {
    public String name() { return "isKatakana"; }
    public int arity() { return 1; }
    public Object apply(List<Object> args) {
        return args.get(0) != null
                && String.valueOf(args.get(0)).matches("[\\u30A0-\\u30FF]+");
    }
}
```

Register it in the jar's
`META-INF/services/io.tesseraql.core.expr.ExpressionFunction`, publish the jar, and declare
it like any other module:

```yaml
# config/tesseraql.yml
tesseraql:
  modules:
    - com.example:example-expression-functions
```

`serve`, `lint`, `test`, `coverage`, and `mcp` all install the functions from the resolved
modules classpath before parsing (the CLI's `--modules <dir>` composes for local jars; the
Maven goals discover functions declared as plugin dependencies). Once installed, the function
is callable wherever the expression language runs — `validate:` rules, 2-way SQL `/*%if*/`
directives, `requiredWhen`, notify `when:` guards, workflow guards:

```yaml
validate:
  kanaName:
    field: kanaName
    code: not-kana
    rule: isKatakana(trim(body.kanaName))
```

The rules that keep the language safe still hold:

- **The purity contract.** A function must be side-effect-free (no I/O, no state mutation),
  total (return `null`/`false` for absent or mismatched values instead of throwing, like the
  built-ins), and fast — expressions evaluate on every request and inside validation
  transactions. This is a contract, not a sandbox: the modules set is the reviewed,
  lock-pinned channel (`modules.lock`), which is exactly why functions load from it and not
  from `plugins/`.
- **Fail-fast installation.** A name that is not a legal identifier, shadows a built-in, or
  is contributed twice stops the command with `TQL-SQL-2110` — a broken function jar can
  never silently change what an existing expression means.
- **Parse-time whitelist.** Unknown names and wrong arities are still build errors. An app
  that calls custom functions therefore fails `tesseraql admission` (the declarative-only
  gate lints without the app's modules), which is intentional: custom Java is `extended`
  territory, not marketplace territory — see [admission.md](admission.md).

## Validation SQL: rows are violations

```sql
select
  'email' as field
from
  members
where
  email = /* email */'taken@example.com'
```

An empty result means the input is valid. Each returned row becomes one violation; columns
named `field`, `code`, or `message` override the rule's declared defaults per row, and any
other column rides along into the error payload — so a balance check can return the
offending line number. The SELECT's author decides what is exposed; never select internal
diagnostics.

## The error model

A violating request answers `422` with `TQL-FIELD-4220`:

```json
{"error": {"code": "TQL-FIELD-4220", "message": "Unprocessable Entity",
  "fields": [
    {"rule": "uniqueEmail", "field": "email", "code": "duplicate",
     "messageKey": "members.email.duplicate", "message": "Already exists."},
    {"rule": "dateOrder", "field": "endDate", "code": "end-before-start"}]}}
```

`code` defaults to the rule id. The declared message key rides as `messageKey`, and
`message` carries the localized text resolved with the request locale — the app catalog's
entry for the key, falling back to the built-in `tql.constraint.<code>` texts
([internationalization.md](internationalization.md)). The top-level `message` is the
localized status phrase. htmx callers
(`HX-Request: true`) receive the same details as the Hypermedia Components field-errors
fragment; the kit's auto-installed `installFieldErrors` behavior distributes each item next
to the input matching its `data-field` (with `aria-invalid`/`aria-describedby` wiring) and
resolves `data-message-key` through the kit's message catalog:

```html
<div class="hc-alert" data-variant="error" role="alert" data-hc-field-errors
     data-error-code="TQL-FIELD-4220">
  <p class="hc-alert__title">Unprocessable Entity</p>
  <ul class="hc-alert__errors">
    <li class="hc-alert__error" data-field="email" data-code="duplicate"
        data-message-key="members.email.duplicate">Already exists.</li>
  </ul>
</div>
```

Because validation runs first in the transaction, a violation rolls back having written
nothing — sequences, steps, and outbox events all stay untouched.

## Testing rules in declarative suites

A suite case can target a route's rules directly — the violations are the case's rows — so a
rule is testable without serving the route:

```yaml
tests:
  - name: a taken email is rejected
    validate:
      route: members.register      # evaluates the route's whole validate: block
    params:
      body:
        email: taken@example.com
        startDate: "2026-01-01"
    expect:
      rowCount: 1
      rows:
        - rule: uniqueEmail
          field: email
          code: duplicate

  - name: ordered dates pass the cross-field rule
    validate:
      route: members.register
      rule: dateOrder              # optional: narrow the case to one rule
    params:
      body:
        startDate: "2026-01-01"
        endDate: "2026-12-31"
    expect:
      rowCount: 0
```

The case's `params:` map is the execution context the rules see (typically a `body:` map).
SQL rules run against the test database and record line/branch coverage like SQL-file cases.

## The validation coverage kind

Every rule of every route's `validate:` block is declared as `<routeId>.<ruleId>`; a
validation case covers the rules it evaluates (the targeted rule, or the route's whole block
when no `rule:` is named). Gaps surface in the coverage report and as SARIF findings, and a
`coverage.thresholds.validation` threshold gates the build like any other kind.

## Lint

Lint reports statically what would otherwise fail at route build time:

- `validate:` on a non-command recipe (`TQL-YAML-1003`)
- a rule with both or neither of `rule:`/`file:`, a missing `field:`, or validation SQL
  that writes (`TQL-FIELD-2003`)
- malformed `when:`/`rule:` expressions (`TQL-SQL-2101`)
- a missing rule SQL file (`TQL-SQL-2103`)

## Error codes

| Code | Status | Meaning |
| --- | --- | --- |
| `TQL-FIELD-4220` | 422 | declarative validation rejected the input |
| `TQL-FIELD-2003` | — | invalid validation rule declaration (build/lint time) |
| `TQL-YAML-1003` | — | lint: `validate:` on a non-command recipe |
