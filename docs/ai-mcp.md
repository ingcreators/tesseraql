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
is one consumer; the application MCP endpoints below are the second — the runtime drives the same
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

Set `tesseraql.mcp.enabled: false` to stop serving the endpoint (tools, resources, and UI
resources alike).

## Application MCP resources

Alongside its tools, an application declares read-only **resources** — context an agent attaches,
the way a person pastes a document into a chat. A resource is a `query-json` definition placed
under `mcp/` with `kind: resource`: it is addressed by a stable `uri` instead of a name, takes no
arguments (its uri is the whole address), and runs the same read pipeline a `query-json` route
runs.

```yaml
# mcp/active-users.yml
version: tesseraql/v1
id: active-users
kind: resource
recipe: query-json
uri: tesseraql://users/active
mimeType: application/json
description: Active users (id, name). Attach for user-directory context.

security:
  auth: bearer
  policy: users.read

sql:
  file: active-users.sql
  mode: query
```

The runtime serves every declared resource over the same `/_tesseraql/mcp` endpoint as the tools.
On startup the compiler turns each resource into a read-only internal route — telemetry, the
resource's own authentication and authorization, tenancy and locale resolution, the 2-way SQL — and
the MCP endpoint answers `resources/list` and `resources/read` from it. So:

- **Discovery and read.** `resources/list` advertises every resource (`uri`, `name`, `mimeType`,
  `description`); `resources/read { "uri": ... }` runs the SQL and returns the JSON result as the
  resource's `contents`, tagged with its `uri` and `mimeType`. `resources/templates/list` is empty
  (no URI-templated resources are modeled).
- **Security is per-resource and identical to a route.** The request's `Authorization: Bearer`
  rides into the resource's route, where its declared `auth`/`policy` run. Discovery is open;
  reading an unauthorized resource comes back as a `resources/read` JSON-RPC error (the connection
  stays up, so the agent can read the message).
- **Read-only by construction.** Lint rejects a resource that is not `query-json` with query-mode
  SQL (`TQL-MCP-1003`), that declares no `uri` (`TQL-MCP-1004`) or any `input:` (`TQL-MCP-1006`),
  and fails fast on a duplicate uri (`TQL-MCP-1007`); a missing `description` is a warning
  (`TQL-MCP-1005`). The governance gate scores a resource like a read route (never `advanced`,
  since it cannot write), and an `mcp-resource` coverage kind tracks which resources your
  declarative suites exercise.

## Application MCP Apps UI

