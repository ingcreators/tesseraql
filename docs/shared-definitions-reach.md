# Shared definitions: reach beyond `web/`

> **Status: slices 1–2 shipped, 3–5 designed.** [field-domains.md](field-domains.md) and
> [validation-rule-sets.md](validation-rule-sets.md) both shipped, and both stop at the `web/`
> boundary. The 2026-07-25 contract-deviation sweep confirmed — by running the real loader and
> compiler against a purpose-built app home — that `use:` in an MCP tool or queue consumer fails
> at startup with an error naming keys the author never wrote, and that `domain:` on a tool or
> consumer input **silently loses every constraint it carries**. The linter compounds it from
> both directions: it reports the referenced-only-from-a-tool rule as unreferenced, and it
> rejects `validate:` on recipes the compiler happily validates. This document closes the reach
> gap and makes the authoring surfaces agree.
>
> **Slices 1–2 are shipped:** resolution moved above the tree split, so routes, queue consumers,
> and MCP tools all resolve `use:` and `domain:`; every "is this referenced?" scan
> (`TQL-FIELD-4611`/`4612`, `TQL-SEC-4136`) now walks all three, so closing the reach gap did not
> just relocate it; and `TQL-YAML-1003` gates on the recipes that actually reach the transactional
> command pipeline instead of on the name `command-json`. `lintValidation` is called from the
> consumer and tool linters too. Slices 3–5 (schema/reference, the bind-contract check, portal
> and Studio) remain.

The failure class: a definition declared once and referenced by name, where the resolution step
runs for one document tree and not its siblings. What makes it worse than an ordinary gap is the
error it produces. `ValidationRule.isExpression()` and `isSql()` are both false when only `use:`
is set, so the compiler's arity check fires:

```
TQL-CAMEL-3102: Route 'mcp.x': validation rule 'nameIsFree' must declare exactly
one of rule: or file:
```

The author wrote `use:`. The message never mentions it.

## What is resolved where

`ManifestLoader.load` wraps `loadRoutes` in `applyRuleSets(applyFieldDomains(applySecurityDefaults(…)))`;
`loadMcp` and `loadConsumers` return raw parser output. Nothing downstream resolves either —
`ValidationRuleSets` and `FieldDomains` appear at six production sites, all of them routes-only or
docs/Studio consumers.

| Tree | `security.defaults` | `domain:` | `use:` | validate lint | domain/rule reference scan |
| --- | --- | --- | --- | --- | --- |
| `web/` | yes | yes | yes | yes (was over-strict) | yes |
| `consume/` | n/a | yes (was **—**) | yes (was **—**) | yes (was **none**) | yes (was **—**) |
| `mcp/` | n/a | yes (was **—**) | yes (was **—**) | yes (was **none**) | yes (was **—**) |

Both trees fully support `validate:` and `input:` — the compiler passes `definition.validate()`
into the transactional command processor for queue-consume and MCP tools alike, and constructs
`RequestBinder` with the raw definition — so this is a resolution gap, not an unsupported feature.

Reproduced live against `target/classes`:

```
== CONSUMERS ==
  validate nameIsFree use=nameIsFree rule=null file=null isExpr=false isSql=false
  input name domain=items.name type=null maxLength=null      <-- domain constraints LOST
== TOOLS ==   (identical)
CONSUMER THROWS: TQL-CAMEL-3102 …
TOOL THROWS:     TQL-CAMEL-3102 …
```

The `domain:` half is the dangerous one because it is **silent**. `InputBinder.coerce` and its
validation read `type`, `maxLength`, `minLength`, `pattern`, `format`, and `enum` off the unmerged
field — all null. On an MCP tool that is an agent-facing write surface advertised (through
`McpInputSchema`) without the constraints its author declared. An unknown domain name is not even
an error: nothing resolves it, so nothing can reject it.

Two aggravating consequences the sweep surfaced:

- `lintFieldDomains` and `lintRuleSets` iterate `manifest.routes()` only, so a domain or rule
  referenced **exclusively** from a tool or consumer is additionally mis-reported as
  `TQL-FIELD-4611` / `TQL-FIELD-4612` "declared but never referenced" — a false positive stacked on
  top of the silent loss.
- `TQL-SEC-4136` (the ambient-principal lint) has the same routes-only scan, so a `principal.*`
  bind in `consume/**/*.sql` — which can never carry a principal — is compile-clean and lint-clean
  and fails on the first message.

Mitigating, and the reason this is designed rather than hotfixed: it is latent. No shipped app or
example uses `use:` or `domain:` outside `web/`; the four `use:` documents in the repo are all
under `web/`.

## The linter disagrees with the compiler about `validate:`

