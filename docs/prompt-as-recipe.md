# A prompt is a recipe

Status: **designed 2026-08-13.**
Pre-1.0: the replaced spelling is deleted, not aliased; the CHANGELOG records what changed
and why.

Three of the four MCP document families are routes. `kind: tool`, `kind: resource` and
`kind: ui` are parsed by the route parser, carry `input:`, `security:` and `sources:`, and are
compiled by `buildMcpTool`/`buildMcpResource`/`buildMcpUi` into the same pipeline an HTTP route
runs — JSON-RPC is the entry point, not a different kind of thing. `kind: prompt` is the
exception: the loader branches on it before the route parser and reads the raw tree, and the
runtime renders its template with no route behind it.

That exception was never designed. It followed from "a prompt is pure text, so it has no
recipe", and this document is about what that sentence cost.

## What the exception costs

- **A prompt cannot read data.** `sources:` is unavailable, so "draft a welcome for customer
  4711" cannot look 4711 up. The author's workaround is a tool that returns prose, which is a
  tool pretending to be a prompt.
- **A prompt has no `security:`.** The MCP endpoint takes no transport-level gate by design —
  [McpRouteBuilder](../tesseraql-camel-runtime/src/main/java/io/tesseraql/runtime/McpRouteBuilder.java)
  says so: "each tool runs its own route security, so there is no transport-level auth gate".
  A prompt therefore has *no* gate at all, and not because prompts are public by decision:
  because there is no route to hang a policy on. A prompt that interpolates data (see above)
  would need one.
- **Its `input:` is a near-copy that does less.** A prompt argument accepts `type:`,
  `required:` and `description:`, of which only the last two are read — `type:` is documented
  in the guide, consumed by nothing, and is registered today as a known-dead component. The
  route `input:` next door is `InputField`, whose `type:` drives coercion and validation.
- **Every cross-cutting concern skips it.** No telemetry span, no audit, no coverage kind, no
  governance scoring, no `TQL-MCP-*` lint beyond what was hand-written for it — each because
  they attach to routes.

None of these are decisions. They are the shape of the exception.

## The pieces already exist

The framework already renders a Thymeleaf TEXT template with a model, from a route:
`response.file:` does exactly that
([FileResponseRenderer](../tesseraql-compiler/src/main/java/io/tesseraql/compiler/binding/FileResponseRenderer.java)),
resolving the template beside the document and evaluating each model value as a context
expression. A prompt's rendering step is that renderer with the HTTP headers removed.

So making a prompt a recipe is not new machinery. It is deleting a branch.

## Decisions

1. **`kind: prompt` is parsed by the route parser**, like its three siblings. `PromptDefinition`
   and its `Argument` are deleted; the document carries `input:` (`InputField`), `security:`,
   `sources:` and a response, and `SimpleYamlParser.parsePrompt` goes with them.
2. **A new recipe, `prompt-text`.** It compiles to `direct:mcp.prompt.<id>` through the same
   head every recipe gets — `applyCommonGovernance`, `RequestBinder`, `CatalogBinder`, the
   declared sources, then the renderer. It is a read recipe: a command step is refused, because
   `prompts/get` is a read in the protocol's own vocabulary.
3. **The response arm is `text:`**, a `FileResponse` without `filename`/`contentType` — the
   rendered string *is* the message, not a download. Reusing `file:` verbatim would accept two
   keys with nowhere to go, which is the defect this document is about; `text:` is
   `FileResponse`'s two meaningful keys under a name that says what it produces. The prompt
   handler reads the body and wraps it as one `user` message, exactly as today.
4. **`input:` is `InputField`, and `type:` becomes real.** MCP delivers arguments as strings;
   the binder coerces them by the declared type and refuses a value that does not parse, so
   `type: integer` stops being decoration. The keys `InputField` carries that a prompt cannot
   act on (`policy:`, `writable:`, `domain:`, `codes:`, `widget:`, `classification:`, `mask:`)
   are refused by lint on a prompt document rather than silently accepted — the same "wire it
   or don't declare it" rule the surface guard enforces on the Java side.
5. **`security:` applies.** A prompt declares `auth:`/`policy:` like any route; a prompt that
   reads data through `sources:` and declares no policy is a lint error, mirroring the
   deny-by-default rule a write tool already lives under (`TQL-MCP-4030`).
6. **Everything cross-cutting follows for free** — telemetry, audit, governance scoring, and an
   `mcp-prompt` coverage kind — because they attach to the route, and now there is one.

## What this deliberately does not do

- No change to the wire: `prompts/list` and `prompts/get` keep their shapes, and a client sees
  the same prompts it saw before.
- No prompt-side `command-json`. A prompt that writes is a tool.
- No multi-message prompts. `prompts/get` may return several messages with roles; TesseraQL
  returns one `user` message today and this document does not widen that — it is a protocol
  feature to design on its own, not a side effect of a refactor.

## The break

A prompt document gains a `recipe:` line and its `input:` entries mean what the route `input:`
means. `type:` on an argument, previously accepted and ignored, is now enforced — a document
declaring `type: integer` on an argument whose caller sends `"abc"` starts failing where it
used to render the string. Both are pre-1.0 breaks recorded in the CHANGELOG with no upgrade
steps, per rule 10.

## Slices

1. **The recipe** — `prompt-text` in the compiler, the `text:` response arm, `buildMcpPrompt`,
   and the MCP handler dispatching `prompts/get` to the route. `kind: prompt` still parses the
   old way; both paths exist for exactly this slice.
2. **The model** — `kind: prompt` moves to the route parser, `PromptDefinition`/`parsePrompt`/
   the `promptFile` tree-reading are deleted, `PromptFile` carries the route definition like
   `ToolFile` does, and the `UNWIRED` registration for `Argument#type` goes with them.
3. **The rules** — lint for the refused `InputField` keys, the read-only refusal, the
   policy-when-it-reads rule, the `mcp-prompt` coverage kind; `docs/app-mcp.md` rewritten for
   the recipe, with a worked example that reads data.

Each slice is a PR with the yaml, compiler and runtime suites green; slices 2 and 3 carry the
CHANGELOG entries.
