# AI-Assisted Development (MCP)

TesseraQL's artifacts are declarative and machine-checkable — lint, declarative tests,
coverage kinds, deterministic generated output — which is exactly the feedback loop a coding
agent needs. `tesseraql mcp` turns that loop into a [Model Context
Protocol](https://modelcontextprotocol.io) server: an agent connected only over MCP can
scaffold a table-backed route and iterate until lint, tests, and coverage pass, without any
direct filesystem access (roadmap Phase 24).

Every tool reuses the same service the CLI and Maven plugin use, so a tool call behaves
exactly like running the command by hand — same generation, same lint rules, same coverage
gate.

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
| `test` | runs the declarative suites, returns pass/fail per case plus SQL and item coverage and the coverage-gate result |
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
same machinery as the CLI: `scaffold_crud` through the checksum-aware writer (design ch.
22.20), and the draft tools through [Studio's](app-layout.md) draft/apply mechanism — both
confined to the app home (design ch. 20.2), so a path that escapes it is rejected.

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

## A reusable protocol core

The protocol machinery lives in `tesseraql-mcp`, deliberately free of any dev-tool coupling:
`McpServer` (JSON-RPC dispatch), the `McpTool` model (a name, a JSON-Schema input, and a
handler returning text or structured content), and the transports (`StdioTransport`, and a
server-agnostic `McpHttpHandler` with a JDK-server binding). The dev-tool server is one
consumer; the application MCP endpoints below are the second — the runtime drives the same
`McpHttpHandler` from a Camel route.

## Application MCP endpoints

The same core lets a TesseraQL **application** declare its own MCP tools, so the running
business application is AI-enabled — the way it already declares HTTP routes. A tool is a
`query-json` or `command-json` definition placed under `mcp/` instead of `web/`: same recipe,
input constraints, 2-way SQL, and security as a route — only the entry point differs.

```yaml
# mcp/find-orders.yml
version: tesseraql/v1
id: find-orders
kind: tool
recipe: query-json
description: Find orders for a customer, newest first. Use when asked about a customer's orders.

input:
  customerId:
    type: integer
    required: true
  limit:
    type: integer
    default: 20
    min: 1
    max: 100

security:
  auth: bearer
  policy: orders.read

sql:
  file: find-orders.sql
  mode: query
  params:
    customerId: query.customerId
    limit: query.limit
```

The runtime serves every declared tool over the Streamable HTTP transport at
`/_tesseraql/mcp` (the same protocol the dev-tool HTTP transport speaks). On startup the
compiler turns each tool into an internal route running the full pipeline — telemetry, the
tool's own authentication and authorization, input validation, the SQL or transactional
command — and the MCP endpoint dispatches a `tools/call` to it. So:

- **Security is per-tool and identical to a route.** The MCP request's `Authorization: Bearer`
  rides into the tool's route, where its declared `auth`/`policy` run. A tool with no security
  is public; a tool with a policy enforces it; an unauthorized call comes back as an MCP tool
  error. Discovery (`tools/list`) is open so a client can see what the app offers.
- **The input schema is derived** from the route's `input:` constraints (types, required,
  ranges, enums), so the model is guided toward valid arguments; validation still runs
  server-side.
- **The result** is the SQL/command result as JSON (`{ "rows": [...], "rowCount": n }` for a
  query), or a custom shape if the tool declares a `response: { json: ... }` block.
- **Governance, lint, and coverage extend to tools.** A write (command) tool must declare an
  authorization policy or lint fails (`TQL-MCP-4030`, deny-by-default — an agent must not
  mutate data unauthorized); the governance gate scores and gates tools like routes (a
  write tool reachable without authentication is `advanced` and needs approval); and an `mcp`
  coverage kind tracks which tools your declarative suites exercise.

Set `tesseraql.mcp.enabled: false` to stop serving the endpoint. Resources and MCP Apps UI
are not modeled yet — see the roadmap.

## Error codes

| Code | Meaning |
| --- | --- |
| `TQL-MCP-4002` | a dev-tool call is missing a required argument |
| `TQL-MCP-5001` | no datasource: the call gave no `jdbcUrl` and the app declares no main datasource |
| `TQL-MCP-1001` | (lint) an application MCP tool uses a recipe other than `query-json` / `command-json` |
| `TQL-MCP-1002` | (lint, warning) an application MCP tool has no `description` |
| `TQL-MCP-4030` | (lint) a write MCP tool declares no authorization policy |

Tool failures (a bad argument, a missing datasource, a draft that does not compile) come back
as an MCP tool result with `isError: true` and the message — the connection stays up so the
agent can read the error and correct course. Protocol-level mistakes (unknown method,
malformed JSON-RPC) use the standard JSON-RPC error codes.