A tool can hand back interactive UI instead of only JSON — the [MCP Apps
extension](https://modelcontextprotocol.io/community/seps/1865-mcp-apps-interactive-user-interfaces-for-mcp)
(SEP-1865). TesseraQL's Hypermedia Components (`hc-*` markup) and htmx are the natural renderer,
so the UI is a server-rendered fragment, not a client-side template: an application declares a
**UI resource** as a `kind: ui` document under `mcp/` — a `query-html` (or `page`) definition,
addressed by a stable `ui://` uri — and a `kind: tool` document references it with a `ui:` field.

```yaml
# mcp/orders-board.yml
version: tesseraql/v1
id: orders-board
kind: ui
recipe: query-html
uri: ui://orders/board
description: A board of open orders, rendered as a Hypermedia Components fragment.

security:
  auth: bearer
  policy: orders.read

sql:
  file: orders-board.sql
  mode: query

response:
  html:
    template: orders-board.html
    model:
      orders: sql.rows

ui:
  prefersBorder: true
  csp:
    connectDomains: ["'self'"]
```

```yaml
# mcp/find-orders.yml — the tool links to the UI resource it renders into
ui: ui://orders/board
```

On startup the compiler turns each UI resource into a read-only internal route that runs the same
read-and-render pipeline a `query-html` route runs — telemetry, the resource's own authentication
and authorization, tenancy and locale resolution, the 2-way SQL, then the Thymeleaf template — so
it renders the same `hc-*` fragment a page would (UI work follows the blessed patterns in
[docs/hypermedia-ui.md](hypermedia-ui.md); any gap belongs upstream in the kit, not in app CSS).
The runtime serves it over the same `/_tesseraql/mcp` endpoint as the tools and resources. So:

- **The extension is negotiated.** When the app serves any UI resource, `initialize` advertises it
  under `capabilities.extensions["io.modelcontextprotocol/ui"]` with `text/html;profile=mcp-app`,
  the MCP Apps content type.
- **Discovery and read mirror resources.** `resources/list` advertises every UI resource (its
  `ui://` uri, `name`, the `text/html;profile=mcp-app` mimeType, `description`, and `_meta.ui`
  rendering hints — `prefersBorder`, content-security-policy domains); `resources/read { "uri": ... }`
  runs the route and returns the rendered `hc-*` fragment as the resource's `contents`.
- **Tools link to a UI resource.** A tool's `ui:` field is advertised as its
  `_meta.ui.resourceUri`, so a host renders the linked fragment to present the tool's result
  instead of showing the raw JSON.
- **Security is per-resource and identical to a route.** The request's `Authorization: Bearer`
  rides into the UI resource's route, where its declared `auth`/`policy` run; an unauthorized read
  comes back as a `resources/read` JSON-RPC error. Discovery is open.
- **Read-only by construction, governed like a read.** Lint keeps a UI resource HTML-rendering and
  uri-addressed (`TQL-MCP-1008`/`1009`/`1011`), warns on a missing description (`TQL-MCP-1010`),
  and rejects a tool whose `ui:` link dangles (`TQL-MCP-1012`); the governance gate scores a UI
  resource like a read route (never `advanced`, since it cannot write); and an `mcp-ui` coverage
  kind tracks which UI resources your declarative suites exercise.

## Application MCP prompts

An app can also declare an MCP **prompt** — a parameterized, reusable message template the
connecting agent surfaces to its model (an IDE slash command, say), the third MCP primitive
alongside tools and resources:

```yaml
# mcp/draft-welcome.yml
version: tesseraql/v1
id: draft-welcome
kind: prompt
description: Draft a welcome message for a new user.

input:
  name:
    type: string
    required: true
    description: The new user's name.
  tone:
    type: string
    required: false

template: draft-welcome.txt.tpl
```

```text
# mcp/draft-welcome.txt.tpl  (Thymeleaf TEXT mode)
Write a [(${tone})] welcome message for [(${name})].
```

- **Pure text, no SQL.** Unlike a tool or resource, a prompt is not compiled to a route and runs no
  query — `prompts/get` renders the colocated `template` (Thymeleaf TEXT mode) against the supplied
  argument values and returns it as one `user` message. So a prompt has no recipe and no per-prompt
  security beyond the endpoint's own auth. The declared `input:` becomes the prompt's arguments
  (name, optional description, required flag).
- **Advertised like the rest.** The runtime serves prompts at the same `/_tesseraql/mcp` endpoint;
  `prompts/list` enumerates them and the `prompts` capability is negotiated in `initialize` when an
  app declares any. This is the application-side counterpart of the dev-tool `studio_copilot` prompt
  — TesseraQL ships the workflow, the agent's own model does the reasoning, no embedded LLM.

## Mounted-app tools

A TesseraQL runtime hosts the main app and any mounted or bundled system apps —
the ops console, Studio, IAM admin, and apps listed under `tesseraql.apps.<name>`. Each is a plain
YAML/SQL/template tree compiled by the same route compiler, so each may declare its own MCP tools,
resources, and UI resources under `mcp/`. The runtime serves them all from the one
`/_tesseraql/mcp` endpoint, so an agent sees one catalog spanning every hosted app:

- **One endpoint, every app.** `tools/list` and `resources/list` advertise the tools, resources, and
  UI resources of the main app and every mounted app together; the MCP Apps UI extension is
  negotiated in `initialize` when *any* hosted app serves a `ui://` resource. The single
  `tesseraql.mcp.enabled` flag governs the whole endpoint.
- **Security stays per-route.** A mounted-app tool's `tools/call` (or resource read) runs the
  route that app declared, with that route's own `auth`/`policy`. The MCP request's bearer token
  rides into it exactly as for a main-app tool. Mounted apps share the main app's configuration
  (datasources, security policies, JWT verification), so a policy and the token verifier resolve the
  same way across apps.
- **Names and uris are unique across apps.** Because every app's surface shares the one endpoint, a
  tool name (a tool's `id`), a resource `uri`, and a UI `ui://` uri must be unique across all hosted
  apps — resources and UI resources share one uri namespace. The startup route-conflict check (the
  same guard that rejects duplicate HTTP route ids and method+path pairs across mounted apps) rejects
  the collision with a clear error, so a clash fails the mount rather than silently shadowing a tool.

This needs no new YAML: an app declares its MCP surface the same way whether it runs as the main app
or is mounted into another.

## Error codes

| Code | Meaning |
| --- | --- |
| `TQL-MCP-4002` | a dev-tool call is missing a required argument |
| `TQL-MCP-5001` | no datasource: the call gave no `jdbcUrl` and the app declares no main datasource |
| `TQL-MCP-1001` | (lint) an application MCP tool uses a recipe other than `query-json` / `command-json` |
| `TQL-MCP-1002` | (lint, warning) an application MCP tool has no `description` |
| `TQL-MCP-4030` | (lint) a write MCP tool declares no authorization policy |
| `TQL-MCP-1003` | (lint) an application MCP resource is not read-only (`query-json` with query-mode SQL) |
| `TQL-MCP-1004` | (lint) an application MCP resource declares no `uri` |
| `TQL-MCP-1005` | (lint, warning) an application MCP resource has no `description` |
| `TQL-MCP-1006` | (lint) an application MCP resource declares `input:` (a resource takes no arguments) |
| `TQL-MCP-1007` | (lint) two application MCP resources declare the same `uri` (UI resources share the namespace) |
| `TQL-MCP-1008` | (lint) an MCP Apps UI resource does not render HTML (use `query-html` or `page`) |
| `TQL-MCP-1009` | (lint) an MCP Apps UI resource declares no `ui://` uri |
| `TQL-MCP-1010` | (lint, warning) an MCP Apps UI resource has no `description` |
| `TQL-MCP-1011` | (lint) an MCP Apps UI resource declares `input:` (a UI resource takes no arguments) |
| `TQL-MCP-1012` | (lint) a tool's `ui:` link resolves to no declared UI resource |

Tool failures (a bad argument, a missing datasource, a draft that does not compile) come back
as an MCP tool result with `isError: true` and the message — the connection stays up so the
agent can read the error and correct course. Protocol-level mistakes (unknown method,
malformed JSON-RPC) use the standard JSON-RPC error codes.
