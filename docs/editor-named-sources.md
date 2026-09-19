# A view's `source:` is a route's declaration: go-to-definition and completion for named sources in the editor

> **Status: designed 2026-09-15; every decision decided as recommended the same day; S1 shipped
> the same day.** One implementation slice and one release, each on the user's call.
>
> **S1** — the `symbols` contract carries every route's named sources (with their lines), the
> view it binds and the views it composes; the extension navigates from a `source:` value to the
> `sources.<name>:` line of the route that binds the document, and completes the names the
> binding route declares: **shipped, #1357** (extension 0.3.17; the CLI guard was red on HEAD by
> absence, the two detector variants `v-nowalk` and `v-anyenrich` each red on exactly the
> exclusion test, and the new CLI's document for `examples/procurement-app` fed into the
> compiled extension core resolved `dashboard.view.yml:13` to `web/dashboard/get.yml:14`).
> **R1** — `ext-v0.3.17`, the first extension release since #1354 and #1355, read back by its
> notes and by the bytes of its vsix: *open — the user pushes the tag after S1 merges*.

This is the last line of `docs/vscode-extension.md` "Not currently supported" — "go-to-definition
for named queries" — written when the route surface still spelled them `queries:`. Since the
unified source model ([`unified-sources.md`](unified-sources.md)) a route declares every
acquisition of rows as a named entry of `sources:`, `main` reserved for the primary, and a view
document reads them by name. The extension resolves every other declared name the framework
knows — policies, message keys, domains, rules, decisions, calendars, catalogs, workflows, jobs —
and not this one.

Registered in `docs-site/nav.mjs` `EXCLUDED` and `ErrorIndex.INTERNAL_DOCS` by this record.
Site-excluded, so the prose lint does not read it; `sync-content.mjs` and `InternalDocsSyncTest`
do.

## What was measured

Measured 2026-09-15 on main `a1bd03d92` (0.18.0-SNAPSHOT). Rows 1 and 3 are RUNs; rows 2 and 4
are censuses over the tree; row 5 is a reading of the lint.

