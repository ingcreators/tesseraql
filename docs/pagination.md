# Declarative pagination

Roadmap Phase 41: a `page:` block on a `query-json`/`query-html` route paginates the main
query — the framework appends the dialect's pagination clause at execution time, so the
authored 2-way SQL stays plain-tool runnable and carries no `LIMIT` of its own
(`TQL-YAML-1018` warns when it does).

## Offset strategy (default)

```yaml
sql:
  file: search.sql        # ends in ORDER BY <cols>, <pk> — a stable order, no LIMIT
page:
  size: 50                # rows per page
  maxSize: 200            # opt-in: the caller may pass ?size= up to this cap
  count: true             # opt-in: run a select count(*) wrapper for totals
```

The framework owns the `?page=` (1-based) and `?size=` request parameters — they are not
declared inputs (a bad value is a field-scoped `400`). One row beyond the page is fetched
to answer `hasNext` without a count. The `page` context entry carries
`number`/`size`/`hasNext`/`hasPrev` (+ `totalRows`/`totalPages` with `count: true`) for
response bodies (`meta: page`) and templates; the response automatically carries
`X-Total-Count` (when counting) and RFC 8288 `Link` `rel="next"`/`rel="prev"` headers. A
`view: list` on a paginated route renders the kit's `hc-pagination` nav, links preserving
the search and sort state ([docs/declarative-views.md](declarative-views.md)).

## Keyset strategy

```yaml
input:
  after: { type: integer, required: false }
sql:
  file: users.sql
  params:
    after: params.after
page:
  strategy: keyset
  by: id                  # the cursor column; TQL-YAML-1016 when missing
  size: 20
```

Keyset keeps the predicate in the SQL — SQL-first, plain-runnable:

```sql
select u.id, u.name from users u
where 1 = 1
/*%if after != null */
  and u.id > /* after */ 0
/*%end*/
order by u.id
```

The framework derives the next cursor from the last row's `by:` column (`page.next`), and
the `Link: <…?after=N>; rel="next"` header/`nextHref` follow. `count:` composes when a
total is worth its cost.

## Machine-checkable

`TQL-YAML-1015` (page on a non-query recipe), `1016` (keyset without `by:`/unknown
strategy), `1017` (size bounds), `1018` (authored LIMIT/FETCH warning); a `page` coverage
kind (`coverage.thresholds.page`) counts every paginated route a suite exercises; the
OpenAPI contract gains the `page`/`size`/`after` parameters. `tesseraql scaffold crud`
lists paginate this way out of the box (size 50, `maxSize` 200, counted).
