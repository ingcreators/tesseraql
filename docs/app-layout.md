# Application layout

The canonical layout of a TesseraQL application (design ch. 4). The same rules apply to user
apps, the bundled system apps (ops-console, studio, iam-admin) and future `.tqlapp` packages.

```
config/                 application.yml / tesseraql.yml (+ overlay.yml)
web/                    routes; the directory tree mirrors the URL space one-to-one
  api/users/            GET /api/users
    get.yml             route definition
    search.sql          colocated 2-way SQL (referenced as sql.file: search.sql)
  users/                GET /users (an HTML page)
    get.yml
    index.html          colocated template (referenced as template: index.html)
  users/fragments/table/  GET /users/fragments/table (an htmx fragment)
    get.yml
    search-table.sql
    table.html
batch/                  job definitions (yml + colocated sql)
db/migration/           Flyway-managed schema migrations (V1__name.sql, ...), applied when the
                        app is mounted; per-app history table, run per tenant pool in
                        schema/database-per-tenant modes
templates/              shared templates only: app-wide fragments and layouts
assets/                 static files, served at /assets/** (mounted apps at /assets/<app>/**)
security/               identity contract SQL (sql realms), key material paths
governance/             approvals.yml - the route review ledger (route id + approved source
                        SHA-256), gating routes whose mode or risk score requires review
work/                   runtime scratch (drafts, spools, mounted apps); never committed
```

## URL mapping

`web/` mirrors URLs exactly: the directory path is the URL path, the file name is the HTTP
method (`get.yml`, `post.yml`, ...), and `{name}` directories declare path parameters. No
directory is special-cased, so the URL is always predictable from the file path.

Distinctions are URL conventions, not folder rules:

- `/api/...` — JSON APIs (query-json / command-json / query-export recipes)
- `/...` — HTML pages (page / query-html recipes)
- `.../fragments/<name>` — htmx fragments: partial markup (no `<html>` element) swapped into a
  page by hypermedia requests. Fragments are ordinary, addressable resources.
- `/_tesseraql/...` — reserved for the framework and its system apps
- `/assets/...` — static assets (`/assets/_tesseraql/*` framework files, `/assets/vendor/*`
  self-hosted WebJars at version-less paths)

## Route folders are the unit of work

A route folder holds everything the route needs: the definition (`get.yml`), its data
(`*.sql`, resolved relative to the folder) and its presentation (`*.html` / `*.tpl`, resolved
relative to the folder first). The shared `templates/` directory is only for templates used by
more than one route. The framework-shared layout is referenced as
`~{tql/shell :: page(title, header, content)}` (system navigation) or
`~{tql/shell :: shell(title, nav, header, content)}` (app-specific navigation) and never copied
into apps.

## Templates

- `*.html` renders in Thymeleaf HTML mode; everything else (e.g. `*.yml.tpl`) renders in TEXT
  mode for `response.file` downloads.
- Pages are full documents composed with the `tql/shell` fragment; fragments are partial markup.
- `@{...}` link expressions are not available (no web context); build URLs with literal
  substitution: `th:href="|/users/${u.id}|"`.
