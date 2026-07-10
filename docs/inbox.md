# The in-app notification center — the shell's bell and inbox

Status: design accepted 2026-07-04 (roadmap Phase 49, Horizon 9). **Both slices are
delivered — Phase 49 is complete and milestone M14 is met**: the inbox channel type and
store (slice 1), the shell bell and `/_tesseraql/inbox` (slice 2).

Business applications tell their users things: your request was approved, a document
needs you, stock ran low. Phase 20 delivers those over mail and webhooks; Phase 48 gave
notifications a **recipient** and a per-user opt-out. What is still missing is the place
users actually expect these to land: an in-app inbox behind a bell in the shell. This
phase adds it as a third **channel type** — not a new pipeline — so everything the outbox
already guarantees (at-least-once retries, dead letters, the enqueue-time opt-out, the
`notification` coverage kind) applies to inbox messages unchanged.

## The channel type (slice 1)

```yaml
tesseraql:
  notifications:
    channels:
      approvals:
        type: inbox                      # beside mail and webhook
        title: "Request [(${payload.requestId})] was [(${payload.decision})]"
        body: "Decided by [(${payload.decidedBy})]."
        userOptOut: "true"               # optional, the Phase 48 marker
```

A `notify:` on an inbox channel **must** name its `recipient:` (the Phase 48 expression
resolving to a subject) — an inbox message without an addressee is meaningless, so a
missing one is a **lint error**, not a runtime surprise. The resolved recipient now rides
the outbox envelope (a new optional field; old in-flight envelopes decode with it absent),
and `NotificationSink` grows an `inbox` case beside mail and webhook that renders the
`title`/`body` templates against the payload (the mail channel's inline-template
mechanism) and inserts into the managed table.

## The store (slice 1)

`InboxStore` SPI in `tesseraql-core`, `JdbcInboxStore` in `tesseraql-operations`, over:

```sql
create table if not exists tql_user_notification (
  event_id   varchar(64)   not null,   -- the outbox event id: the dedupe key
  tenant_id  varchar(64)   not null,
  subject    varchar(255)  not null,
  channel    varchar(128)  not null,
  source     varchar(256)  not null,
  title      varchar(500)  not null,
  body       varchar(2000),
  created_at timestamp     not null,
  read_at    timestamp,
  primary key (event_id)
);
```

- **Dedupe by outbox event id**: at-least-once delivery means a crash between insert and
  acknowledge redelivers — the second insert hits the primary key and reads as
  already-delivered. No message ever doubles.
- **Retention**: delivery opportunistically prunes **read** messages older than
  `tesseraql.inbox.retentionDays` (default 90) — the session-store prune-on-create
  pattern; unread messages stay.
- The table lives outside the Flyway component set (the `tql_user_preference` pattern):
  `ensureSchema` is its only owner, so the slice-4 dual-ownership collision cannot recur.
- The store binds only when an inbox channel is declared — no channel, no table, no bell.

## The surface (slice 2)

- The shared shell grows a bell between the header slot and the user menu, rendered from
  a reserved **`_inbox`** variable (beside `_account`/`_theme`): a link to
  `/_tesseraql/inbox` carrying the unread count as an `hc-badge` when it is non-zero.
  The count is read through a short-TTL cache (the preference-store pattern) so the
  per-page cost is a map lookup, not a query.
- **`/_tesseraql/inbox`** joins the bundled account app (same mount, same kill switch):
  newest-first list — title, body, source, time, unread highlight — with per-message
  **Mark read** and a **Mark all read** action. Session-only, CSRF on writes, and the
  subject is always the session principal's: the page cannot read another user's inbox
  by construction, exactly like the preference store.
- Message bodies are plain text rendered escaped — a notification never carries markup
  into the page.

## What this deliberately reuses

| Concern | Answer |
| --- | --- |
| Delivery guarantees | The Phase 20 outbox: at-least-once, retries, dead letters |
| Addressing | The Phase 48 `recipient:` expression |
| Muting | The Phase 48 opt-out (`userOptOut: true` shows the toggle; the enqueue check silences) |
| Testing | The existing `notification` coverage kind and `notify:` suite targets |
| Chrome | The reserved-variable + bundled-app pattern (`_account`, `/_tesseraql/account`) |

## Error and lint surface

- Lint `TQL-YAML-1034`: a `notify:` on an inbox-type channel declares no `recipient:`.
- `TQL-ACCOUNT-4806`: marking a message that is not the caller's (or unknown) as read.
- Delivery failures throw, so the dispatcher's retry/dead-letter policy applies — except
  the duplicate-key case, which reads as already-delivered success.

Milestone **M14** closes the phase: a gallery app declares one inbox channel and one
`recipient:`-addressed `notify:`; the event shows up as an unread badge in the shell and
a message in the inbox, reading clears it, opting out silences it — zero app code beyond
those declarations.

## Live badge (hc 0.1.9 adoption)

The bell's badge is pushed, not polled: the shell subscribes it to the framework's
per-session event stream with the kit's `sse-updates` recipe on htmx's bundled `sse`
extension — the second consumer of the `SseRoutes` transport the copilot's streaming
replies introduced (docs/copilot.md, "The SSE transport").

- **`GET /_tesseraql/events`** — browser-session-authenticated SSE, mounted exactly when
  an inbox channel is configured (like the bell). One named event today, `inbox:badge`,
  whose payload is the badge fragment; idle `ping` frames double as heartbeats. Named
  events are the wire contract, so later live surfaces ride the same stream.
- **One choke point** — the runtime binds the inbox store wrapped in a notifying layer:
  outbox delivery, mark-read, and mark-all-read all signal the subject's open streams
  automatically. Signals coalesce per stream; the caching layer sits underneath, and a
  local mutation invalidates it before the signal fires, so the pushed count is fresh.
- **One markup source** — `InboxBadge.html` renders the fragment for both the page's
  initial `_inbox.badge` and the pushed payload; an all-read inbox pushes an empty
  payload, clearing the badge. A pushed update and a reload are byte-identical.
- **Bounded by construction** — subscriptions are capped per subject and globally (a new
  stream evicts the oldest), and each stream ends itself after a fixed lifetime; the
  browser's EventSource reconnects at the server-set `retry` delay, which also covers
  evictions. Initial state always renders server-side — the stream only freshens it, so
  no JavaScript means the Phase 49 behavior: the badge updates on the next page load.
