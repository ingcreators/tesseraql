# Live views

A list that other users keep changing — an order queue, a task board, a stock level — can
refresh itself the moment a command commits, with two lines of YAML and no JavaScript of
your own. The command declares what changed; the view declares what it watches:

```yaml
# web/orders/approve/post.yml — the write
version: tesseraql/v1
id: orders.approve
kind: route
recipe: command-json
emit: orders.changed
# ...input, security, sql as usual
```

```yaml
# web/orders/orders.view.yml — the screen
version: tesseraql/v1
kind: view
view: list
title: Orders
refreshOn: orders.changed
```

When the command's transaction commits, every open page whose list declares
`refreshOn: orders.changed` re-fetches itself and swaps its table region in place. A
rolled-back command emits nothing.

## How it works — and what never leaves the server

The wire carries **topic names, never data**. A committed `emit:` pushes one named,
empty Server-Sent Event on `GET /_tesseraql/topics` (the same SSE transport as the
[inbox bell](inbox.md#live-badge)); the browser side is the bundled htmx `sse` extension,
which re-issues an ordinary `GET` of the page and swaps the view's refresh region in
place (a list's table region — the same one the search box refreshes — or a detail's or
dashboard's `#<view>-view` region). Because the refetch is a normal request, everything
that guards the route guards the refresh: authentication, policies,
[data scoping](data-scoping.md), tenancy. Two viewers with different row authority each
re-fetch their own view of the data.

- **Topics are tenant-scoped**: a commit in one tenant never signals another tenant's
  streams.
- **The stream is session-authenticated** (like `auth: browser` routes) and only serves
  topics some route actually declares; unknown requested topics simply never fire.
- **Bounded by construction**: subscriptions are capped per subject and globally (a new
  stream evicts the oldest, and the browser's EventSource reconnects), signals coalesce
  per topic, and idle `ping` frames keep intermediaries from severing quiet streams.
- **The refetch carries the live client state**: the typed search term and the current
  sort ride along, read from the DOM (the search box swaps the region without navigating,
  so the render-time URL can be stale) — and because the search box sits outside the
  swapped region, a live refresh never clobbers in-progress typing. A paginated list
  live-refreshes to its first page.
- **Graceful without JavaScript**: the page renders complete server-side; the stream only
  freshens it, so without the extension the list simply updates on the next reload.

## Semantics and limits

- `emit:` is a `command-json` key (a topic broadcast belongs to a committed write) and
  takes one topic or a list. Topic names are lowercase dot/dash-separated segments —
  `orders.changed`, `stock.low` — checked by lint (`TQL-YAML-1038`/`TQL-YAML-1039`).
- `refreshOn:` works on **list, detail, and dashboard** views — a list refreshes its
  table region, a detail its fields and children, a dashboard its whole panel grid. Forms
  are the deliberate exception (`TQL-VIEW-3311`): a live replacement would discard
  in-progress input. A topic no route emits is a lint warning (`TQL-VIEW-3312`), since
  that view would never refresh.
- Signals are **per-node and best-effort**, matching the framework's
  [per-node stance](deployment.md#safety-valves-and-multi-node-semantics): on a multi-node deployment, viewers
  connected to another node converge on their next reload. Live refresh is a freshness
  hint — reliable delivery is what the [outbox](notifications.md) is for.
- The signal fires after commit on the node that served the command; there is no queue
  and no replay. A page that was disconnected re-renders current data when it reconnects
  or reloads, which is always correct — the data never rode the stream.

## Testing

The emitting command is a write route: test it with a [write `sql` case](testing.md#testing-write-routes)
(the `emit:` itself needs no case — a topic with no declared emitter is already a lint
finding, and the stream carries no logic of its own).

## Further reading

- [declarative-views.md](declarative-views.md) — the list view `refreshOn:` hangs off.
- [inbox.md](inbox.md) — the framework's own live surface on the same transport.
- [deployment.md](deployment.md) — the per-node coordination stance this feature follows.
