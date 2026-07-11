# Studio copilot

An in-Studio chat panel that drives the framework's **existing gated loop** as tools.
Describe → the copilot reads routes, sources, lint and schema, previews
buffers — and, only when you hold an edit role, saves **drafts**. Nothing it does is served
until you review and apply the draft in the editor's diff-confirm UI, and every
copilot-saved draft lands in the audit trail under your name.

## Setup

TesseraQL ships **no model** and stores **no key in app source**. The operator points the
panel at any OpenAI-compatible chat-completions endpoint:

```yaml
tesseraql:
  copilot:
    enabled: true
    endpoint: https://api.example.com/v1/chat/completions   # vLLM, Ollama, a gateway, ...
    model: your-model-name
    apiKey: ${secret.env.COPILOT_API_KEY}    # resolves at call time via the secret provider
    maxTurns: 6                              # bounded tool loop per message
```

The key is read lazily per request through the config placeholder chain, so a
`${secret.*}` reference never resolves at startup and never lands in a file. Without
`enabled: true` the panel renders a setup hint and the chat endpoints (send, stream,
reset) answer `TQL-STUDIO-4235`.

**What leaves the server.** Every turn POSTs the whole conversation to the configured
endpoint: a system prompt naming the app, your messages, the model's replies, and the
result of every tool the model called — which can include entire source files it read
(route YAML, 2-way SQL, templates), the route inventory, lint findings, and introspected
table and column names. No table data is sent — the schema tool returns names only —
and the API key rides as a bearer header. The
[outbound egress allow-list](connectors.md) does **not** apply here: configuring
`tesseraql.copilot.endpoint` is itself the authorization, so point it only at an
endpoint you trust with your app's source.

## What the copilot may do

| Tool | Gate |
| --- | --- |
| `list_routes`, `read_source`, `lint`, `schema_tables`, `preview_draft` | Studio browser session |
| `save_draft` | your Studio **edit role** — offered to the model only when you hold one |
| apply | **never** — a human applies every draft in the editor |

The `studio_copilot` MCP prompt ([MCP dev tools](ai-mcp.md)) remains the way to drive the
same loop from an external agent; the panel is the zero-setup, in-Studio path.

## Streaming replies

The panel streams the assistant's reply as it is produced instead of blocking on the whole
tool loop. It adopts two Hypermedia Components kit recipes (hc 0.1.9) verbatim — `chat-messages` (the transcript is an
`hc-chat` log whose composer posts over htmx) and `streaming-response` (the assistant
placeholder owns its own SSE connection) — on htmx's bundled `sse` extension. No new
dependency: the extension ships inside the htmx WebJar and is served version-less like
every other vendor asset.

### The turn contract

- **POST `…/copilot/send`** (htmx): registers a **single-use pending turn** — actor-bound,
  UUID id, short TTL — and answers `200` with the chat-messages fragments: the user
  message item, an assistant placeholder (`aria-busy="true"`, `sse-connect` to the stream
  URL), and an out-of-band composer re-render that clears the input. The model has not
  been called yet.
- **GET `…/copilot/stream?turn=<id>`**: authenticates the browser session, consumes the
  turn (an expired, foreign, or already-used id is a 404), and runs the same bounded tool
  loop — now streaming. Content deltas arrive as `chunk` events (server-escaped HTML
  text), each tool execution emits a small marker chunk, and the turn ends with `done`
  carrying the complete final message markup (or `error`). Swapping the placeholder away
  closes the EventSource; there is no reconnect.
- **No JS**: the same send URL without `HX-Request` runs the blocking loop and answers
  `303` back to the page — exactly the pre-streaming behavior, now the fallback.

### The SSE transport

How streaming behaves, in short: the reply rides server-sent events over the framework's
shared streaming transport — the same one that pushes the [inbox](inbox.md) bell badge.
The stream authenticates the browser session exactly like `auth: browser` routes, and a
refusal before the stream opens renders the framework's normal JSON error envelope with
its mapped status. Upstream, the chat-completions call streams too; an endpoint that
cannot stream surfaces as the stream's `error` event. Without JavaScript the same send
URL runs the blocking loop and answers `303` back to the page, so the panel works —
rendered identically — with no stream at all.

### Security

- The stream is a GET riding the session cookie — no CSRF needed; the turn id is the
  capability: UUID, single-use, actor-bound, short TTL.
- Every chunk is server-escaped before framing; the client appends, never interprets.
- CSP unchanged: EventSource connects same-origin under `default-src 'self'`.
