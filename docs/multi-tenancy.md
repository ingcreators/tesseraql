# Multi-tenancy

A multi-tenant TesseraQL application serves several customer organizations — tenants — from one
deployment, keeping each tenant's data isolated from every other's. Tenancy is off by default:
an application is single-tenant until `tenancy.enabled: true` is set, and nothing on this page
applies before then. Once enabled, every request resolves a tenant before its SQL runs, and that
tenant drives either a row predicate (shared schema) or the choice of connection pool
(schema/database per tenant).

Tenancy answers *which organization's data* a request may touch. What a caller may see *within*
their tenant — their department, their own records — is the job of
[organizational data scoping](data-scoping.md), which deliberately mirrors the tenant-predicate
mechanism one level deeper.

## Isolation modes

The `tenancy` block lives in `config/application.yml` alongside the database coordinates. Three
modes exist; `shared-schema` is the default when tenancy is enabled.

### Shared schema

All tenants share the `main` datasource and every tenant-owned table carries a tenant column.
Isolation is a predicate the route author writes into the SQL:

```yaml
# config/application.yml
tenancy:
  enabled: true
  mode: shared-schema
  required: true          # reject requests without a resolvable tenant (the default)
  resolver:
    type: header
    source: X-Tenant-Id
```

```yaml
sql:
  file: list.sql
  mode: query
  params:
    tenant_id: tenant.id
```

```sql
select id, name
from items
where tenant_id = /* tenant_id */ 'dummy'
order by id
```

Because the predicate is hand-written, lint watches for its absence: see
[`TQL-TENANT-3001`](#lint-and-errors) below.

### Schema per tenant and database per tenant

Each tenant gets its own connection pool, declared under `tenancy.datasources` — one block per
tenant id, same keys as any pool (`jdbcUrl` required, `username`/`password` optional and secrets
by reference). The two modes share one mechanism and differ only in where the URLs point: for
schema-per-tenant, each pool targets a different schema of the same server (for example a
`currentSchema` parameter on a PostgreSQL URL); for database-per-tenant, entirely separate
databases:

```yaml
# config/application.yml
tenancy:
  enabled: true
  mode: database-per-tenant
  resolver:
    type: header
    source: X-Tenant-Id
  datasources:
    acme:
      jdbcUrl: jdbc:postgresql://db-acme:5432/app
      username: app
      password: ${secret.env.ACME_DB_PASSWORD}
    globex:
      jdbcUrl: jdbc:postgresql://db-globex:5432/app
      username: app
      password: ${secret.env.GLOBEX_DB_PASSWORD}
```

In these modes the application tables need no tenant column — isolation is structural. The
resolved tenant's pool replaces `main` for that request's SQL; a resolved tenant with no
configured pool is rejected with 403 (`TQL-TENANT-4031`) rather than silently falling back to a
shared pool. Tenant routing replaces **only** `main`: an explicit non-main `datasource:` on a
route is deployment-shared infrastructure and is never tenant-routed
([multi-datasource routes](multi-datasource.md)).

## Tenant resolution

Resolution runs once per request, after authentication, and publishes the tenant into the
execution context. Two resolver types exist:

- **`header`** (the default) — the tenant id is read from a request header, `X-Tenant-Id`
  unless `resolver.source` names another. Typically a gateway in front of the application sets
  and trusts this header.
- **`claim`** — the tenant id is read from the authenticated principal, following the dotted
  path in `resolver.source` (default `tenantId`). The principal's tenant is populated by the
  authentication mechanism — a JWT `tenantClaim`, an OIDC claim mapping, or an API key / mTLS
  client whose tenant is bound to the credential so a caller cannot escalate across tenants
  ([authentication](authentication.md)).

With `required: true` (the default when tenancy is enabled), a request with no resolvable tenant
is rejected with 400 (`TQL-TENANT-4001`) — deny-by-default. The resolved tenant is also recorded
on the route's telemetry span.

Resolution by host name or subdomain is not supported; put a gateway that maps host to header in
front if you need it.

## The tenant in SQL

The resolved tenant joins the [2-way SQL](two-way-sql.md) bind sources as `tenant`: `tenant.id`
is the tenant identifier, and `tenant.attributes.<name>` exposes any additional tenant metadata.
On [per-tenant jobs](jobs.md#per-tenant-jobs) the same `tenant.id` bind is available even though
there is no request.

### Lint and errors

| Code | Meaning |
| --- | --- |
| `TQL-TENANT-3001` | warning — a shared-schema SQL route neither binds `tenant.*` nor mentions a tenant column in its SQL; the query would leak rows across tenants. Bind `tenant.id` or filter by a tenant column. |
| `TQL-TENANT-4001` | 400 — no tenant could be resolved for the request (with `required: true`). |
| `TQL-TENANT-4031` | 403 — the resolved tenant has no configured datasource in a per-tenant mode. |
| `TQL-TENANT-5005` | the tenant registry query failed. |

Data scoping reuses this exact shape — a parameterized predicate at an author-chosen site,
backed by a lint — to confine rows *within* the tenant by the principal's organizational reach.
Tenancy decides whose database or rows a request touches at all; [scoping](data-scoping.md)
decides which of those rows this particular caller may see.

## The tenant directory

Anything that fans out over all tenants — per-tenant jobs, or listing tenants at all in shared
schema — needs a directory of tenant ids. It is resolved in precedence order:

1. a static `tenancy.tenants` list in config,
2. a `tenancy.registry.sql` query run against the `main` datasource (first column of each row),
3. the keys of `tenancy.datasources`.

Shared-schema deployments have no pools to enumerate, so they use the list or the registry query:

```yaml
tenancy:
  enabled: true
  mode: shared-schema
  registry:
    sql: select tenant_id from tenants order by tenant_id
```

## Per-tenant infrastructure

- **Migrations.** The app's `db/migration` set runs against `main` and, in the per-tenant modes,
  against every configured tenant pool, each with its own history — see
  [application layout](app-layout.md). Tenant pools are read from config at startup, so
  onboarding a tenant in a per-tenant mode is a config change.
- **Jobs.** `perTenant: true` runs a job once per tenant in the directory, each run on that
  tenant's own datasource (or the shared one, scoped by the `tenant.id` bind) as a separate
  execution record — see [jobs](jobs.md#per-tenant-jobs).
- **Framework tables.** The managed `tql_*` tables (preferences, shortcuts, inbox, workflow,
  delegations, route audit) live on the `main` datasource in every mode and discriminate by a
  `tenant_id` column, as do the default identity store's users and groups. Notification outbox
  writes from per-tenant job runs likewise land on `main`, where the dispatcher reads them.

## What tenancy is not

- **Not per-tenant:** named datasources, templates, messages, feature flags, and the rest of the
  application configuration are deployment-wide. One tenant cannot get different behavior, only
  different data.
- **Not self-service onboarding:** per-tenant pools come from configuration; there is no runtime
  API to create a tenant's database.
- **Not authorization:** tenancy never decides what a caller may do inside their tenant — that
  is route policy plus [data scoping](data-scoping.md).
- **Not automatic SQL rewriting:** in shared schema the tenant predicate is written by the
  author and checked by lint, never injected behind the query.

## Where to go next

- [Organizational data scoping](data-scoping.md) — row-level restriction within a tenant
- [Application layout](app-layout.md) — where migrations live and how they run per tenant pool
- [Jobs](jobs.md) — per-tenant batch fan-out
- [Deployment](deployment.md) — pool tuning, audit trail, and operating the runtime
