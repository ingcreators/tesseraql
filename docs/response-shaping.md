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
      rows: main.rows
      total: params.qty * params.price
      label: upper(trim(params.name))
```

Expressions compile at build time; a leaf the grammar rejects is treated as a plain
dotted path, so existing bodies keep their behavior byte for byte.

## Nested composition (`nest:`)

A parent row set with a named child query composes into one document — grouped, not joined
by hand:

```yaml
sources:
  main:
    sql:
      file: orders.sql          # parents
      params: { customerId: query.customerId }
  lines:
    sql:
      file: lines.sql         # children
      params: { customerId: query.customerId }
response:
  json:
    body:
      orders: main.rows
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

### Merging a reference (`merge:`)

`as:` attaches the matching rows as a list, which is the shape of a one-to-many child. A
many-to-one reference — the partner name behind an order's partner code — wants the opposite:
columns on the parent row itself. `merge:` names the columns to copy:

```yaml
sources:
  partners:
    sql:
      file: partners.sql
response:
  json:
    body:
      orders: main.rows
    nest:
      - into: orders
        children: partners
        on: { partner_code: code }
        merge: [partner_name]     # instead of as:
```

Each entry declares exactly one of `as:` or `merge:`. A parent that matches nothing still
carries the merged columns, as nulls, so a client reads one row shape rather than two; a
parent that matches more than one row fails with `TQL-CAMEL-3113` rather than picking one.

### Composite join keys

`on:` takes one entry per key column, so a reference keyed by a pair joins on the pair:

```yaml
      - into: orders
        children: partners
        on: { buyer_code: buyer, supplier_code: supplier }
        merge: [partner_name]
```

Every column must match for the rows to compose.

## Enrichment (`enrich:`)

`nest:` composes the response *body*, so it can only serve a JSON response and only from
results the route already fetched with the request's own inputs. A reference keyed by the rows
themselves — the partner name behind each order's partner code, where the partner master is
another system's table — needs the keys to reach the query. That is `enrich:`, and because it
composes the *result set* rather than the body, an HTML list's `columns:` sees the merged
column too:

```yaml
sources:
  main:
    sql:
      file: orders.sql
    enrich:
      partner:
        on: { partner_code: code }    # rowColumn: referenceColumn
        sql: { file: partners.sql, datasource: crm }
        merge: [partner_name]         # or as: partners, to attach a list
```

```sql
-- partners.sql — the distinct keys arrive as `keys`
select code, name as partner_name
from partners
where code in /* keys */('P1', 'P2')
```

**A small master that barely changes is not this.** Twenty payment methods are held whole and
resolved from memory by a [code catalog](code-catalogs.md), with no query per request at all.
`enrich:` is for the reference too large to hold — the choice is size, not key arity.

**By key, not by row.** The distinct keys of the rows being enriched are collected and the
reference is fetched in batches — one statement per `batchSize` keys, not one per row. A
hundred-row page over sixty distinct partners costs one round trip.

- **An enrichment nests under the source it transforms**, so a detail page's history list is
  enriched by writing `enrich:` under `history:` — any source, whatever arm it names, including
  an `http:` one.
- **`batchSize:`** is how many keys ride one statement. The default comes from the dialect and
  the key's arity — under Oracle's 1000-expression `IN` limit and SQL Server's 2100
  parameters — so a large key set becomes several statements whose results merge by key.
- **`maxKeys:`** (default 10000) bounds the whole fan-out: past it the request fails
  (`TQL-SQL-2114`) rather than quietly issuing an unbounded number of round trips.
- The reference SQL must bind `keys`, or the build fails (`TQL-YAML-1048`) — a query that
  ignores them returns the right answer while reading the whole table once per batch.

A **composite key** takes one `on:` entry per column. The keys then arrive as rows named by
the *reference's* columns, so the file reads in its own vocabulary:

```yaml
    on: { buyer_code: buyer, partner_code: supplier }
```

```sql
where
/*%for k : keys separator ' or ' */
  (buyer = /* k.buyer */'B1' and supplier = /* k.supplier */'P1')
/*%end*/
```

A row-value `IN` is deliberately not generated: SQL Server does not accept one, and the same
2-way SQL file has to run on every dialect and in a plain SQL tool.

`merge:`/`as:` mean exactly what they mean on `nest:`, including the many-to-one rule. What
enrichment does **not** buy is sorting, searching, or paginating by the merged column: only
SQL can order a result set, so a screen that must sort by partner name still joins.

An **export** enriches too, and it does so a window at a time: `batchSize` rows are read, the
reference is fetched once for their distinct keys, and the enriched window is written. So a
million-row extract makes one reference query per window and never holds more rows than it
already did — including a streaming format, which reads straight through.

### An HTTP reference

The reference may be a call instead of a query — the same call vocabulary
[connectors](connectors.md) documents, plus `select:` and `onError:`. How the keys reach it is
`mode:`:

```yaml
enrich:
  partner:                              # perRow (the default): one request per distinct key
    on: { partner_code: code }
    http: { url: https://crm/partners/{key.code}, credential: crm }
    merge: [name]

  rating:                               # batch: one request per batchSize keys
    on: { partner_code: code }
    mode: batch
    http:
      method: POST
      url: https://crm/ratings/search
      body: keys                        # the distinct key set, as JSON
      select: matches
    merge: [rating]
```

- **`perRow`** suits a resource keyed per URL. `{key.<column>}` placeholders in the url take
  that key's values, **percent-encoded per path segment**, so a key carrying `/` or a space
  cannot address a different resource. The response needs no key of its own: the answer belongs
  to the key that was asked for.
- **`batch`** suits an endpoint that accepts a list. The keys bind as `keys` exactly as they do
  for a SQL reference, `batchSize` splits the set, and — because one response answers many keys
  — its rows **must carry the reference columns**, which `on:` matches on.
- A reference that mentions neither `keys` nor `key.<column>` anywhere sends the identical
  request every time and is refused at build (`TQL-YAML-1048`).
- **`onError: empty`** degrades the *whole* enrichment: no key is merged, never the batches that
  happened to succeed. A list where some rows carry a name and some do not reads as a data
  problem and gets reported as one. Degrading is logged and counted
  (`tesseraql.enrich.degraded`).

## Conditional statuses (`statusWhen:`)

Business conditions map to HTTP statuses declaratively — the generalization of
`expect.onMismatch`:

```yaml
response:
  json:
    body:
      data: main.rows
    statusWhen:
      - when: main.rowCount == 0
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

## Next

- [declarative-views.md](declarative-views.md) — shaping an HTML response instead.
- [app-mcp.md](app-mcp.md) — the same routes as agent tools.
