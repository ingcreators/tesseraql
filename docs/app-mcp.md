# Application MCP surface

A TesseraQL **application** can declare its own [Model Context
Protocol](https://modelcontextprotocol.io) surface — tools an agent calls, resources it
attaches as context, interactive UI it renders, and prompts it reuses — the way the app
already declares HTTP routes. Each is a YAML document placed under `mcp/` instead of `web/`,
compiled through the same route pipeline (security, input validation, 2-way SQL, telemetry),
and served by the runtime at `/_tesseraql/mcp`, so the running business application is
AI-enabled. This page is for building MCP features *into* the app you ship; for the
development-time tooling — the `tesseraql mcp` server a coding agent uses to lint, test, and
scaffold the app itself — see [AI-assisted development (MCP)](ai-mcp.md).

## Tools

A tool is a `query-json` or `command-json` definition placed under `mcp/` instead of `web/`:
same recipe, input constraints, 2-way SQL, and security as a route — only the entry point
differs.

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
`/_tesseraql/mcp` (the same protocol the [dev-tool HTTP transport](ai-mcp.md) speaks). On
startup the compiler turns each tool into an internal route running the full pipeline —
telemetry, the tool's own authentication and authorization, input validation, the SQL or
transactional command — and the MCP endpoint dispatches a `tools/call` to it. So:

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

## Resources

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

## MCP Apps UI

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

## Prompts

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
  app declares any. This is the application-side counterpart of the dev tool's `studio_copilot`
  prompt ([AI-assisted development](ai-mcp.md)) — TesseraQL ships the workflow, the agent's own
  model does the reasoning, no embedded LLM.

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

Lint findings:

| Code | Meaning |
| --- | --- |
| `TQL-MCP-1001` | an application MCP tool uses a recipe other than `query-json` / `command-json` |
| `TQL-MCP-1002` | (warning) an application MCP tool has no `description` |
| `TQL-MCP-1003` | an application MCP resource is not read-only (`query-json` with query-mode SQL) |
| `TQL-MCP-1004` | an application MCP resource declares no `uri` |
| `TQL-MCP-1005` | (warning) an application MCP resource has no `description` |
| `TQL-MCP-1006` | an application MCP resource declares `input:` (a resource takes no arguments) |
| `TQL-MCP-1007` | two application MCP resources declare the same `uri` (UI resources share the namespace) |
| `TQL-MCP-1008` | an MCP Apps UI resource does not render HTML (use `query-html` or `page`) |
| `TQL-MCP-1009` | an MCP Apps UI resource declares no `ui://` uri |
| `TQL-MCP-1010` | (warning) an MCP Apps UI resource has no `description` |
| `TQL-MCP-1011` | an MCP Apps UI resource declares `input:` (a UI resource takes no arguments) |
| `TQL-MCP-1012` | a tool's `ui:` link resolves to no declared UI resource |
| `TQL-MCP-4030` | a write MCP tool declares no authorization policy |

At runtime, a tool call that fails (a bad argument, an unauthorized write) comes back as an
MCP tool result with `isError: true` and the message — the connection stays up so the agent
can read the error and correct course. Protocol-level mistakes (unknown method, malformed
JSON-RPC) use the standard JSON-RPC error codes.