`lintValidation` raises `TQL-YAML-1003` — severity **error**, so it gates CI — for any recipe other
than `command-json`. But `usesTransactionalCommand` returns true whenever `validate()` is
non-empty, and `buildWebhook` delegates unconditionally to `buildTransactionalCommand`, as does
`buildJson` for `query-json`. Reproduced: a `recipe: webhook` route carrying a `validate:` block
yields

```
TQL-YAML-1003 [error] web/hooks/stripe/post.yml :: validate: is only supported
on command-json routes, not 'webhook'
```

while the compiler compiles and runs that validation. So the check is a false positive on two
recipes and, symmetrically, `lintTool` and `lintConsumer` contain no validate handling at all — no
shape check, no missing-SQL check, no SELECT-only check. A typo'd validation SQL filename in a
consumer is lint-green and crashes at startup.

(1003 is *correct* for `query-html`, `page`, `query-export`, `file-import`, and `file-export`,
which never reach the transactional builder.)

## The authoring surfaces lag the model

Neither shared-definition feature reached the editor-facing surfaces:

- `tesseraql-v1.schema.json`'s `$defs.inputField` has no `domain` property, and its `validate` node
  is an untyped map whose description still reads "each declares one of `rule:` (expression) or
  `file:` (validation SQL)" — written before `use:` existed. Because `ReferenceGenerator` renders
  [reference-yaml-surface.md](reference-yaml-surface.md) straight from the schema, the published
  YAML surface documents neither key; a reader concludes they do not exist.
- The scaffolded `.vscode/settings.json` associates the schema with `web/`, `consume/`, `batch/`,
  and `mcp/` — not `domains/` or `rules/`, which get no schema at all. (The schema cannot be reused
  as-is for them: its top-level `required` is `["version","id","kind"]`.)
- `SchemaSyncTest` covers the recipe, auth-mode, and input-type enums. Nothing asserts property
  coverage or description freshness, so this drift was structurally invisible.
- Studio's validation rule builder emits neither `use:` nor `params:` for SQL rules, so an author
  generating a rule there gets an inline copy with unbound non-ambient binds — nudged into exactly
  the duplication rule sets exist to eliminate.
- There is no portal page for rule sets, though [validation-rule-sets.md](validation-rule-sets.md)
  promises one, and `RouteSpecGenerator` drops `use()` and `code()`, so even the per-route page
  cannot show shared provenance. A reviewer asking "which routes share this rule" — the entire
  point of declaring it once — cannot answer from the portal.
- A rule set's `binds:` contract is never checked against the rule's actual SQL. Adding a bind to
  `rules/x.sql` without extending `binds:` passes load and lint everywhere and fails on the first
  request that triggers the rule. `TQL-FIELD-4609` is unallocated, and the design doc's
  "route-local rule textually identical to a shared one" lint was never implemented.
- Portal route-page constraint chips lack `pattern`, `minLength`, and `requiredWhen` because
  `RouteSpec.Input` does not carry them, though OpenAPI and the Domains page render all three — so
  a `pattern:`-constrained input reads as unconstrained at review time.

## The model

**Resolution follows the definition, not the directory.** `applyFieldDomains` and `applyRuleSets`
move above the tree split so every document that can carry `input:` or `validate:` gets them.
Mechanically this needs the appliers to work on the shared shape rather than `RouteFile` — tools
are `ToolFile`, consumers their own type — which is the same signature widening
[route-governance-parity.md](route-governance-parity.md) needs for `applyAudit`. Doing both at once
is cheaper than doing either twice.

**Every scan that answers "is this referenced?" scans every tree.** `lintFieldDomains`,
`lintRuleSets`, and `TQL-SEC-4136` iterate routes, tools, and consumers. Without this, fixing the
resolution gap would leave the 4611/4612 false positives in place and add a new one.

**Lint derives its vocabulary from the compiler, not from a recipe name.** `TQL-YAML-1003` gates on
the same predicate the compiler uses (`usesTransactionalCommand`-equivalent) rather than
`recipe == "command-json"`, and `lintValidation` is called from `lintTool` and `lintConsumer` too.
The general rule this instance argues for: where lint and the compiler both decide "does this
recipe support X", exactly one of them owns the predicate.

**The schema is a tested artifact, not a hand-maintained one.** `SchemaSyncTest` grows a property-
coverage assertion: every field of `InputField` and every key of a `ValidationRule` appears in the
schema, so the next model addition fails the build until the schema and the generated reference
catch up. `domains/` and `rules/` get their own small schemas and their own file associations.

## Slices

