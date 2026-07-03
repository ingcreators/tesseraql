# Studio copilot

An in-Studio chat panel that drives the framework's **existing gated loop** as tools
(roadmap Phase 44). Describe → the copilot reads routes, sources, lint and schema, previews
buffers — and, only when you hold an edit role, saves **drafts**. Nothing it does is served
until you review and apply the draft in the editor's diff-confirm UI, and every
copilot-saved draft lands in the audit trail under your name.

## Setup (decision point 8)

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

The `studio_copilot` MCP prompt (Phase 24) remains the way to drive the same loop from an
external agent; the panel is the zero-setup, in-Studio path.
