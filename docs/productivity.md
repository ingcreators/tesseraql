# Personal productivity — pins and recents

Status: design accepted 2026-07-04 (roadmap Phase 51, Horizon 9). **Both slices are
delivered — Phase 51 is complete and milestone M17 is met.** Refinements from
implementation, recorded honestly: the toggle lands on the account page (redirect
placeholders URL-encode their values, and redirect-to-user-input is best avoided
anyway); hrefs also refuse the backslash protocol-relative form; rows key on the
href's SHA-256 (the naive key blows SQL Server's clustered index cap); rapid reloads
of the same record dedupe inside the cache TTL instead of writing a row per render;
and the planned in-pattern "Pin this view" button was **dropped as redundant chrome**
— the header control already does exactly that on every page, which is the design's
own collapse argument applied once more.

Business users keep coming back to the same places: a filtered list ("open requests over
5000"), a Studio data-browser query, a handful of records they touch daily. TesseraQL has
no per-user answer — bookmarks live in the browser, filters are retyped. This phase adds
the smallest structure that carries all of it.

## One concept fewer than the candidate promised (deliberately)

The roadmap stub named three features: saved filters, favorite pages, recents. The design
collapses the first two: **a saved filter is a pinned URL whose query string rides
along**. A pin is `{label, href}`; pinning `/requests?state=open&min=5000` *is* saving
the filter, and the same control works on every shell page — list views, the Studio data
browser, dashboards — with zero per-surface code. What remains:

- **Pins** — user-curated, labelled, capped (20). Rendered as a **Pinned** group at the
  top of the sidebar on every shell page, and managed (relabel by re-pinning, remove) on
  the account page.
- **Recents** — an automatic, bounded ring (20) of **detail views** (`view: detail`
  renders are the framework's honest definition of "a record"), deduped by URL and
  bumped on revisit. Listed on the account page; never in the sidebar (chrome stays
  calm).

## The store (slice 1)

One managed table, one SPI — both lists are the same shape:

```sql
create table if not exists tql_user_shortcut (
  tenant_id  varchar(64)   not null,
  subject    varchar(255)  not null,
  kind       varchar(16)   not null,     -- pin | recent
  href       varchar(1000) not null,
  label      varchar(200)  not null,
  touched_at timestamp     not null,
  primary key (tenant_id, subject, kind, href)
);
```

`ShortcutStore` SPI in core (`list`, `put` (upsert-and-bump), `remove`, with the per-kind
cap enforced inside `put` by deleting the oldest beyond it), `JdbcShortcutStore` in
operations — Flyway-outside, `ensureSchema`-only, bound with the account surface (no
account surface, no pins, no chrome). The account-surface construction invariant applies
throughout: the subject is always the session principal's; nobody reads or writes another
user's shortcuts.

## The chrome (slice 1)

- The shell header (beside the bell) gains a **Pin / Unpin** toggle for the current page
  — a plain CSRF'd form carrying the current path+query and the page title as the
  default label. Present only with a browser session and the account surface.
- The sidebar renders the **Pinned** group above the app's own navigation (menu.yml apps
  and framework navs alike) from the reserved `_shortcuts` variable — the `_account`
  pattern, published by the renderer from a short-TTL-cached read (the badge-count
  trade-off, same numbers).
- The account page gains a **Pinned & recent** card: both lists, remove buttons, and the
  honest empty states.

## Safety notes (small surface, still explicit)

- A pin's `href` is stored and rendered as a **relative path only**: values not starting
  with `/` (or starting with `//`) are refused (`TQL-ACCOUNT-4802`) — a pin can never
  become an off-site redirect in the user's own sidebar.
- Labels are length-capped and rendered escaped, like every account-surface string.
- Recents record **URLs the user already saw**, per user, self-viewable only, in a ring
  that old entries fall out of; remove is one click. The cookbook says what is tracked
  and where it shows.

## Slice 2 — recents and the list-view hook

- The renderer records a recent when a route renders a **`view: detail`** page (the
  bounded, deduped ring above). Label: the view's title line — the document key the
  detail pattern already renders.
- ~~The list pattern gains its own Pin this view button~~ — dropped in implementation:
  the header control already pins any page with its query, so a second button beside
  the search box would be redundant chrome. The collapse argument, applied once more.

**Milestone M17** — a user pins a filtered list view and a Studio data-browser query and
both reappear in the sidebar of every page they open; the records they view collect in a
bounded recent list on the account page; removing and re-labelling work from there — per
user, zero app code, and a pin can never point off-site.
