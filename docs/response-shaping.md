# Response shaping

Response shaping gives `query-json`/`query-html` routes three declarative tools —
computed fields, nested composition, and conditional statuses — so common shaping never
needs a template or an extra endpoint.

## Computed fields

Every leaf of `response.json.body` (and every `response.html.model` value) is an expression
in the [core expression language](declarative-validation.md#the-expression-language)
— a plain dotted path behaves exactly as before, and computed leaves come for free:

```yaml
response:
  json:
    body:
      rows: sql.rows
      total: params.qty * params.price
      label: upper(trim(params.name))
```

Expressions compile at build time; a leaf the grammar rejects is treated as a plain
dotted path, so existing bodies keep their behavior byte for byte.

## Nested composition (`nest:`)

A parent row set with a named child query composes into one document — grouped, not joined
by hand:

```yaml
sql:
  file: orders.sql          # parents
  params: { customerId: query.customerId }
queries:
  lines:
    file: lines.sql         # children
    params: { customerId: query.customerId }
response:
  json:
    body:
      orders: sql.rows
    nest:
      - into: orders        # the body key holding the parent rows
        children: lines     # the named query whose rows attach
        as: lines           # the field added to each parent
        on: { id: order_id }  # parentColumn: childColumn
```

The child is an ordinary named query: it runs with the route's own inputs — nothing binds
the parent rows' keys into it automatically — and the grouping happens in memory on the
`on:` columns after both queries return. Scope the child's SQL so it returns the children
of exactly the parents the main query returns; here both files filter on the same input:

```sql
-- lines.sql
select l.order_id, l.line_no, l.product_id, l.quantity
from order_lines l
join orders o on o.id = l.order_id
where o.customer_id = /* customerId */1
```

Join keys compare canonically (INTEGER 1 matches BIGINT 1); parents are copied, so shared
context rows are never mutated. `TQL-YAML-1019` keeps the references honest.

## Conditional statuses (`statusWhen:`)

Business conditions map to HTTP statuses declaratively — the generalization of
`expect.onMismatch`:

```yaml
response:
  json:
    body:
      data: sql.rows
    statusWhen:
      - when: sql.rowCount == 0
        status: 404
```

The first truthy arm wins (else the declared `status`). Works on `response.html` too;
conditions are pre-compiled at build (`TQL-YAML-1020`), and each arm's status rides into
the generated OpenAPI as a response entry.

## Default response headers

An HTML response's `headers:` map sends per-route headers (`HX-Trigger` toasts, cache hints).
The security header block every page sends identically — `Content-Security-Policy`,
`X-Content-Type-Options`, `X-Frame-Options`, `Referrer-Policy` — is declared once, app-wide:

```yaml
tesseraql:
  security:
    responseHeaders:
      Content-Security-Policy: "default-src 'self'; style-src 'self' 'unsafe-inline'; frame-ancestors 'none'"
      X-Content-Type-Options: nosniff
      X-Frame-Options: DENY
      Referrer-Policy: no-referrer
```

The compiler merges the defaults under every HTML response; the merge is per header name and the
route always wins. A route that must not send a default declares it with the literal value
`unset`, which removes the header entirely (and draws lint `TQL-SEC-4134`, so a suppressed
security header is owned, not accidental). Restating a default identically is flagged as leftover
copy-paste (`TQL-SEC-4133`); overriding one with a wildcard the default does not carry is flagged
as a broadening (`TQL-SEC-4134`). Hardening the whole app — tightening CSP, adding a new header —
becomes a one-line config edit instead of an edit per page.

Defaults apply to HTML responses (pages, fragments, and MCP UI resources). JSON, stream, and
generated-file responses do not carry a `headers:` map and are unaffected.

## Where to go next

- [pagination.md](pagination.md) — the `page` context entry maps into shaped bodies the
  same way (`meta: page`)
- [declarative-views.md](declarative-views.md) — declarative HTML rendering over the same
  route declarations

## HTTP caching

A query route can declare how clients and proxies may cache its response — stateless by
design: there is no server-side cache to invalidate and nothing to coordinate across nodes.

```yaml
cache:
  maxAge: 30s                 # Cache-Control: private, max-age=30 (private is the default)
  visibility: public          # public lints onto auth: public routes only
  staleWhileRevalidate: 60s   # optional
  etag: true                  # the default
```

- **`Cache-Control`** comes straight from the block; `private` is the default, and
  `visibility: public` is only legal on `auth: public` routes (`TQL-YAML-1025`) — an
  authenticated response is per-principal by definition.
- **`ETag`** (on by default) is a strong hash of the rendered body; a matching
  `If-None-Match` answers `304` with no body. The render already happened, so a 304 saves
  transfer, not compute — a stale validator on changed data simply gets fresh content with a
  new tag.
- `cache:` is a **query-recipe** key (`query-json`, `query-html`, `page`): a command's
  response must never come from a cache. Streaming responses (`query-export`) are not hashed.