| # | Run | Result |
| --- | --- | --- |
| 1 | `tesseraql symbols --app examples/procurement-app` (the `0.18.0-SNAPSHOT` CLI jar from the local repository) | 27 routes, each carrying `id`, `source`, `method`, `path`, `recipe` and nothing else. The dashboard route: `{"id": "procurement.dashboard", "source": "web/dashboard/get.yml", "method": "GET", "path": "/dashboard", "recipe": "query-html"}` — its three sources (`main`, `ordersByState`, `openNegotiations`, `web/dashboard/get.yml:10,14,17`) and the view it binds (`procurement.dashboard.view`, line 23) are not in the contract. |
| 2 | Census of the 13 shipped applications (7 examples, the 6 bundled system apps) | 278 route documents. **11 declare a source other than `main`** (26 named sources); every one of the 11 declares more than one. **20 `view:` bindings across 19 view documents**; one view is bound by two routes (`prices-import`, `examples/inventory-app/web/products/prices/import/get.yml:14` and `post.yml:45`). Zero `views:` lists. **16 `source:` scalars in view documents** — the three dashboards (`inventory-app` 7, `procurement-app` 6, `user-admin-app` `stats.view.yml` 3), 6 of them in the flow-map spelling `- { type: stat, source: main, … }`. **2 `source:` scalars in route documents, both `params:` bind names** (`source: params.source`, the Studio validation builder's POST and its copy) — a `source:` in a route document is not a reference by default. Zero `enrich: … source:` anywhere shipped. |
| 3 | The compiled extension's detectors on `dashboard.view.yml:13` (`    source: ordersByState`, cursor on the value) and `:7` (`- { type: stat, source: main, … }`) | `symbolReferenceAt` → `undefined`, `viewReferenceAt` → `undefined`, `completionKindAt` → `undefined`, `viewCompletionAt` → `undefined`. **Go to Definition does nothing on either line, and nothing completes after `source:`.** `referenceLinks` (`links.ts`) matches `file:`/`template:`/`view:` values only. |
| 4 | Where `source:` means something else | The route schema's `enrichment.source` is a *context path* ("a route source by name, a job step as `steps.<id>`"); `inputField.lookup.source` is a URL path (`examples/purchase-request-app/domains/supplier.yml:11`, `source: /api/suppliers/search`); `decisions.<name>.source` is an object (a table); a `params:` map may name a bind `source` (row 2). The view schema (`tesseraql-view-v1` + `viewChild`/`viewPanel` in the shared defs) has exactly three `source` properties — the document's, a child's, a panel's — **and all three are the reference kind.** |
| 5 | How the framework resolves the reference (`ViewRules`, `TQL-VIEW-3308`) | For each route whose `response.html.view` resolves (`manifest.viewById`), every `children[].source` and `panels[].source` must be a key of that route's `sources:` — or `main`, accepted whether or not it is declared (`declaresViewSource`). A view bound through `views:` on a template route, and a view embedded by another view's panel or child, are not judged. The editor mirrors the resolution the lint performs: **the referent of a view's `source:` is a `sources:` entry of a route that binds the view.** |

## The mechanism

A named source is a route-level declaration. `RouteDefinition.sources()` is the authored-order
map of `sources:` (a `LinkedHashMap`; `main` is `RouteDefinition.MAIN`), each value a `Binding`
whose arm is exactly one of `sql` (`file:`), `contract`, `service`, `http`, `sequence`, `spool`
(`Binding.isSql()` and siblings). The contract can therefore say, per route, what it declares
and where — the same `dottedKeyLines` walk that already positions message keys and shared
definitions yields `sources.<name>` → line for a block-form `sources:` (a flow-form
`sources: { … }` yields no line, and the editor falls back to the file's first line, as it does
for every other kind).

A named source is *referenced* from four places, of which two are declared references with a
single referent:

1. **A view document bound to the route** — `source:` at the top level, on a panel, on a child
   (block or flow-map spelling). The view is bound by the route's `response.html.view`, or
   listed in a template route's `response.html.views`. This is the reference the lint judges.
2. **The route's own `enrich:`** — `source:` is a context path; a bare identifier names one of
   the route's sources. (`steps.<id>` is the *job* spelling — a pipeline step's `enrich:`
   composes an earlier step — and a route's `enrich:` cannot carry it: `TQL-YAML-1046`.)
3. **Bindable paths** — `response.html.model` (`users: main.rows`), `response.json.body`,
   `notifications[].payload`: a path whose *first segment* may be a source name. A path, not a
   reference; filed here, resolved by docs/audit-low-leads.md slice 21 (a value-shape
   detector, `pathReferenceAt`).
4. **Templates** — `${ordersByState.rows}` in Thymeleaf HTML and the export templates. Not YAML;
   filed with the "embedded-SQL analysis" line that stays under "Not currently supported".

The view → route direction is the new one. Every other kind the extension resolves is declared
in a shared document and referenced from anywhere; a source is declared in one route and
referenced from a document that does not name the route. The manifest holds the binding
(`response.html.view`), so the contract carries it and the extension inverts it.

## The decisions

Each was recommended, and the user decided every one as recommended (2026-09-15).

1. **The truth is the `symbols` contract, extended.** Every route entry gains `sources`
   (`[{name, line, arm, file}]` in authored order; `file` is the `sql` arm's file, `null` for
   the other arms), `view` (the id `response.html.view` names, or `null`) and `views` (the ids
   `response.html.views` lists, `[]` when none). *Rejected:* a workspace scan in the extension,
   as `views.ts` does for view ids. A view id is a file-system fact (the file name or its `id:`
   line) and the scan mirrors a registry walk; a route's sources are a manifest fact — the
   `sources:` block with its `main` default, its arms and its authored order — and a scan would
   be the "editor plugin with its own parser" the design notes rule out. The cost is real and is
   the established one: **the feature needs the 0.18.0 CLI**; a 0.17.0 CLI omits the three
   properties and the extension degrades them to empty and `null`, navigating nothing and
   raising nothing (the rule every contract addition since 0.8 has followed).
2. **The reference positions are exactly these.** In a `*.view.yml`: every scalar `source:`
   (the three schema positions; block form and inside a flow map `{ …, source: x, … }`). In a
   route document: a scalar `source:` whose nearest less-indented ancestor key chain passes
   through `enrich:` before reaching column 0, and whose value is a bare identifier (no `.`).
   *Not a reference, by construction:* a `source:` under `params:` (row 2's two shipped lines),
   `lookup.source` (a URL, and the value carries `/`), `decisions` `source:` (a block, not a
   scalar), `steps.<id>` (a step, not a source — filed; since slice 21 of
   docs/audit-low-leads.md a bindable path, resolved to the step's `- id:` line), and any
   `source:` in a document that is neither a view nor a route (a job's pipeline `enrich:`
   reads steps, not sources).
3. **The definition target is the `sources.<name>:` key line of the binding route document.**
   Not the SQL file: "from a value to the line that declares it" is the rule every other kind
   follows, and the `file:` on the next line is already a document link (one more click). A
   view bound by several routes returns one location per route (VS Code shows the peek list;
   `prices-import` is the shipped case). A view bound by no route, or a name no binding route
   declares, returns nothing — "the providers navigate, they do not judge"; `TQL-VIEW-3308` is
   the judgement. `main` resolves to the declared `main` line and to nothing when it is not
   declared, even though the lint accepts an undeclared `main`.
4. **A view is bound by the routes whose `view` is its id or whose `views` list it.** Both come
   from the contract (decision 1); the current document's id comes from `viewIdInfoOf`, which
   the view intelligence already computes. An embedded view (a panel's or child's `view:`) is
   read against the *embedding* route's sources at runtime, and is not resolved here: it needs
   the embedding view's panels parsed, which nothing in the extension does. Filed. *Resolved
   by docs/audit-low-leads.md slice 21 on the contract side: each route carries `embeds`, the
   views its bound documents embed, and `routesBinding` counts a host as a binding route.*
5. **Completion at every position of decision 2** offers the union of the binding routes'
   declared source names (in a route document, the route's own), one item per name, its detail
   `<arm> · <file> · <route source>` (`sql · orders-by-state.sql · web/dashboard/get.yml`),
   `file` omitted for a non-sql arm. `main` is offered only when declared. A view bound by no
   route completes nothing.
6. **Where it lives.** Core: `symbols.ts` gains `SourceSymbol`, the three route properties
   (parsed with the same degrade as `jobs`/`workflows`), `sourceReferenceAt(fileName, lineText,
   character, linesAbove)` and `sourceCompletionAt(…)` (the enrich-context walk is the
   `isViewsSequenceItem` shape: up past blank, comment and deeper lines, reading the key chain),
   and `routesBinding(symbols, viewId)`. Glue: `SymbolDefinitionProvider` and
   `SymbolCompletionProvider` in `language.ts` try the source detector after the existing one
   (the index they hold is the pool; the view intelligence's providers do not have it). No new
   provider registration, no new command, no `package.json` contribution.
7. **The guards, red before the fix.** CLI (`AppLifecycleCommandsTest`): the scaffolded app
   plus a route declaring `sources: main`, two named sources of different arms and
   `response.html.view`, and a template route with `views:` — assert names in authored order,
   the 1-based lines, `arm`/`file`, `view`, `views`, and `sources: []` on a route without a
   block; red on HEAD by absence. Extension (`test/symbols.test.ts`): parsing and the degrade
   (a route entry without the three properties); `sourceReferenceAt` at the top-level, child and
   panel positions in both spellings; the enrich context (bare name → reference; `steps.x` →
   not); the exclusions (`params:` bind, lookup URL); `routesBinding` through `view` and through
   `views`; the completion contexts. **The exclusion tests must be run against a built variant
   that detects any `source:` scalar without the ancestor walk — they are the ones that read as
   passing while the walk is broken.** The route-document `source:` census (row 2) is the
   fixture: `source: params.source` under `params:` must not resolve.
8. **The documents.** `docs/vscode-extension.md`: a `source:` row in the completion table, a
   go-to-definition bullet, the contract JSON, the version note ("a pre-0.18 CLI omits …"), and
   the "Not currently supported" bullet reduced to embedded-SQL analysis. `vscode-extension/
   README.md`: the feature under declared-symbol intelligence and the CLI-version caveat
   (`ExtensionLedgerTest` pins the command names there; no command changes). `CHANGELOG.md`
   `## Unreleased` `### Added`, one entry naming both halves. `package.json` `0.3.16` → `0.3.17`
   in the same pull request (`release.md`: an extension bump rides the PR that changes the
   extension). This record's status line.
9. **The release is read back, not watched.** `ext-v0.3.17` is pushed by the user after S1
   merges, on the release commit or any later `main`. Three things to read, in order: (a) the
   GitHub release's notes are the output of `.github/scripts/extension-release-notes.sh` — the
   pull requests that touched `vscode-extension/` since `ext-v0.3.16`, expected to be S1 alone
   (`gh release view ext-v0.3.17 --json body`), not seventy-two framework lines; (b) the vsix the
   Marketplace serves is the release asset: `curl -sSL --compressed` of
   `https://marketplace.visualstudio.com/_apis/public/gallery/publishers/ingcreators/vsextensions/tesseraql-vscode/0.3.17/vspackage`
   and `gh release download ext-v0.3.17 -p '*.vsix'`, then `sha256sum` — **measured today on
   `ext-v0.3.16`: both `d3e0642af3cd…`, 57,771 bytes, 33 entries**, so the claim in
   `docs/vscode-extension.md` "Publishing" holds for the current release and the check costs two
   commands; (c) the token step's log — the retry script prints an `attempt n/3` line only on a
   timeout, so a clean pass says nothing new about the retry and a `Request timeout` followed by
   a pass is the first live proof of #1355. No tag is cut to exercise the workflow.
10. **The version skew is stated where the user reads it.** The README's requirements line and
    the user document's version note say the source navigation needs the `0.18+` `symbols`
    document; on 0.17.0 it stays absent, like every earlier contract addition on an older CLI.

## What this breaks

Nothing. The contract grows three properties on an entry consumers already tolerate unknown
keys on (the extension's parser reads named properties; the MCP dev-tools do not read `symbols`
at all). No route, view or lint behaviour changes.

## Filed, not fixed

- **Bindable paths** (`response.html.model`, `response.json.body`, `payload:`): a path whose
  first segment is a source name or `steps.<id>`. A path resolver is a different detector (the
  first segment, then `rows`/`first`/`rowCount`/a column) and would also serve `steps.<id>`.
  *Fixed in docs/audit-low-leads.md slice 21: `pathReferenceAt` detects by the value's shape
  (one dotted path as the whole scalar, or a `{…}` placeholder), whatever the key — `params:`
  and `location:` carry the same class.*
- **`steps.<id>` in `enrich: source:`, `spool:`, `attach:`, `file:`** — a step reference, the
  same shape as a source reference, declared in the same document (`steps[].id`). The contract
  does not carry step ids. *Fixed in slice 21 without a contract change: the referent is in the
  same document, so the extension scans the `steps:`/`pipeline:` sequence for the `- id:` item.
  The `enrich: source:` position exists only on a job's pipeline step — a route's `enrich:`
  cannot name a step (`TQL-YAML-1046`), which this record's mechanism list used to describe.*
- **Embedded views**: a panel's or child's `view:` reads the embedding route's sources; the
  extension would need to invert the embedding, which means parsing panels and children.
  *Fixed in slice 21 on the contract side (`routes[].embeds`) — the manifest knows the hosts;
  an extension-side scan would take a route's own `view:` binding for an embedding.*
- **The contract still does not carry views** (`views.ts` scans); this record adds the *binding*
  to routes, not the registry. A `views` array in the contract would retire the scan. *Measured
  in docs/audit-low-leads.md (EN-04): not worth doing — the scan survives a duplicate view id
  (TQL-VIEW-3315 empties the contract's manifest arrays), so it must stay whatever is added.*
- **A flow-form `sources: { main: … }` yields no line** (`dottedKeyLines` reads block keys); the
  editor lands on line 1 of the route document. Every other kind has the same fallback.
  *Measured (EN-05): a multi-line flow map resolves by indentation; only a name that does not
  start its own line misses. Not worth doing.*
- **Templates** (`${name.rows}` in HTML, jxls and PDF templates) stay with embedded-SQL analysis
  under "Not currently supported".
