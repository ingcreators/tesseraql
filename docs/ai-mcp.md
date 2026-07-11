# AI-assisted development (MCP)

TesseraQL's artifacts are declarative and machine-checkable — lint, declarative tests,
coverage kinds, deterministic generated output — which is exactly the feedback loop a coding
agent needs. `tesseraql mcp` turns that loop into a [Model Context
Protocol](https://modelcontextprotocol.io) server: an agent connected only over MCP can
scaffold a table-backed route and iterate until lint, tests, and coverage pass, without any
direct filesystem access.

Every tool reuses the same service the CLI and Maven plugin use, so a tool call behaves
exactly like running the command by hand — same generation, same lint rules, same coverage
gate.

> **Building MCP features into your app?** This page covers the development-time tooling.
> For the tools, resources, UI, and prompts an application itself declares under `mcp/` and
> serves at runtime, see the [application MCP surface](app-mcp.md).

## Running the server

Two transports. **stdio** is the default — an agent launches the server as a subprocess and
talks newline-delimited JSON-RPC over its stdin/stdout:

```bash
tesseraql mcp --app .
```

A typical client registration (for example a `.mcp.json` an IDE or agent reads):

```json
{
  "mcpServers": {
    "tesseraql": {
      "command": "tesseraql",
      "args": ["mcp", "--app", "/path/to/app"]
    }
  }
}
```

In VS Code, the *TesseraQL: Register MCP Server* command writes this registration for you —
into `.vscode/mcp.json` and/or the repo-root `.mcp.json`, merging with any existing servers
(see the [VS Code extension](vscode-extension.md)).

**HTTP** serves a Streamable HTTP endpoint at `/mcp` for a shared development server, so a
remote agent or IDE connects over the network:

```bash
tesseraql mcp --app . --transport http --port 8765
```

The server prints the URL and serves until interrupted. Point the client at
`http://host:8765/mcp`; `initialize` mints an `Mcp-Session-Id` the client echoes on later
requests.

> stdout carries protocol frames only in stdio mode — the server redirects all logging to
> stderr. Do not write to stdout from hooks or wrappers around it.

## Security

The write tools change source files, so the HTTP transport must not be exposed unguarded.

- **Authentication reuses the app's own bearer tokens.** When the app configures
  `tesseraql.security.jwt.secret`, every HTTP request must carry a valid
  `Authorization: Bearer <jwt>` (the same HS256 verification the app's routes use); a missing
  or invalid token gets `401`. There is no second credential system to manage.
- **Loopback by default.** `--bind` defaults to `127.0.0.1`. The server refuses to bind a
  non-loopback address without authentication unless you pass `--insecure` (and warns when it
  runs without auth at all).
- **`--read-only`** drops the write tools entirely (scaffold and drafts), leaving only the
  read tools — safe to expose for inspection on a shared host.

The stdio transport inherits the trust of the process that launched it; it has no separate
auth.

## Tools

### Read

| Tool | What it returns |
| --- | --- |
| `manifest_summary` | app name, home, reproducibility hash, every discovered route and job |
| `source_read` | one source file (YAML/SQL/template) by app-relative path, path-confined |
| `schema_introspect` | a table's columns, primary key, version column, and unique indexes via JDBC metadata |
| `lint` | every lint finding (code, severity, source, message) with error/warning counts |
| `test` | runs the declarative suites ([testing](testing.md)), returns pass/fail per case plus SQL and item coverage and the coverage-gate result |
| `ops_status` | outbox counts and recent events, and recent batch job executions (a note when the ops schema is absent) |

### Write (gated; omitted under `--read-only`)

| Tool | What it does |
| --- | --- |
| `scaffold_crud` | scaffolds a table's list/detail/edit routes, 2-way SQL, htmx pages, and tests — idempotent, hand-edits skipped unless `force` |
| `draft_save` | saves a draft edit under `work/studio/drafts` without touching the source of truth |
| `draft_preview` | compiles a draft (parse route YAML, render SQL, process templates) without applying it |
| `draft_apply` | promotes a saved draft to the source of truth — only if it compiles |

`schema_introspect`, `scaffold_crud`, `test`, and `ops_status` use the app's configured main
datasource unless the call supplies `jdbcUrl` / `username` / `password`. Writes go through the
same machinery as the CLI: `scaffold_crud` through the checksum-aware writer, and the
draft tools through [Studio's](app-layout.md) draft/apply mechanism — both confined to the
app home, so a path that escapes it is rejected.

## The iterate loop

The acceptance scenario, entirely over MCP:

1. `manifest_summary` — see what exists.
2. `schema_introspect { "table": "orders" }` — read the table's shape.
3. `scaffold_crud { "table": "orders" }` — generate the CRUD slice.
4. `lint` — fix anything with severity `error`.
5. `test` — iterate until `passed` is true and `coverage.gatePassed` holds.

For hand edits between regenerations, the agent uses `draft_save` → `draft_preview` →
`draft_apply`: a draft only lands once it compiles, so a broken edit never reaches the source
of truth.

## The Studio copilot prompt

The dev-tool server offers one MCP **prompt**, `studio_copilot` (in write mode only) — the
guided "describe → draft → preview → apply" loop a client surfaces as a slash command. Given a
plain-language `task` (and an optional backing `table`), `prompts/get` returns guidance that
steers the connecting agent's model through the tools above: orient with `manifest_summary` /
`source_read`, draft with `scaffold_crud` or `draft_save`, verify with `draft_preview` / `lint`
/ `test`, and only then `draft_apply`.

This is "describe" without an in-app model: TesseraQL ships the workflow, the agent's own model
does the natural-language reasoning, and every step is a separately-gated tool call — so the
copilot adds no LLM dependency, no API key, and no new privilege. The MCP loop, not an
embedded model, is TesseraQL's AI surface. (For the in-Studio chat panel that drives the
same loop, see the [Studio copilot](copilot.md).)

## A reusable protocol core

The protocol machinery lives in `tesseraql-mcp`, deliberately free of any dev-tool coupling:
`McpServer` (JSON-RPC dispatch over `tools`, `resources`, and `prompts`), the `McpTool` model (a
name, a JSON-Schema input, and a handler returning text or structured content), the `McpPrompt`
model (a name, declared arguments, and a handler rendering messages), and the transports
(`StdioTransport`, and a server-agnostic `McpHttpHandler` with a JDK-server binding). Each
surface is advertised in `initialize` only when the server registers some. The dev-tool server
is one consumer; the [application MCP surface](app-mcp.md) is the second — the runtime drives
the same `McpHttpHandler` from a Camel route to serve the tools, resources, and prompts an app
declares.

## Error codes

Runtime errors:

| Code | Meaning |
| --- | --- |
| `TQL-MCP-4002` | a dev-tool call is missing a required argument |
| `TQL-MCP-5001` | no datasource: the call gave no `jdbcUrl` and the app declares no main datasource |

Tool failures (a bad argument, a missing datasource, a draft that does not compile) come back
as an MCP tool result with `isError: true` and the message — the connection stays up so the
agent can read the error and correct course. Protocol-level mistakes (unknown method,
malformed JSON-RPC) use the standard JSON-RPC error codes. The lint codes for an
application's own MCP documents are listed on the [application MCP
surface](app-mcp.md) page.
