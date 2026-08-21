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

Sub-folders under `mcp/` are for tidiness only. Unlike `web/`, where the directory path *is*
the URL, an MCP document is addressed by the name it declares — a tool by its `id:`, a
resource by its `uri:` — so moving a file between folders changes nothing a client sees, and
two documents declaring one name collide wherever they sit (`TQL-MCP-1014`). Colocated files
resolve beside their document, so `mcp/sales/orders/tool.yml` reads
`mcp/sales/orders/list.sql`.

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

sources:
  main:
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
  authorization policy or lint fails (`TQL-MCP-4030`): deny-by-default, because an agent must
  not mutate data unauthorized. The governance gate scores and gates tools like routes, so a
  write tool reachable without authentication is `advanced` and needs approval. An `mcp`
  coverage kind tracks which tools your declarative suites exercise.

Set `tesseraql.mcp.enabled: false` to stop serving the endpoint (tools, resources, UI
resources, and prompts alike).

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

sources:
  main:
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

sources:
  main:
    sql:
      file: orders-board.sql
      mode: query

response:
  html:
    template: orders-board.html
    model:
      orders: main.rows

ui:
  prefersBorder: true
  csp:
    connectDomains: ["'self'"]
```

```yaml
# mcp/find-orders.yml — the tool links to the UI resource it renders into
ui: ui://orders/board
```

On startup the compiler turns each UI resource into a read-only internal route running the
same read-and-render pipeline a `query-html` route runs: telemetry, the resource's own
authentication and authorization, tenancy and locale resolution, the 2-way SQL, then the
Thymeleaf template. It therefore renders the same `hc-*` fragment a page would. UI work
follows the blessed patterns in [docs/hypermedia-ui.md](hypermedia-ui.md), and any gap
belongs upstream in the kit rather than in app CSS.

The runtime serves it over the same `/_tesseraql/mcp` endpoint as the tools and resources.
So:

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
- **Read-only by construction, governed like a read.** Lint keeps a UI resource
  HTML-rendering and uri-addressed (`TQL-MCP-1008`/`1009`/`1011`), warns on a missing
  description (`TQL-MCP-1014`), and rejects a tool whose `ui:` link dangles
  (`TQL-MCP-1012`). The governance gate scores a UI resource like a read route — never
  `advanced`, since it cannot write. An `mcp-ui` coverage kind tracks which UI resources your
  declarative suites exercise.

## Prompts

An app can also declare an MCP **prompt** — a parameterized, reusable message the connecting
agent surfaces to its model (an IDE slash command, say). A prompt is a document under `mcp/`
with `kind: prompt` and the `prompt-text` recipe, and it is a route exactly like the other three
kinds: same `input:`, same `security:`, same `sources:`, ending in a `response.text:` whose
rendered string *is* the message. So a prompt reads data — "draft a welcome for customer 4711"
looks 4711 up instead of asking the agent to fetch it first:

```yaml
# mcp/draft-welcome.yml
version: tesseraql/v1
id: draft-welcome
kind: prompt
recipe: prompt-text
description: Draft a welcome message for a customer, in that customer's own context.

security:
  auth: bearer
  policy: customers.read

input:
  customerId:
    type: integer
    required: true
    description: The customer to welcome.
  tone:
    type: string
    default: warm
    enum: [warm, formal]
    description: How the message should read.

sources:
  main:
    sql:
      file: customer.sql
      mode: query
      params:
        customerId: params.customerId

response:
  text:
    template: draft-welcome.txt.tpl
    model:
      customer: main.first
      tone: params.tone
