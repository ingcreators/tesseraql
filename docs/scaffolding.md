# Scaffolding and Project Generation

`tesseraql new` and `tesseraql scaffold crud` take a team from an empty directory to a
working, tested CRUD slice (roadmap Phase 23). Everything they emit is ordinary TesseraQL
source — YAML routes, 2-way SQL, Thymeleaf pages, declarative suites — indistinguishable from
hand-written code and owned by the app from then on. Generation is a pure function of its
inputs, so the same schema always produces byte-identical artifacts (design ch. 48).

## `tesseraql new <app>`

```bash
tesseraql new order-entry
cd order-entry
tesseraql serve --app .
```

The skeleton is a runnable app home:

```
config/application.yml       server port, main database coordinates (env-overridable)
config/tesseraql.yml         app name, datasource, managed identity realm, security
                             defaults, JWT dev secret, the app.read / app.write policies
db/migration/V1__create_items.sql
                             a starter table following the Phase 18 write conventions:
                             identity key, version column, audit columns, a named unique
                             index — exactly the shape `scaffold crud` consumes
templates/nav.html           the shared sidebar fragment pages reference
web/get.yml + index.html     a public home page on the hc-shell layout
web/api/items/get.yml + search.sql
                             a query-json search with 2-way SQL branches
tests/smoke-test.yml         exercises the seeded row and both SQL branches
.gitignore                   excludes the work/ runtime scratch directory
```

The target directory must not exist yet (or be empty); skeleton files carry no regeneration
marker — they are yours to edit from the first minute.

## `tesseraql scaffold crud --table <t>`

```bash
tesseraql scaffold crud --app . --table items
# or introspect a different database than the app's main datasource:
tesseraql scaffold crud --app . --table items \
  --jdbc-url jdbc:postgresql://localhost:5432/order_entry --username dev --password dev
```

The table's shape is read through plain JDBC metadata (columns in ordinal order, primary
key, single-column unique indexes) and drives the generated slice:

```
web/items/                       list page (live htmx search over a table fragment)
web/items/fragments/table/       query-html fragment + search.sql + table.html (hc-datagrid)
web/items/new/                   create form page
web/items/create/                command-json insert (one transaction, audit binds)
web/items/{id}/                  detail/edit page + select.sql
web/items/{id}/update/           command-json update (optimistic locking)
web/items/{id}/delete/           command-json delete (confirmed, version-checked)
tests/items-crud-test.yml        data-independent suite over the generated queries
```

Conventions are applied when the table opts in:

- **Generated keys** — an auto-generated single primary key is captured with `keys:` and
  drives the post/redirect/get flow (`/items/{steps.record.keys.id}`); a non-generated key
  becomes a required form field instead. Composite keys fail fast (`TQL-APP-5203`).
- **Optimistic locking** — a numeric `version` column emits the
  [transactional-writes.md](transactional-writes.md) pairing: a version predicate in the
  UPDATE/DELETE plus `expect: { rows: 1, onMismatch: conflict }`, so a stale edit answers
  `409 Conflict`. Without the column, neither half is emitted.
- **Audit columns** — `created_by` / `created_at` / `updated_by` / `updated_at` are stamped
  from the canonical `audit.user` / `audit.now` binds, explicit in the SQL.
- **Constraint mapping** — each single-column unique index becomes an
  `errors.constraints` entry, so a duplicate surfaces as a field-level error.
- **Typed binds** — every bind reads the coerced `params.*` view of the declared inputs
  (browser form posts and path parameters arrive as strings); `date` columns ride the
  blessed `hc-datepicker` native-input skin, `datetime` inputs declare their HTML form
  format, booleans use the hidden-false + checkbox pattern.
- **Command SQL carries no trailing semicolon** (like the transactional-writes examples):
  drivers append `RETURNING` for generated-key capture, which a terminator would break.

