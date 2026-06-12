# Declarative Validation

A command declares its business-rule validation in YAML (roadmap Phase 19): cross-field
rules in the core expression language plus validation SQL — SELECTs whose returned rows are
the violations (uniqueness, existence, balance checks) — executed inside the command's
transaction, before a single step writes. Violations come back as a field-scoped
`422 Unprocessable Entity` with a stable error model: rule ids, field paths, rule codes, and
message keys (localized rendering arrives in Phase 22). Input constraints (`input:` type,
required, range, enum) still reject malformed requests with `400` at bind time; `validate:`
is the business-rule layer behind them.

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
    message: members.email.duplicate # a message key, translated in Phase 22
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

- `rule:` — a cross-field expression in the core expression language (design ch. 8.1):
  comparisons, `&&`/`||`/`!`, dotted paths over `body`, `query`, `path`, `principal`,
  `tenant`. The language is whitelist-only — no method calls, no side effects.
- `file:` — a validation SQL file, a plain SQL-tool-runnable 2-way SELECT. It executes on
  the command's connection, inside the transaction, so it sees a consistent snapshot (and
  may lock rows with `FOR UPDATE` for balance checks). A non-SELECT fails at route build
  time: validation must not write.

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
     "message": "members.email.duplicate"},
    {"rule": "dateOrder", "field": "endDate", "code": "end-before-start"}]}}
```

`code` defaults to the rule id; `message` is a message key, not display text. htmx callers
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
        data-message-key="members.email.duplicate">email: duplicate</li>
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
`coverage.thresholds.kinds.validation` threshold gates the build like any other kind.

## Lint

Lint reports statically what would otherwise fail at route build time:

- `validate:` on a non-command recipe (`TQL-YAML-1003`)
- a rule with both or neither of `rule:`/`file:`, a missing `field:`, or validation SQL
  that writes (`TQL-FIELD-2003`)
- malformed `when:`/`rule:` expressions (`TQL-SQL-2101`)
- a missing rule SQL file (`TQL-SQL-2103`)

## Error codes added in this phase

| Code | Status | Meaning |
| --- | --- | --- |
| `TQL-FIELD-4220` | 422 | declarative validation rejected the input |
| `TQL-FIELD-2003` | — | invalid validation rule declaration (build/lint time) |
| `TQL-YAML-1003` | — | lint: `validate:` on a non-command recipe |
