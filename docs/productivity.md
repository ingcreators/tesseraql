# Pins and recents

Business users keep coming back to the same places: a filtered list ("open requests over
5000"), a Studio data-browser query, a handful of records they touch daily. Pins and
recents are the framework's per-user answer — bookmarks no longer live only in the
browser, filters are not retyped. Both work on every shell page with zero app code, per
user, and a pin can never point off-site.

- **Pins** — user-curated, labelled, capped (20). Rendered as a **Pinned** group at the
  top of the sidebar on every shell page, and managed (relabel by re-pinning, remove) on
  the account page ([account surface](account.md)).
- **Recents** — an automatic, bounded ring (20) of **detail views** (`view: detail`
  renders — see [declarative views](declarative-views.md) — are the framework's honest
  definition of "a record"), deduped by URL and bumped on revisit. Listed on the account
  page; never in the sidebar (chrome stays calm).

There is no separate saved-filter feature, deliberately: **a saved filter is a pinned URL
whose query string rides along**. A pin is `{label, href}`; pinning
`/requests?state=open&min=5000` *is* saving the filter, and the same control works on
every shell page — list views, the Studio data browser, dashboards — with zero
per-surface code.

## The store

One managed table, one SPI — both lists are the same shape:

```sql
create table if not exists tql_user_shortcut (
  tenant_id  varchar(64)   not null,
  subject    varchar(255)  not null,
  kind       varchar(16)   not null,     -- pin | recent
  href_hash  varchar(64)   not null,     -- SHA-256 of href
  href       varchar(1000) not null,
  label      varchar(200)  not null,
  touched_at timestamp     not null,
  primary key (tenant_id, subject, kind, href_hash)
);
```

The primary key uses the href's SHA-256 rather than the raw href — a raw-href composite
key exceeds SQL Server's clustered index size cap.

`ShortcutStore` SPI in core (`list`, `put` (upsert-and-bump), `remove`, with the per-kind
cap enforced inside `put` by deleting the oldest beyond it), `JdbcShortcutStore` in
operations — Flyway-outside, `ensureSchema`-only, bound with the account surface (no
account surface, no pins, no chrome). The account-surface construction invariant applies
throughout: the subject is always the session principal's; nobody reads or writes another
user's shortcuts.

## The chrome

- The shell header (beside the bell) gains a **Pin / Unpin** toggle for the current page
  — a plain CSRF'd form carrying the current path+query and the page title as the
  default label. Present only with a browser session and the account surface. Submitting
  the toggle lands on the account page: redirect placeholders URL-encode their values,
  and redirecting to user input is best avoided anyway.
- The sidebar renders the **Pinned** group above the app's own navigation (menu.yml apps
  and framework navs alike) from the reserved `_shortcuts` variable — the `_account`
  pattern, published by the renderer from a short-TTL-cached read: a few seconds of
  staleness instead of a query per page render, the same trade the [inbox](inbox.md)
  badge count makes.
- The account page gains a **Pinned & recent** card: both lists, remove buttons, and the
  honest empty states.

## Recents

The renderer records a recent when a route renders a **`view: detail`** page (the
bounded, deduped ring above). The label is the view's title line — the document key the
detail pattern already renders. Rapid reloads of the same record dedupe inside the cache
TTL instead of writing a row per render.

## Safety notes (small surface, still explicit)

- A pin's `href` is stored and rendered as a **relative path only**: values not starting
  with `/`, starting with `//`, or using the backslash protocol-relative form are refused
  (`TQL-ACCOUNT-4802`) — a pin can never become an off-site redirect in the user's own
  sidebar.
- Labels are length-capped and rendered escaped, like every account-surface string.
- Recents record **URLs the user already saw**, per user, self-viewable only, in a ring
  that old entries fall out of; remove is one click. Only detail-view renders are
  tracked, and the only place they show is the user's own account page.

## Design notes

- Collapsing saved filters into pins keeps one concept where two were possible: the pin
  already carries the query string, so a filter needs no storage or UI of its own.
- The list pattern has no "Pin this view" button of its own: the header control already
  pins any page with its query, so a second button beside the search box would be
  redundant chrome — the collapse argument, applied once more.