The pages compose the framework `tql/shell` layout with `templates/nav.html :: app-nav`. The
list page renders its rows as a Hypermedia Components **`hc-datagrid`** — a scroll container that
keeps wide tables horizontally scrollable with the header in view, degrading to a plain styled grid
with no JavaScript. Its **column headers sort server-side**: each header is a link to
`fragments/table?sort=<col>&dir=<asc|desc>`, swapped in over htmx (search term and sort state ride
along via `hx-include`), and `aria-sort` drives the kit's sort arrow — CSP-clean, no inline JS. The
`search.sql` `ORDER BY` is an [embedded variable](transactional-writes.md#embedded-variables-dynamic-identifiers)
— `/*# order by t.{sort} {dir}, t.<pk> */` — so the whole clause lives in a comment and the file
stays runnable in a plain SQL tool, with the primary key as a stable pagination tiebreaker. The
`sort`/`dir` inputs are `enum` allowlists (so an interpolated value can only be a known column or
direction — no injection, enforced by `TQL-SQL-2109`), defaulting to the primary key / ascending. The
create and edit forms follow the Hypermedia Components
**mutating-form recipe**: an htmx post
(`hx-post` mirroring `method`/`action`) with an in-form field-errors container, a
double-submit guard and busy spinner, and the hidden CSRF field — degrading to a plain form
post with no JavaScript. A failed write swaps the kit's field-errors fragment inline (a `422`
validation error, a `409` optimistic-locking conflict, or a `409` constraint violation
distributes to the offending input); a success answers `HX-Redirect` for the htmx caller
(`204`) and a plain `303 Location` for the no-JS caller. The edit page's delete uses the
confirmed-destructive variant — `data-hc-confirm` gates the submit and the form fires on
`hc:confirmed`. The generated security blocks reference the `app.read` / `app.write` policies
the skeleton defines — the CLI prints a hint when an app is missing them or the nav template.

### CSRF, on by default

The mutation routes declare `csrf: true`, and the form-bearing pages (list, create, edit)
authenticate as `browser`/`app.read` so the shell renders `<meta name="csrf-token">` with the
session token. On the htmx path the kit's `installCsrfHeader` behavior reads that tag and
attaches the `X-CSRF-Token` header to every request; on the no-JS path the hidden `_csrf` form
field carries the token (the framework's `csrf` step accepts either, and treats `_csrf` as a
reserved field so it never trips the mass-assignment guard). See
[docs/hypermedia-ui.md](hypermedia-ui.md) for the full recipe markup and the convention.

## Regeneration and edit detection (design ch. 22.20)

Every `scaffold crud` file carries one checksum comment over the rest of its own content:

```
# tesseraql-scaffold-checksum: sha256:0603c981…
```

Rerunning the command is idempotent — an unchanged schema rewrites nothing. When the schema
or the generators change, files whose checksum still matches (pristine generated output) are
regenerated in place; files you edited no longer match and are **skipped and reported** (the
command exits 1), and files with no marker at all are never touched. `--force` overwrites
both. Deleting the checksum line hands a file over permanently. There is no ledger outside
the files themselves.

## The example gallery is dogfooded

[`examples/scaffold-demo-app`](../examples/scaffold-demo-app) is exactly
`tesseraql new scaffold-demo` plus `tesseraql scaffold crud --table items` — not a byte of
hand editing. CI proves it stays that way:

- `ScaffoldDogfoodIntegrationTest` (Maven plugin module) applies the skeleton's migration to
  PostgreSQL, regenerates the app, and asserts the committed tree is byte-identical; then
  lints it (no errors, no undefined-policy warnings) and runs its declarative suites — both
  generated search templates at 100% branch coverage.
- `ScaffoldedCrudIntegrationTest` (runtime module) boots the app and drives the full flow
  over HTTP: create with a generated key, edit, a stale-version 409 (`TQL-SQL-4092`), a
  duplicate-name field error, and a confirmed delete.

After changing the generators intentionally, refresh the gallery and commit the diff:

```bash
./mvnw -pl tesseraql-maven-plugin test -Dtest=ScaffoldDogfoodIntegrationTest \
  -Dtesseraql.scaffold.regenerate=true
```

## Error codes

| Code | Meaning |
| --- | --- |
| `TQL-APP-5201` | introspection failed: unknown table or unreadable metadata |
| `TQL-APP-5202` | a scaffolded path escapes the app home (design ch. 20.2) |
| `TQL-APP-5203` | unsupported target: invalid app name, non-empty `new` target, or a table without a single-column primary key |
