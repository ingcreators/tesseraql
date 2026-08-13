# The linter as a rule registry

Status: **designed 2026-08-13.**
Pre-1.0: code renumbering and severity changes carry no upgrade steps; the CHANGELOG records
what changed and why.

The refactoring audit measured `AppLinter` at ~6,100 lines: one public method, ~170 private
methods, ~190 distinct codes raised from ~360 sites, thirty-five commits in one release cycle.
The 2026-08-13 campaign already extracted the substrate — `LintContext` memoizes every read and
parse, `SqlNode.walk` replaced five hand-rolled traversals — but the rules themselves still
live in one class, and three structural problems remain that no single fix inside that class
can reach. This document decides the target shape; the slices are mechanical once it is
decided.

## What exists today

- **The fault line already exists, on the test side.** The lint suite is 37 files, one per
  rule family (`AppLinterDecisionsTest`, `AppLinterMessagingTest`, `AppLinterStepUnitTest`, …),
  while the production side is one file. Every campaign edits both; only one of them merges
  cleanly.
- **Three strictness mechanisms answer "what happens to an unknown key".**
  `view/ViewSpec.parse` hand-whitelists every nesting level and hard-fails the load
  (TQL-VIEW-3314). `SimpleYamlParser` key-checks domains and catalogs against hand-kept sets
  and hard-fails — but parses rule sets, decisions and calendars into `ignoreUnknown` records
  with no check at all. Routes, jobs, scopes, workflows, consumers and mcp documents get the
  reflective walk in `AppLinter.lintUnknownKeys` (TQL-YAML-1043/1044, warning) — but only for
  the document's own keys plus the blocks someone registered in `FIXED_SHAPE_BLOCKS`: the
  interiors of `security:`, `response:`, `consume:`, `publish:` and `webhook:` are still
  silently tolerant, and `kind: prompt` documents are parsed from the raw tree and never
  checked. Whether a typo is a load error, a warning, or nothing is an accident of which
  campaign touched the document family.
- **Codes no longer identify rules.** `LintFinding.severity` is a free string (`"error"`,
  `"warning"` at ~360 call sites); TQL-FIELD-2004 alone answers four distinct problems
  (a command step declaring `enrich:`, a job step declaring no work, two bindings, `chunk:`
  beside a binding). The generated error reference merges those into one row, and the
  `ErrorCodeUniquenessTest` guard added this campaign can only police *declared constants* —
  string-literal lint codes are outside its reach.
- **Rule order is load-bearing only where tests say so.** `lint()` is a hand-ordered call
  sequence of ~35 family methods; a handful of tests assert emission order within one family,
  none across families.

## Decisions

1. **A rule family is a class.** `<Family>Rules.java`, package-private, one per existing test
   file, implementing one interface:
   `interface LintRule { void lint(LintContext context, AppManifest manifest, List<LintFinding> findings); }`.
   `AppLinter.lint()` keeps its signature and becomes: build `LintContext`, run the registry
   in today's order, return findings. The registry is a `List.of(...)` in `AppLinter` —
   explicit, ordered, diff-reviewable; no ServiceLoader, no annotations.

   *Amended during slice 1–3 (the sketch said `lint/rules/`).* A package-private class in
   `io.tesseraql.yaml.lint.rules` is invisible to `AppLinter`, so the registry could not name
   it; publishing fifty rule classes to buy the subpackage contradicts "no SPI surface, rules
   are framework-internal". The families live beside `AppLinter` in `io.tesseraql.yaml.lint`.
   The registry is also **built per run** rather than held in a `static final` list: a family
   holds its run's `LintContext` in a field, and one shared list would leak that context
   between concurrent lints (two Studio requests, or a build linting several apps).
2. **Bodies move verbatim.** This is code motion along the test fault line, not a rewrite:
   each family method moves with its private helpers; helpers shared by two families move to
   `LintContext` or a small `LintSupport`. Findings stay byte-identical — code, severity,
   message, position — and the 37 test files must not change.