```

```text
# mcp/draft-welcome.txt.tpl  (Thymeleaf TEXT mode)
Write a [(${tone})] welcome message for [(${customer.name})],
who joined on [(${customer.signedUpOn})] and is on the [(${customer.plan})] plan.
```

A prompt with nothing to look up declares no `sources:` and renders from its arguments alone —
that is the whole document minus the two blocks.

- **A route like the others.** `prompts/get` runs the same pipeline a tool call runs: telemetry
  and the audit trail, the prompt's own `security:`, tenancy and locale resolution, input
  binding, the declared `sources:`, then the template. The arguments arrive as strings and are
  coerced and validated by the `input:` declaration, so `type: integer` is enforced rather than
  documented — a caller sending `"abc"` gets an error instead of a message with `abc` in it.
- **`security:` is available and optional.** A prompt may declare `auth:`/`policy:` like any
  route, which is what the example above does because it reads customer data. Declaring nothing
  leaves the prompt open, which is right for a template that only rephrases its arguments.
  Discovery stays open either way: `prompts/list` advertises every prompt, and a declared policy
  is enforced on `prompts/get`, exactly as a tool's is on `tools/call`.
- **The arguments are the `input:`.** `prompts/list` advertises each declared field's name,
  `description:` and whether it is `required:` — one declaration, so what the agent is told and
  what the binder enforces cannot drift.
- **A prompt is a read.** `prompts/get` is a read in the protocol's own vocabulary, so a prompt
  declaring `steps:` or a source in `mode: update` is refused (`TQL-MCP-1016`, and
  `TQL-ROUTE-3116` if it reaches the compiler). A prompt that writes is a tool.
- **Only the input keys a prompt can act on.** An argument is a full route `input:` field. The
  three keys with nothing to act on it here — `policy:`, `writable:`, `widget:` — are refused
  (`TQL-MCP-1015`) rather than silently accepted. A prompt renders a message rather than a form,
  and its arguments come from the caller, so the field-level write gate can only refuse the call.
  A key a shared `domain:` supplies is not refused — the author did not write it here, and a
  domain must stay usable from every surface that references it.
- **Advertised like the rest.** The runtime serves prompts at the same `/_tesseraql/mcp` endpoint;
  `prompts/list` enumerates them and the `prompts` capability is negotiated in `initialize` when an
  app declares any. An `mcp-prompt` coverage kind tracks which prompts' SQL your declarative suites
  exercise (a prompt that reads nothing declares no SQL, so it is not counted). This is the
  application-side counterpart of the dev tool's `studio_copilot` prompt
  ([AI-assisted development](ai-mcp.md)) — TesseraQL ships the workflow, the agent's own model
  does the reasoning, no embedded LLM.

## Mounted-app tools

A TesseraQL runtime hosts one application plus the framework's own surfaces — the ops console,
Studio, IAM admin, the account pages and the sign-in pages. Each is a plain
YAML/SQL/template tree compiled by the same route compiler, so each may declare its own MCP tools,
resources, and UI resources under `mcp/`. The runtime serves them all from the one
`/_tesseraql/mcp` endpoint, so an agent sees one catalog spanning every hosted app:

- **One endpoint, every app.** `tools/list` and `resources/list` advertise the tools, resources, and
  UI resources of the application and the framework's surfaces together; the MCP Apps UI extension is
  negotiated in `initialize` when *any* hosted app serves a `ui://` resource. The single
  `tesseraql.mcp.enabled` flag governs the whole endpoint.
- **Security stays per-route.** A tool's `tools/call` (or resource read) runs the route that
  declared it, with that route's own `auth`/`policy`. The MCP request's bearer token rides into it
  the same way for every hosted app, which share the application's configuration (datasources,
  security policies, JWT verification), so a policy and the token verifier resolve the same way
  across them.
- **Names and uris are unique across apps.** Because every app's surface shares the one endpoint, a
  tool name (a tool's `id`), a resource `uri`, and a UI `ui://` uri must be unique across all hosted
  apps — resources and UI resources share one uri namespace. The startup route-conflict check (the
  same guard that rejects duplicate HTTP route ids and method+path pairs) rejects the collision with
  a clear error, so a clash fails the mount rather than silently shadowing a tool.

This needs no new YAML: an app declares its MCP surface the same way whether it is the application a
runtime serves or one of the framework's own surfaces.

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
| `TQL-MCP-1013` | an `mcp/` document declares a `kind:` outside `tool` / `resource` / `ui` / `prompt` |
| `TQL-MCP-1014` | two application MCP tools, or two prompts, declare the same `id` (the folders name nothing) |
| `TQL-MCP-1015` | a prompt argument declares `policy:`, `writable:` or `widget:`, which a prompt cannot act on |
| `TQL-MCP-1016` | a prompt declares `steps:` or a source in `mode: update` (`prompts/get` is a read) |
| `TQL-MCP-4030` | a write MCP tool declares no authorization policy |

At runtime, a tool call that fails (a bad argument, an unauthorized write) comes back as an
MCP tool result with `isError: true` and the message — the connection stays up so the agent
can read the error and correct course. Protocol-level mistakes (unknown method, malformed
JSON-RPC) use the standard JSON-RPC error codes.

## Next

- [ai-mcp.md](ai-mcp.md) — the framework-authoring server, rather than your app.
- [authentication.md](authentication.md) — how an agent caller is authorized.