1. ~~**Resolution reach.**~~ **Shipped.** The two appliers became per-definition transforms
   (`resolveSharedDefinitions`) applied to routes, consumers, and tools alike; the domain and
   rule-set loads hoisted to one call each. `TQL-FIELD-4611`/`4612` and `TQL-SEC-4136` walk all
   three trees, so a rule referenced only by a tool is no longer reported unreferenced — and
   `TQL-SEC-4136` gained the case the sweep found: a queue consumer can *never* carry a
   principal, so a `principal.*` bind in its SQL is an error regardless of its security block.
   `RuleSetResolutionTest` and `FieldDomainsTest` grew consume/mcp cases, both confirmed to fail
   without the loader change.
2. ~~**Lint vocabulary.**~~ **Shipped.** `TQL-YAML-1003` moved to `lintRoute` and gates on
   `VALIDATING_RECIPES` (command-json, query-json, webhook — every recipe that reaches the
   transactional command pipeline), so the webhook and query-json false positives are gone;
   `lintValidation` is a shape check callable from any surface and runs for consumers and tools;
   `lintEmit` runs for consumers, and tools get their own message since a tool may legally carry
   `recipe: command-json` while its compiled pipeline still broadcasts nothing. The existing test
   that pinned the false positive was rewritten — it asserted the bug.
3. ~~**Schema and reference.**~~ **Shipped.** `domain` on `inputField`, a typed `validate` node
   including `use:`, refreshed descriptions, `domains/`+`rules/` schemas and file associations,
   and the `SchemaSyncTest` property-coverage guard that keeps all of it honest. The domains
   schema carries a copy of the route schema's `inputField` rather than a cross-file `$ref`,
   because whether an editor's language server resolves a relative reference offline could not
   be verified here, while a copy the test compares can be.
4. ~~**The bind contract check.**~~ **Shipped.** Parse the shared rule's SQL at load and compare its bind set with
   `binds:`; reject a `rule:`-kind set declaring non-empty `binds:` (which today dies per-reference
   at compile). *Shipped:* `TQL-FIELD-4609` took the declaration-side load error, because
   4604..4608 are load errors and 4610..4612 are lint warnings — a warning at 4609 would have
   broken the only ordering the family has. The identical-copy warning took `TQL-FIELD-4613`.
5. **Portal and Studio.** Studio's rule builder offering `use:` and emitting `params:` remains.
   **The Rules page is shipped**, mirroring the Domains page deliberately: the two are the same
   idea applied to different declarations, and a reader who has learned one should not have to
   learn the other. Each rule shows its kind, its bind contract, its default code, and the routes
   referencing it; an unreferenced rule is marked with the same signal as lint
   `TQL-FIELD-4612`.
   **The route page's own gaps are shipped:** `use()` and `code()` are carried into `RouteSpec`,
   so a rule referencing a shared one says which one and under what code, and the missing
   constraint chips (`pattern`, `minLength`, `requiredWhen`) render. Those three were already on
   the Domains page and in OpenAPI; dropping them on the route page meant an input carrying a
   real constraint read as unconstrained exactly where a reviewer decides whether it is safe.

## Lint and tooling

- No new lint *codes* for the reach fix itself — the existing 4604..4612 family simply starts
  seeing tools and consumers. The bind-contract check takes `TQL-FIELD-4609` and the
  identical-copy warning `TQL-FIELD-4613` (see slice 4).
- One behavioral change worth calling out in the CHANGELOG: apps that today pass lint with an
  unreferenced-looking domain (because their only reference is from a tool) will stop emitting
  `TQL-FIELD-4611` — a false positive disappearing, not a rule loosening.

## Out of scope

- **`security.defaults` for consumers and tools.** Path-matched route defaults key on a URL path;
  a queue consumer has none. If tool-level defaults are wanted they need their own matcher and
  their own design.
- **Cross-app shared definitions.** Bundled and mounted apps keep their own namespaces, as
  [validation-rule-sets.md](validation-rule-sets.md) already decided.
- **Typed `binds:`.** Still the open question that document parked; the bind-name contract check in
  slice 4 does not presuppose it.

## Open questions

1. Should an unknown `domain:` be a load error everywhere, matching `use:`? It is today, for
   routes — `FieldDomains.require` throws. Once resolution reaches tools and consumers it becomes
   one automatically, which is right but is a new failure mode for manifests that "worked".
   Leaning: yes, and name it in the CHANGELOG — a silently unenforced constraint is the bug.
2. Does the MCP input schema advertise domain-derived constraints to the model once domains
   resolve? `McpSchema`'s javadoc frames type-and-required as deliberate scope. Leaning yes for
   enum/pattern/length: a model that sees the constraint produces a valid call, and the alternative
   is a rejected tool call the model cannot diagnose.
3. Should the Rules page live in the portal, in Studio, or both? Domains chose the portal. Leaning
   portal for parity, with Studio linking to it rather than reimplementing.