3. **One strictness mechanism for unknown keys.** The reflective walk generalizes: instead of
   the hand-registered `FIXED_SHAPE_BLOCKS` map, the walk recurses into every record-typed
   component reachable from the document's record (the information Jackson already has),
   applying the same TQL-YAML-1043/1044 contract everywhere — `security:`'s interior checks
   the same way `export:`'s does. `kind: prompt` gets a declared key set and joins the walk.
   The hard-failing surfaces keep their semantics (a view or domain typo still refuses the
   load — those errors are older than the lint and tested); rule sets, decisions and calendars
   gain the walk they never had. The hand-kept sets in `SimpleYamlParser` and the
   `FIXED_SHAPE_BLOCKS`/`STEP_BLOCKS` registration maps are deleted — a new nested block is
   covered the day its record lands, which is the property the registration map could never
   have.
4. **A severity is an enum and a code is a constant.** `LintFinding.Severity { ERROR, WARNING }`
   (the record keeps a string accessor for the wire/JSON shape, so consoles and the extension
   see no change). Every string-literal code moves to a per-family constants interface the
   rule class and its test share; `ErrorCodeUniquenessTest` learns to scan those constants,
   closing the string-literal gap.
5. **Shared codes split where they answer different questions.** TQL-FIELD-2004 keeps its
   most-published meaning (a step's shape is wrong: no work, two bindings, chunk beside a
   binding — one question: "what is this step's work?") and the command-step `enrich:` misuse
   moves to its own code, next free in FIELD. Renumbering follows the campaign rule: the
   documented meaning keeps the number.
6. **Order is preserved, then pinned.** The registry runs in today's order. One new test
   asserts the registry's family order explicitly, so a future insertion is a reviewed
   decision instead of an accident of where a method landed in a 6,000-line file.

## What this deliberately does not do

- No new rules, no severity changes, no behavior changes beyond decisions 3 and 5 — and
  decision 3's only behavior change is *more* findings on previously-tolerant interiors
  (warnings, same codes).
- No plugin/SPI surface. Rules are framework-internal; the registry is a list.
- `lineOf` positions stay as they are per family; extending coverage is incremental work a
  family can do when touched, not a campaign.

## Slices

1. **Registry + first third** — `LintRule`, the registry list, and the families whose tests
   are pure fixtures (decisions, calendars, messaging, duckdb, export-step, step-unit,
   attachments, i18n, …). `AppLinter` shrinks below ~4,000 lines.
2. **Second third** — the security/scope/tenant families (they share ambient-bind helpers →
   `LintSupport`), then routes/webhooks/consumers.
3. **Final third + registry pin** — unknown-keys, mcp, views, jobs; the order-pinning test;
   `AppLinter` ends as context construction + registry (~200 lines).

   *Slices 1–3 shipped as one commit* — the extraction is a single mechanical pass whose
   oracle is the untouched 37-file suite, and splitting it into three PRs would have bought
   review granularity nobody used at the cost of two extra rebase-and-CI cycles over a
   6,000-line move. `AppLinter` ended at 117 lines. Order is preserved exactly: the per-route
   fan-out cannot become separate registry entries (that would run an all-routes rule before a
   per-route one), so families like `HttpCacheRules` and `ExportRules` are classes on the test
   fault line called in place rather than registered — 36 registry entries, 13 shared rule
   classes.
4. **Unknown-key generalization** (decision 3) — the recursive walk, prompt key set, deletion
   of the registration maps and `SimpleYamlParser` hand sets; gallery apps re-linted for new
   true positives before merge.

   *Shipped.* The derivation moved to `model/AcceptedKeys` — creator parameters first, record
   components otherwise — because three packages read it (the lint, the domains/catalogs
   loader, the view loader) and only the model can be the one place it lives. The walk descends
   into a record, into the entries of a collection of records, and into the values of a map of
   records: an author names the keys of that map, so the names are not checked but what hangs
   under each one is, which is what covers a source's binding and a rule set's rules. It stops
   at scalars, at containers of non-records, and wherever the authored YAML shape is not the
   declared one (a shorthand string where a record is declared) — that is the loader's error to
   report. A sequence item is named for what it is rather than for the collection holding it
   (`step 'report' push.pth`), which is the message the pinned tests already carried.
   `kind: prompt` got a real `PromptDefinition` the loader reads, not a key list beside it: a
   shape nothing loads is a shape that drifts. The gallery and the bundled apps produced no new
   findings — the previous campaigns had already fixed the interiors this would have caught.
5. **Codes and severities** (decisions 4–5) — enum, constants, the 2004 split, guard-test
   extension, reference regeneration.

Each slice is a PR with the full yaml suite green; slices 4 and 5 carry CHANGELOG entries.
