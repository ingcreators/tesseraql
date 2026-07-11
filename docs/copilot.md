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
`enabled: true` the panel renders a setup hint and the endpoint refuses.

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

### Why these are Java routes

Streaming and `HX-Request` content negotiation are transport concerns the Simple-YAML
surface deliberately does not model — the same boundary that keeps login, logout, assets,
health, and MCP in Java route builders. `send` and `stream` live in `CopilotRouteBuilder`
(mounted with Studio; an unconfigured copilot still refuses with TQL-STUDIO-4235); the
page GET and reset stay YAML app routes.

### The SSE transport

The framework's streaming surface, built to be reused (the [inbox](inbox.md) bell rides it
too): `SseRoutes` registers each stream as a **raw route on the platform's Vert.x
router**, not a Camel route. A Camel exchange answers with a complete body, and the
platform-http `InputStream` pump (`AsyncInputStream`) only delivers full 8 KB buffers —
an SSE frame must reach the wire the moment it is written, so streams bypass the exchange
body entirely and write to the response directly. Per connection: the browser session
authenticates exactly like `auth: browser` routes, a pre-stream refusal renders the
framework's JSON error envelope with its mapped status, the producer runs on a virtual
thread with every frame write hopping to the connection's event loop, and a client
disconnect fails the next write, which ends the producer. `data:` payloads are
single-line by construction (newlines become markup before framing).

### One source for the transcript markup

The per-entry `hc-chat` markup is built once in Java (`CopilotFragments`) and used by
both surfaces: the SSE `done` event carries it, and the page view model carries it
pre-rendered per entry (the template inserts it with `th:utext`). A reload after a
streamed turn and the streamed turn itself render identically — no template/Java drift
to guard.

### Upstream

The chat-completions call gains `stream: true`; the JDK HttpClient reads the upstream SSE
line stream, forwards content deltas, and assembles fragmented `tool_calls` deltas by
index into the same message shape the buffered call returned — history, trimming, and the
tool loop are unchanged. An upstream that cannot stream surfaces as the `error` event;
the panel stays usable through the no-JS path.

### Security

- The stream is a GET riding the session cookie — no CSRF needed; the turn id is the
  capability: UUID, single-use, actor-bound, short TTL.
- Every chunk is server-escaped before framing; the client appends, never interprets.
- CSP unchanged: EventSource connects same-origin under `default-src 'self'`.
