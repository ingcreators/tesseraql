# YAML surface drift

> **Status: designed 2026-09-07.** This is audit campaign C7 (findings F31, F48, F49, F50, F51,
> F52, F53, F79, F80, F81), re-measured against `f31481468` before a line of it was written.
>
> **Do not build from `work/repo-audit-2026-09-04/REMEDIATION-PLAN.md`.** Twelve of its claims
> do not survive HEAD, and its centrepiece is mechanically impossible — see
> [What the plan got wrong](#what-the-plan-got-wrong). Its slice 1 would land a permanently red
> build; its slice 3 would ship four dangling anchors onto a published page; its slice 4 would
> undo #1180 and fail to compile.
>
> **And re-measure this document too.** Every count here was produced twice — once by a
> measurement fleet, once by hand — and the two disagreed three times before they agreed. A slice
> that inherits a number from here without recounting it will inherit a wrong one.

## The class of defect

The YAML surface an application author writes has **four descriptions**, and only one of them is
enforced by running code:

| Description | Where | Enforced by |
| --- | --- | --- |
| The model | `io.tesseraql.yaml.model.*`, `ViewSpec`, `TestSuite` | Jackson, at load |
| The shipped JSON Schema | `tesseraql-yaml/src/main/resources/schema/*.json` | The author's editor |
| The lint | `io.tesseraql.yaml.lint.*` | `tesseraql lint`, admission |
| The published reference | `docs/reference-*.md` | Nobody |

The model is the only honest one. The other three have each drifted from it somewhere no test
looks, and the drift is invisible in the ordinary way: nothing is red, because nothing compares
them.

Three shapes of drift, and they need different guards — which is the mistake the plan made by
trying to catch all of them with one walk.

**A node that describes nothing.** `{"type": "object", "additionalProperties": true}` with no
`properties` is a schema that validates everything and completes nothing. The editor offers no
keys, and the generated reference renders the row as "object" or "array of any". Twenty-five
such nodes ship today.

**A node that describes the wrong set.** `response.json` declares four of `JsonResponse`'s six
components. The job pipeline item offers thirteen keys against `PipelineStep.of`'s nine. These
are invisible to any blind-node check — the node *does* describe something, just not the right
thing — and invisible to a `containsAll` check whenever the schema offers *more* than the model
folds, which is the direction that produces silent data loss.

**A generated artifact that inherits and amplifies.** Two dead `TQL-CAMEL-*` codes sit in schema
descriptions and are copied verbatim into five rows of the published reference. The error index
loses the raise-site meanings of 96 codes because it resolves one constant idiom and not the
other. The doc site's landing page still advertises Apache Camel, a dependency removed a campaign
ago.

## What is broken

Everything in this table was verified at `f31481468` by reading the file, and recounted.

| Where | What is wrong | Who feels it |
| --- | --- | --- |
| `tesseraql-view-v1` `children` | Declared `type: object`; `ViewSpec.children` is `List<Child>` and both shipped detail views author a sequence | The editor `tesseraql new` installs marks two of the framework's own example documents invalid |
| `tesseraql-view-v1` `fields`, `columns`, `panels` | Bare `type: array`, no `items` | `docs/reference-yaml-surface.md:716`, `:717`, `:719` render a view's three most important keys as "array of any" |
| `tesseraql-config-v1` `tesseraql.identity` | `{type: object, additionalProperties: true}`, no description, no properties | Zero completion for the whole identity block — while `docs/reference-config.md` lists nine real keys under it |
| `tesseraql-tests-v1` `verify[].sql`, `verify[].expect` | Blind, while `tests[].sql` and `tests[].expect` — the same two shapes — are described in the same file | A read-back step gets no completion; the file contradicts itself |
| `tesseraql-defs-v1` `enrichment.sql`, `enrichment.http`, `shared.export.after.sql` | Blind, while the model types them `Binding.SqlArm` and `HttpSourceSpec` | Three of the most-written arms complete nothing |
| `tesseraql-route-v1` `response.json` | Declares 4 keys; `JsonResponse` has 6 (`headers`, `headersWhen` missing) | A documented feature the editor says does not exist |
| `tesseraql-job-v1` `pipeline[]` | `allOf`s the whole `#/$defs/binding`, offering 13 keys; `PipelineStep.of` folds 9 | `contract:`, `service:`, `sequence:`, `spool:` on a job step are **silently dropped** — `@JsonIgnoreProperties(ignoreUnknown = true)`, and no lint fires because the schema offers them |
| `AppLinter.KNOWN_INPUT_TYPES` | Seven types; `datetime` is honoured in seven main sources and absent from both the linter set and the schema enum | Every scaffolded app with a business timestamp column gets an editor error on a type the framework fully supports |
| `InputRules` | Eight messages open `page: `, a key `UnknownKeyRules.RENAMED_KEYS` turns into an error | The lint names a key that cannot be written — and points at the wrong line |
| `tesseraql-defs-v1:144`, `:193` | Cite `TQL-CAMEL-3101` and `TQL-CAMEL-3114`, renamed to `TQL-ROUTE-*` in #957 | Five rows of the published reference cite error codes that do not exist |
| `ErrorIndex` | Never resolves a reference to a `TqlErrorCode` constant, only to a `String` one | 96 codes publish the declaration's meaning and lose the 366 raise sites that carry the real ones |
| `README.md:64-71` | `serve`, a false `modules/` claim, and `--app-name user-admin-app` against a declared `user-admin` | The repository front page's quick start exits `TQL-APP-4040` on its first command |
| `docs-site/.../index.mdx:7,:38` | "on Apache Camel" in the hero tagline; `tesseraql serve` | The doc site's landing page, and the only tracked file in that directory |
| `ConfigOptions:22` | Its `--repo` text says "Combine with `--offline`". `docs/reference-cli.md` carries that text on **27** command rows and `--offline` on **2**, both `modules` subcommands | The published help names a flag the command does not have, on 25 commands |

## Decisions

### 1 — The centrepiece is a schema-only blind-node deny-list

The plan's recursive model-to-schema walk is **dead, and not only by direction**. It cannot
terminate: `InputField.items` &rarr; `InputItems.fields` &rarr; `Map<String, InputField>` is a real
cycle (`InputField.java:32,172-173`), and the plan's visited set keyed by `(shape, path)` never
prunes because every reachable path differs. The schema side cycles too, at
`defs-v1#/$defs/inputField/properties/items/properties/fields/additionalProperties`.

The replacement reads **only the schema files**. No model reflection, no visited set, no ledger
that shrinks. It answers one question — *does this node describe anything?* — and it answers it
about a JSON document, which is a finite tree with no cycles because `$ref` is never followed.

That is a strictly smaller claim than the plan's, and the guard's assertion message must say so:
"no schema node describes nothing" is **not** "the schemas match the model". The drifts a
schema-only walk cannot see get their own slices (decision 5).

### 2 — What "describes nothing" means, and the four exclusions each node in the corpus forced

A node in a schema position describes nothing when it carries none of: `$ref`; a non-empty
`allOf` / `oneOf` / `anyOf` / `prefixItems`; a non-empty `properties` / `patternProperties`; an
object-valued non-empty `additionalProperties` / `items`; a non-empty `enum`; `const`; `required`;
`not`. A scalar `type` describes itself.

Every clause tests **presence and non-emptiness**. `properties: {}`, `allOf: []`, `enum: []`,
`items: {}` and `additionalProperties: {}` each turn a presence-only predicate green on a node
that describes nothing. The corpus happens to contain none of those today — that is why the
behaviour is untested, not why it is safe.

Four exclusions were forced by real nodes, and each one is a false positive I produced before
excluding it:

- **`required` and `not`.** Four `oneOf` branches in `tesseraql-calendars-v1` and
  `tesseraql-decisions-v1` are constraint-only — `{"required": ["dates"], "not": {"required":
  ["source"]}}` — applied on top of a sibling `properties`. They describe a constraint, not a
  shape.
- **A `$defs` entry may be a namespace.** `defs-v1#/$defs/shared` groups `version`, `id`, `input`,
  `datasource`, `import`, `export` and carries no schema keyword of its own. A walk that treats
  every `$defs` child as a schema reports it as blind **and never descends into it** — which is
  how my own first walker missed `shared.export.after.sql` entirely. Descend a keyword-free
  `$defs` entry as a namespace.
- **Position, not shape, decides what is a schema.** Only the values of `properties`,
  `patternProperties`, `items`, `additionalProperties`, `unevaluatedProperties` and the branches
  of `allOf` / `oneOf` / `anyOf` / `prefixItems` are checked.

With those exclusions: **25 blind nodes across 6 of the 13 schema files** — config 2, decisions 4,
defs 4, route 5, tests 6, view 4. Derived twice, independently, and the two runs disagreed until
each exclusion above was written down.

### 3 — The allow-list names the Java type, and it fires in the stale direction too

Twelve of the 25 are honest: a bind-parameter map, an arbitrary JSON body, a decision table's
cells, a per-connector secret bag. They are allow-listed by JSON Pointer, and **each entry states
the Java type that makes it free-form**. An entry whose reason is prose nothing checks is an entry
the next reader writes falsely to make a red guard green.

The allow-list is also checked in the **stale** direction: an allow-listed node that stops being
blind fails the guard. Otherwise the list silently accumulates entries for nodes that were fixed
years ago, and the next reader trusts a silence it never earned.

| Honest node | Free-form because |
| --- | --- |
| `config` `…poll.credentials.additionalProperties` | Per-connector secret bag; no record |
| `decisions` `default`, `rows[].when`, `rows[].outputs` | Decision-table cells, keyed by the table's own declared inputs/outputs |
| `decisions` `outputs.*.enum` | The output's value space: any scalar the table yields |
| `defs` `inputField.default` | `Object defaultValue` |
| `route` `response.json.body` | `Object body` — arbitrary JSON |
| `route` `response.html.headers` | `Map<String, Object>`; `ResponseHeaders` interpolates recursively and JSON-serialises a nested value |
| `tests` `params`, `principal.claims`, `expect.rows[]`, `verify[].params` | `Map<String, Object>` bind values and result rows |

### 4 — Openness is a recorded design decision in four schemas; the view schema is the exception

`tesseraql-route-v1` and `tesseraql-job-v1` carry the same root `$comment`:

> additionalProperties stays true so newer keys never break older editors; the recipe enum is
> kept in sync with the linter by a build-time test.

`tesseraql-config-v1` and `tesseraql-tests-v1` say the same thing in their own words. **So this
campaign does not close anything.** The plan's instruction to set `additionalProperties: false` on
the response arms and the job pipeline would silently reverse a design position recorded in the
file being edited, across four document families that share the route schema. If that position
should change, it is its own decision with its own blast radius — not something smuggled into a
description fix.

`tesseraql-view-v1` is the exception, and it argues the other way:

> A view document is strict: the loader refuses an unknown key at every nesting level
> (TQL-VIEW-3314), so this schema declares additionalProperties false to match.

Its root does. Its four blind nodes are exactly where the file fails its own stated contract, so
the new view definitions **are** closed. The schema's own comment is the warrant.

There is a second reason not to reach for `additionalProperties: false` here even if we wanted
it: it cannot close an `allOf`-composed node, because each branch sees the whole object. Draft
2020-12's `unevaluatedProperties` is the correct tool and the repo uses it nowhere today. Noting
it so the next person does not rediscover it as a bug.

### 5 — Three drifts a schema-only walk cannot see, and the direction that hides them

A blind-node walk sees nothing wrong with a node that declares the *wrong* keys. Three do, and
each needs its own assertion:

- `response.json` declares 4 of `JsonResponse`'s 6.
- The job `pipeline[]` item offers 13 keys; `PipelineStep.of` folds 9. The four extra —
  `contract`, `service`, `sequence`, `spool` — are dropped in silence.
- The `service:` arm's model still accepts `mode:` and `expect:` that `ServiceStep` does not
  honour. (#1180 split `ContractCall` out of `NamedCall` and closed the contract half; this is
  the residue.)

**Use exactness, never `containsAll`, wherever the direction matters.** `containsAll` passes on an
over-offering — a documented key the loader drops — and it is the dominant idiom in
`SchemaSyncTest` (five tests). The plan's own guard for the job pipeline was `containsAll` and is
green on the very defect it names. Say this in the new test's javadoc, because the nearest
neighbour an implementer copies is wrong.

### 6 — A new shape is a top-level `$defs` entry in `tesseraql-defs-v1`, never a local `$defs`

`SchemaReference.resolve` resolves **every** `$ref`, local or cross-file, against the shared defs
document:

```java
String ref = node.get("$ref").asText();
return defs.at(ref.substring(ref.indexOf('#') + 1));
```

So a local `#/$defs/child` inside `tesseraql-view-v1` resolves against `tesseraql-defs-v1`, finds
nothing, and renders `array of [child](#child)` with no such section on the page. A property
pointer such as `#/$defs/binding/properties/sql` fails the same way, because `defLink` strips to
the last path segment.

Two consequences:

- The view field/column/child/panel/series shapes go into `tesseraql-defs-v1` as top-level
  `$defs` entries and are `$ref`'d cross-file.
- **The arm promotion must land before the job-pipeline narrowing**, not after. The plan states
  the reverse. Narrowing first means `$ref`-ing property pointers, which costs four dangling
  anchors and nine deleted subsections on a published page — and nothing guards that:
  `GeneratedReferenceTest` only compares the file to the generator's output, and
  `PublishedLinkReachabilityTest` only looks for `](../`.

### 7 — F31 is a generator defect, and the mechanism is neither the one the plan names nor the obvious one

`TQL-ROUTE-3100` is raised at eight sites with eight meanings, and `docs/reference-error-codes.md:397`
carries one:

```
| `TQL-ROUTE-3100` | Route '…': unknown recipe '…' | — | [RouteCompiler.java] |
```

The plan says this is because "`ErrorIndex` resolves a constant's meaning from the first use site
in the file". **It is not.** `meaningCell` (`ErrorIndex.java:607-619`) publishes up to *two*
meanings joined with ` · ` and appends ` …` when there are more — so a code with eight recovered
messages would render two and an ellipsis. This row shows one and no ellipsis, which means the
index recovered exactly one message.

The real cause is a blind spot with a much wider blast radius. `ErrorIndex` resolves a raise site
that *names a constant* only for the `static final String NAME = "TQL-…"` idiom
(`STRING_CONSTANT`, `collectReferences`). A code held in a **`TqlErrorCode`** constant matches only
`CONSTRUCTED`, and only at its own declaration — so every `throw new TqlException(NAME, "…")` that
references it is invisible, and the single published meaning comes from the declaration site alone.

Measured at HEAD over 1021 main sources: **96 distinct error codes are thrown with a message at two
or more sites in their declaring file, covering 366 raise sites**, and every one of those messages
is lost. `TQL-ROUTE-3100` is the sixth worst; `TQL-LD-2810` is raised at twenty sites.

So the fix is to resolve `TqlErrorCode` constant references the way `String` code constants are
already resolved. That corrects 96 rows where splitting the code corrects one.

Splitting is also wrong on its own terms. **Six** of the eight refusals already have an
anticipating lint code — the plan says three, missing `TQL-YAML-1002` (`RouteRules.java:24`),
`TQL-WORKFLOW-3106` (`WorkflowRules.java:70`) and `TQL-YAML-1011` (`InputRules.java:20`), all three
verified present. The plan's proposed `WORKFLOW-3121` would duplicate `TQL-WORKFLOW-3106` and its
`ROUTE-3123` would duplicate `TQL-YAML-1011`, which is the defect class `ErrorCodeUniquenessTest`
exists to stop. If per-refusal codes are wanted later, reuse the six that exist rather than minting.

### 8 — `datetime` gets the binder default, not just the vocabulary

Widening `KNOWN_INPUT_TYPES` and the schema enum is half the job. `ViewFields.java:173` renders
`type: datetime` as `<input type="datetime-local">`, which submits `yyyy-MM-ddTHH:mm`;
`InputBinder.coerce` falls through to `ColumnValues.DEFAULT_DATETIME`, which is
`yyyy-MM-dd HH:mm:ss`. **A formatless `datetime` cannot round-trip through the widget the
framework itself picks for it.** The scaffolder only dodges it by always emitting an explicit
`format:` beside the type.

So the slice defaults a formatless `datetime` to the T-separated form the widget submits.
Widening the vocabulary without this would make a silently broken configuration officially valid.

The unknown-input-type lint (F50) is **deliberately not in this campaign**. It is a new ERROR, and
`AdmissionProfile` promotes every lint ERROR to an admission failure, so landing it makes every
app with a business timestamp column un-admittable. It also has an unresolved scope question:
`InputRules.lint` iterates `manifest.routes()` only, so a route-shaped check is blind to
consumers, tools and jobs, and `items.type` needs a different vocabulary. Filed, not built.

### 9 — Never hand-edit the `.vscode` copies, and the dogfood guard is not free

The thirteen schemas under `examples/scaffold-demo-app/.vscode/` are **generator output**
(`AppScaffolder.java:77-`), byte-identical to the source copies at HEAD (all 13 pairs diffed).
Hand-editing them violates AGENTS.md rule 1. Regenerate:

```
./mvnw -pl tesseraql-maven-plugin test -Dtest=ScaffoldDogfoodIntegrationTest \
    -Dtesseraql.scaffold.regenerate=true
```

`ScaffoldDogfoodIntegrationTest.galleryAppIsExactlyTheGeneratorOutput` walks the whole gallery and
compares content per path, inside the plain `./mvnw -B -ntp verify` of the CI verify job.
**#1180's commit message — "Nothing in CI compares those two copies" — was already false when it
was written.** So no slice needs to build that guard.

It has one hole worth a line of test: it compares the copies through `AppScaffolder`'s hardcoded
thirteen-entry list, so a schema added to `src/main/resources/schema` and never registered there
is invisible to it. And it needs Docker, so a hand-edit is red only in a Testcontainers test 26
modules later. A Docker-free byte-identity assertion between each source schema and its copy,
with the file set **derived from the directory**, closes both.

### 10 — Sequencing: the fixes land first, and the guard's proof is the broken variants

Dropping the shrinking ledger has a price, and it should be stated rather than discovered. There
is now nothing to absorb the drift nodes, so the guard cannot land before the fixes — it would be
red on 25 nodes. It lands after them, **green on arrival**.

A guard that is green on arrival has no red run to point at. This repository has been burned by
exactly that four times in the last campaign alone: a checksum rehearsed only where `unzip`
exists, a predicate green on the unfixed `HealthRoutes`, a probe that died in `verify` before
reaching the `deploy` it was testing, a drain test that returned before it ever parked.

So for the guard slice, **the broken-variant suite is the deliverable, not a nicety**:

- an honest allow-listed node turned blind &rarr; must be RED;
- a new blind node in a schema file the guard never named &rarr; must be RED;
- a *new schema file* added to the directory containing a blind node &rarr; must be RED;
- an allow-listed node that has been fixed &rarr; must be RED (the stale direction);
- `properties: {}`, `enum: []`, `items: {}` and `additionalProperties: {}` &rarr; each must be RED;
- the schema directory pointed at a path that does not exist &rarr; must be RED, not green;
- **a negative control**: the corpus as committed &rarr; must be GREEN, so an over-firing
  predicate is caught too.

Each variant's failure text goes in the PR body. No variants run, no merge.

The same rule binds the `$ref`-resolvability loop (decision 6): all 37 edges resolve today, so it
too is green on arrival, and seven of this campaign's fixes *are* `$ref` promotions — the guard
would otherwise be green on its own fix's most likely failure.

### 11 — Which prose gate actually binds

The plan spends two slices keeping schema descriptions under `lint-prose`'s 60-word limit.
`lint-prose.mjs:65` is `if (slug.startsWith('reference-')) continue;` — **it never reads a
generated reference page**, so that limit binds no schema description.

What does bind, and is mentioned nowhere in the plan, is `sync-content.mjs:52`:

```js
const INTERNAL_VOCAB = /\b(?:Phase \d|[Mm]ilestone M?\d|Horizon \d|slice \d|roadmap|design ch\.)/;
```

It applies to every mapped slug — `reference-yaml-surface` is mapped at `nav.mjs:121` — with no
`reference-` exemption and no table-row exemption, and schema descriptions flow verbatim into
table cells. A description copied out of a campaign design document, written in exactly that
vocabulary, fails the Astro job. Neither guard runs in `mvn verify`; run both by hand.

`docs-site/src/content/docs/*.md` is gitignored (`docs-site/.gitignore:5`). `index.mdx` is the
only tracked file there.

## The slices

Twelve PRs. Each branches from a freshly-fetched `origin/main`; each carries a test that is red
against the code it lands on.

| # | Title | Size | Closes |
| --- | --- | --- | --- |
| 1 | this design document | S | — |
| 2 | the `sql` and `http` arms are one shared shape | M | — |
| 3 | a view document's nested shapes are described | L | — |
| 4 | the tests and config schemas describe what they accept | M | — |
| 5 | a JSON response can declare its headers | M | F52 |
| 6 | a job step and a service arm offer only what they honour | M | F48 |
| 7 | a schema node that describes nothing is refused | M | F53 |
| 8 | the schemas cite the codes the framework raises | S | F79 |
| 9 | an error code carries every meaning it is raised with | M | F31 |
| 10 | a documented command line names a verb, a flag and a value the CLI has | M | F80 |
| 11 | `datetime` is a declared input type | S | F49 |
| 12 | the pagination rules name `pagination:`, the key that exists | S | F51 |

Slices 2 through 6 are the fixes; 7 is the net that catches the next one. 2 must precede 6
(decision 6). Nothing else is ordered.

Not in this campaign, and why: **F50** (the unknown-input-type lint) would make existing apps
un-admittable and has an unresolved scope question (decision 8). **F81** (the config scanner's
regex) is generated-reference drift over Java sources rather than YAML surface drift; its
one-line half — `ConfigOptions:22` — rides slice 10. **`SchemaDescriptionCoverageTest`'s
nine-file list**, which is blind to 73 undescribed properties in `config`, `tests` and
`calendars`: real, but 73 sentences of prose that would drown slice 7's own redness. All three
are filed, not dropped.

## The guards this campaign adds

Each is stated at the strength it actually has, because a message that overclaims is what makes
the next reader trust a silence the guard never earned.

| Guard | Claim | Not a claim |
| --- | --- | --- |
| Blind-node deny-list | No schema node describes nothing, and no allow-listed node has quietly been fixed | That the schemas match the model |
| `$ref` resolvability | Every `$ref` in every schema lands on a node that exists | That it lands on the *right* node |
| Response-arm exactness | Every response arm declares exactly its record's components | That the arm set is complete |
| Pipeline-item exactness | The job pipeline offers exactly what `PipelineStep.of` folds | Anything about route `steps:` |
| Cited-code liveness | Every `TQL-*` literal in a schema resolves in the error index | That it names the rule that raises it |
| Command-line validity | A documented invocation names a verb, a flag and an app that exist | That the command succeeds |
| Schema-copy identity | Each `.vscode` copy equals its source, file set derived from the directory | That the copy set is complete without the derivation |

Every one asserts non-vacuity first, per root and not globally: a floor a disappearing root
actually trips. `assertThat(x).isEmpty()` and AssertJ's `allMatch` both pass on nothing at all,
and a directory that does not exist is the failure this campaign has already reproduced once, by
accident, on its own measurement script.

## What the plan got wrong

Twelve, worst first. Every one was verified against `f31481468`.

1. **The centrepiece cannot terminate.** `InputField.items` &rarr; `InputItems.fields` is a real
   cycle and the specified `(shape, path)` visited set never prunes. `KNOWN_DRIFT` exists nowhere
   in the repo, so every later slice's "delete its ledger entry" has no referent.
2. **Its ten-entry seed is nine, and one of the nine cannot fail its own walk.** `job:pipeline[]`
   is an *over*-offering: 9 &sube; 13, so `containsAll` passes. The plan's shrink-only assertion
   then fires on that entry the moment slice 1 lands — a permanent red.
3. **It never opens nine of the thirteen schemas.** Four drift nodes live in `config-v1` and
   `tests-v1`, which its three model roots structurally cannot reach. Both are live editor
   surfaces mapped in `.vscode/settings.json`.
4. **Its slice 4 would undo #1180 and fail to compile.** `ContractCall` already exists with six
   components; the plan's four-component signature deletes `materialize` and `timeoutSeconds`.
   And `NamedCall` *is* referenced outside `Binding.java` — at `ContractBindingBoundsTest.java:54`,
   added by #1180 itself.
5. **Its slice 3/slice 5 ordering is backwards**, and costs four dangling anchors and nine deleted
   subsections on a published page that nothing guards (decision 6).
6. **A local `$defs` in `view-v1` renders dangling anchors** — `SchemaReference` resolves every
   `$ref` against the shared defs root (decision 6).
7. **Closing the arms reverses a recorded decision** in the file being edited (decision 4).
8. **F31: its diagnosis of the generator is wrong, and so is its count.** `ErrorIndex` does not
   "resolve a constant's meaning from the first use site" — it renders up to two meanings and
   never resolves a `TqlErrorCode` reference at all. Six of the eight refusals already have an
   anticipating lint code, not three, and two of the plan's proposed new codes duplicate existing
   ones (decision 7).
9. **The prose gate it optimises for does not read the page it is optimising**, and the one that
   does is never mentioned (decision 11).
10. **"Every schema slice edits BOTH copies"** describes hand-editing a generated artifact, and
    "nothing in CI compares those two copies" is false (decision 9).
11. **Its regen ritual has the trap in the wrong place.** `ReferenceGenerator` reads the schemas
    from the filesystem, so the `install` is unnecessary for that page; but `exec:java` rewrites
    **all four** reference pages and resolves `tesseraql-cli` from `~/.m2`, so a stale jar
    silently regenerates `reference-cli.md` from a stale picocli model. Check
    `git diff --stat docs/` after every regen rather than trusting the command.
12. **Its F80 scope misses the two highest-visibility sites** — `index.mdx`, the doc site's landing
    page, and `README.md`'s quick start, which #1211 *shipped broken* while fixing this very
    defect class: `--app-name user-admin-app` against a declared `user-admin`. #1211's own message
    claimed "the form ci.yml already smoke-tests"; `ci.yml` uses `--app-name inventory`, a
    different value. That is how the wrong one shipped.

Counts the plan gets wrong by one or two, all recounted here: seven `page:` messages (eight),
three `lineOf` sites (four), three anticipating lints (six), ten seeded drift nodes (nine),
twenty-three blind nodes on my own first pass (twenty-five). **Recount everything.**
