# Deterministic output

> **Status: in progress.** Eight slices, closing the findings the 2026-09-04 whole-repo audit filed
> as F27, F33 and F74 — plus the two the audit's own finder and the remediation plan both missed,
> which are the worst instances in the set.
>
> **The plan this document replaces was re-measured twice**, once against `ecdd5d6c8` and again
> against `a8c34e6f7` after the base-path emission campaign moved several of its cited lines. The
> corrections are recorded in [What the plan got wrong](#what-the-plan-got-wrong). Read the
> decisions below, not the plan.
>
> **And re-measure this document too.** Slice 2 re-measured it against `e441b971f` and corrected
> five of its six decisions — including its own guard for the signed documents, which as specified
> was green half the time. Each correction is recorded inside the decision it belongs to. A slice
> that inherits a count or a line number from here without re-measuring it will inherit a wrong
> one.

## The class of defect

`Map.of(…)` and `Map.copyOf(…)` iterate in an order derived from a per-JVM hash salt. The order is
stable within one process and different across processes. So any map that is *built* with one of
those factories and later *iterated* to produce output makes that output vary between runs of
identical code against identical input.

The repository has rediscovered this eight times and fixed it eight times, each time at one site,
each time with a hand-written `LinkedHashMap` and a private comment, and never once by naming the
rule. `RouteDefinition`'s compact constructor is the pattern in miniature: line 100 is
`Map.copyOf(input)`, and the five fields immediately below it are
`Collections.unmodifiableMap(new LinkedHashMap<>(…))` with a comment at :101 explaining why —
"Insertion-ordered so command steps and named sources run in their authored order." Somebody
worked this out for `steps:` and did not carry it one line up to `input:`.

## What is broken

| Where | What varies |
| --- | --- |
| A scaffolded create form | Its fields render in a different order on every boot. Measured on `scaffold-demo`: six inputs, twelve distinct orders across eighty fresh JVMs, the declared order appearing **zero** times. `ViewFields` promises the opposite and has no test. |
| The first reported field error | Which field a validation failure names is whichever the salt put first. |
| An MCP tool's `inputSchema` and its `required` array | An agent reading the tool list sees a different contract per boot. |
| An outbound query string | A partner logs a different URL for the same call. |
| A persisted outbox payload and a webhook body | Two identical events serialize to different bytes. |
| `config/flags.yml` | Studio rewrites the author's committed file with every key reshuffled when a flag is toggled. |
| **`SbomGenerator`** | Three salted `Map.of` calls reach the CycloneDX document — the metadata component at `:48`, and a hash object built once per file component at `:55` and once per library component at `:73-74` — under a javadoc at `:40` that says "ordered by purl **for reproducibility**". |
| **`ReleaseEvidence`** | `:29` builds `Map.of("name", …, "version", …)` into the evidence document, under a javadoc at `:17` that says "The document is **deterministic** … so it is **reproducible and signable**" — and the build then Ed25519-signs those bytes. |

The last two are the worst instances and neither the audit's finder nor the plan names them. A
document whose own javadoc promises reproducibility, which the build signs, and which does not
reproduce, is the sharpest form this defect takes. Both are closed by slice 8, which found the SBOM
worse than the row above states: the salted hash object is emitted **once per component**, so the
guard reported 63 salted nodes for the gallery app, 62 of them hash objects.

The signature never fails: the Mojo signs the bytes it just wrote. The harm is precisely that two
builds of identical source produce different bytes, which is what a reproducible build claim means.

## Decisions

### 1 — One primitive, and it has two methods

`OrderedCopies` was created by slice 2 as `io.tesseraql.core.util.OrderedCopies`, and it takes
**two** methods rather than one:

- `map(Map)` — insertion-ordered and **null-rejecting**, matching `Map.copyOf`'s contract and
  naming the offending key in the message, which `Map.copyOf` cannot.
- `mapAllowingNulls(Map)` — spelled out, for the sites that carry null values deliberately.

The second method is not a convenience. `Map.copyOf`'s `NullPointerException` is the load-time
guard for a null value in a model map, and `ManifestLoader` dereferences without checking. A
one-method `OrderedCopies` that tolerated nulls would silently delete that guard at every model
site it swept — an ordering change that quietly converts a named schema error into a raw
`NullPointerException` much later.

Slice 2 re-measured how that guard actually works, and three things it assumed are not true.
`SimpleYamlParser` does not catch the `NullPointerException`: it has no `NullPointerException`
catch at all, only a broad `catch (IOException | RuntimeException)` repeated at twelve sites, whose
own javadoc names Jackson failures and SnakeYAML limits and never a null value. The guard is real
but undocumented and unowned — nothing warns whoever narrows that catch — and **no test pins it**;
the parser's fuzz test provably cannot reach it, because its token set contains no map-valued key
and its seed is fixed. It is also not the *only* such guard: `ResponseHeaderDefaults` hand-writes
one that raises `TQL-SEC-4135`, and that one *is* pinned by a test. Finally, one site is outside
the laundering entirely: `FlagsSpec` builds its map outside every parser `try`, so a flag authored
with no value throws a raw `NullPointerException` out of `FlagsSpec.load` rather than a coded
error. Filed, not fixed — `OrderedCopies.map` names the offending key in the message, which makes
that raw failure diagnosable without changing what is thrown.

The null-tolerant client set is **three** sites that record their reason in a comment
(`ScopeResolver`, `Principal`, `TransitionSpec`), not the four claimed here or the three the plan
implies — the fourth, `TestSuite`, tolerates nulls with no comment at all, and its null-tolerance
is inferable only from a data flow two files away. Each of the three already hand-rolls
`Collections.unmodifiableMap(new LinkedHashMap<>(…))` precisely because it tolerates nulls, and
each keeps its reason in a comment when it moves. Two further candidates named in passing during
the survey are not clients: `DocViews` builds a `LinkedHashMap` rather than copying one, and
`JoinKeys` copies a `List`.

### 2 — The ledger is the guard; a behavioural order test is not

Two builds of identical source producing identical bytes is a property of the whole tree, and no
test of one form's field order proves it. So the deliverable is a source-scan ledger in
`tesseraql-docs-reference`, beside `SqlExecutorLedgerTest` and `HttpClientLedgerTest`: every
`Map.of`/`Map.copyOf`/`Set.copyOf` in the scoped packages either goes through `OrderedCopies` or
names a recorded exemption.

Behavioural order tests still ship, one per converted surface, but they are the *evidence* for a
slice rather than the campaign's guard. **Every one must be verified RED on the pre-fix branch with
its actual key names before its pull request opens** — the salt is a rotation of a hash-determined
slot cycle, not a uniform shuffle, so a small map can agree with insertion order on most boots and
a five-key assertion can pass by luck.

Slice 2 measured the rule that makes this checkable rather than merely prudent. A map's slot layout
is a pure function of `String.hashCode` and the argument order; the salt chooses only a starting
slot and a direction. So an `n`-entry map has **exactly `2n`** reachable iteration orders for
`n >= 3` — measured 8 at `n = 4`, 12 at `n = 6`, 14 at `n = 7` — and exactly 2 at `n = 2`, where the
declared order therefore comes up on about half of all boots whatever the key names are.

The consequence is that redness is **binary per key set**, not probabilistic in general. Either the
declared order is one of the `2n` reachable orders, or it is not. If it is, the assertion passes on
a sizeable fraction of boots — measured between 10% and 35% for the three-key sets checked, against
the 17% a uniform model would predict, because the reachable orders are not equiprobable. If it is
not, the assertion fails on every boot.

So a slice does not sample its test's redness, it *chooses key names that make it red*, and records
that it enumerated all `2n` orders and the declared one is absent. Slice 2's six flag names do
exactly that: twelve reachable orders, the authored one not among them.

That gives the campaign a hard floor, which slice 3 measured and which no earlier slice knew.
`2n >= n!` exactly when `n <= 3`, so for two- and three-key maps **every** permutation is
reachable and the declared order is therefore always among them. **No order assertion on three
keys or fewer can be red on every boot**, however the names are chosen. Four keys is the minimum
for a provable evidence test, and a slice whose surface only ever carries three keys needs the
ledger rather than a behavioural test.

### 3 — The scanner strips comments before it matches

Some of the files in scope match the census pattern **only inside a comment** — a comment saying
that this site deliberately avoids `copyOf`. Two consequences, and both are traps:

- Slice 7 deletes those comments, so a whole-file matcher moves the census under the very pull
  request that writes the ledger.
- A future author who writes the *correct* explanatory comment gets a red build for it.

So the scanner lexes out comments first and matches code only. This is the same shape as the
`ErrorIndex` trap the 2-way SQL parser campaign recorded, arriving from the other direction — and
that trap is still live at HEAD rather than historical. `ErrorIndex` lexes comments for the meaning
column, but `collect` adds provenance unconditionally. So a `TQL-*` code named in a comment in a
new file still lands that file on the generated page and forces a regeneration.

Slice 2 re-measured the count and it is **three, not four**, and only under a matcher that drops
the trailing parenthesis (`McpServer`, `ReportDoc`, `LintContext`). Under the census pattern as
this document writes it — with the parenthesis, the way all fourteen existing ledger tests write
theirs — the comment-only count is zero, because `InputField` and `TransitionSpec` both match in
code via a zero-argument `Map.of()`. That is not a reason to drop the decision: nine further files
carry the name in *both* code and comments, so a whole-file matcher still moves under slice 7.

It does mean the scope numbers in this document cannot be inherited. "Forty-three files in scope,
not thirty-nine" is not one rule measured twice — it is one fifteen-package scope measured with two
different regexes, and the plan's regex reaches 39 only by adding `Collectors.toMap(` and
`Collectors.toSet(` and dropping `Map.of` entirely, none of which this document mentions. The
stated pattern and the stated count are mutually inconsistent: the stated pattern matches 95 files
in those packages and 249 across the tree. **Slice 7 decides the pattern first and derives its own
count** — including whether `Set.of` belongs in it, which neither this document nor the plan
considers, though `ErrorIndex` holds a ninety-element `Set.of` that is returned to a caller.

### 4 — The signed documents get their own slice

`SbomGenerator` and `ReleaseEvidence` are not swept with the model classes, and their correctness
claim is stronger than everyone else's — their javadoc promises it and the build signs it. They
land as one slice.

Slice 2 re-measured the rest of this decision and three parts of it are wrong.

**The reason is wrong.** The ledger's regex is not what misses them: both files are outside the
ledger's package scope entirely (`yaml/sbom` and `yaml/release` are not among the packages it
walks), so it would not see them however well its regex matched. The conclusion — a separate slice
— survives; the justification does not.

**The site is wrong, and there are more of them.** `SbomGenerator:47-48` is not "a four-entry
`Map.of`". It is a *one*-entry outer `Map.of` wrapping a *three*-entry inner one, and a one-entry
`Map.of` is an `ImmutableCollections.Map1` with no table and no salt — it cannot vary at all. Only
the inner map varies. Two salted sites this document names nowhere matter more: `:55` builds a
two-entry hash object once per **file** component and `:73-74` builds one once per **library**
component, so between them they vary far more of the document than the single metadata block. The
two `Map.of` calls at `:78` are both one-entry and are exemptions, not conversions.

**The guard was wrong, and it was the sharpest instance of the trap decision 2 exists to
prevent.** "Generate twice in separate JVMs, compare the bytes" is itself a lucky test.
`ReleaseEvidence`'s only salted site is one two-key map, so two fresh JVMs emit identical bytes
about **half** the time: the guard as specified was red on roughly one run in two and green on the
other. For `SbomGenerator` it was red on about five runs in six. Reaching a negligible flake budget
that way needs upwards of twenty forks.

Slice 8 replaced it rather than tuning it. `ReleaseDocumentOrderTest` walks the two built document
*trees* and fails on the **cause**: any node that is an `ImmutableCollections$Map` of two or more
entries. Which class `Map.of` returns is decided by the call site's arity and never by the salt, so
the assertion is red on every pre-fix boot and green on every post-fix boot, in one process, with
no forking — and it names the offending path rather than reporting a byte mismatch. It is
deliberately a **deny-list**: an allow-list naming `LinkedHashMap` would go red the moment a site
moved to `OrderedCopies`, which returns an unmodifiable view.

That guard generalises. Any document assembled in memory and then serialized can be checked this
way, and it is strictly better than a behavioural order test wherever it applies, because it needs
no key-set enumeration at all.

### 5 — `ResponseHeaders` is the emission point, not `ResponseSpec`

A declared response header block is re-copied where it is emitted, so converting `ResponseSpec`
alone changes nothing observable and the slice's own promised test would fail after the fix. The
conversion belongs at the emission point, and the test asserts on the wire.

Slice 2 said there were three salting layers and that the fix belonged at `ResponseHeaders`.
**Slice 4 measured the whole path and this decision is wrong at its root.**

`Response` — the pipeline's own response object, which every renderer writes headers into — holds
them in a `TreeMap(String.CASE_INSENSITIVE_ORDER)`. **The wire is alphabetical.** Response-header
order is not observable on the wire at all, so "the test asserts on the wire" cannot be done, and
every downstream copy (`ResponseHeaders`, `ErrorResponseRenderer`, the two lookup processors) is an
exemption rather than a conversion.

**And there is a trap under that.** A test written with `java.net.http.HttpClient` would sort the
headers again on the way in — `HttpHeadersBuilder` holds them in its own case-insensitive
`TreeMap` — so a wire-order assertion written the obvious way is green on a broken build **100% of
the time**. That is worse than decision 2's lucky test: it is a test that can never fail. Nothing
in this campaign may assert response-header order through an HTTP client.

The order *is* observable, in the place nobody looked: the **linter**. `ResponseHeaderRules`
iterates the declared map and the defaults map unsorted at three sites, straight into the finding
list, and `tesseraql lint --format json` serializes that list verbatim. Two runs over identical
sources emitted different JSON bytes — a persisted-artifact defect, which outranks the wire claim
on this campaign's own terms. So the conversions are `ResponseHeaderDefaults` and `ResponseSpec`,
and the reason is the linter, not the wire.

### 5a — The step-result map is an answer only where no response is declared

Slice 5 measured where `TransactionalCommandProcessor`'s step-result map actually reaches a
caller, because it is the third instance of decision 5's shape and the narrowest. The map is built
as a `LinkedHashMap` in authored step order and then copied with `Map.copyOf` at the very exit —
careful ordered construction, discarded one line before it leaves.

It reaches a caller **only** through `mcpToolRenderer`, whose own javadoc says it renders "its
declared JSON shape, or the raw SQL/command result". An MCP tool that declares no `response:` is
answered with that map verbatim, and its keys are what an agent reads. The HTTP path never sees
it: `RouteCompiler.responseRenderer` dereferences `definition.response().json()` unconditionally,
so a `recipe: command-json` **route** with no `response:` block does not serve a raw step map — it
fails to compile at boot. That correction cost one wrong fixture; record it rather than re-derive
it.

This is also the row the campaign had mis-filed. F33 is this defect, not slice 6's payload row.

### 5b — The payload row is two defects of opposite shapes, and closes no finding

Slice 6 re-measured its own row. "A persisted outbox payload and a webhook body" reads as one rule
and is two, of opposite shapes:

- The **model** specs are already correct. `NotifySpec` and `PublishSpec` both hand-roll
  `Collections.unmodifiableMap(new LinkedHashMap<>(payload))`, so a slice that swept the model here
  would have converted correct code and shipped a green test proving nothing.
- The **emission points** are the defect, and there are two: `NotifyEvents.Envelope` and
  `PublishEvents.Envelope`. Each one's own `parse` builds the payload as a `LinkedHashMap` in the
  order the JSON carried it, and the record's compact constructor twenty lines further down copies
  it with `Map.copyOf`. Decision 5's shape again, twice, in two files that are otherwise identical.

Both take the null-**permitting** method, and that is not a preference. `Map.copyOf` there threw a
raw `NullPointerException` out of `parse` for any payload whose expression resolved to nothing — a
live crash on the delivery path, reproduced and now pinned by a regression test. Neither the audit
nor this document filed it.

The row's "in the file" reading is the export path: `ExportModel`, `SplitExport.narrow` (a third
build-then-discard), and the two `FileTransferService` request records. Converted for the same
reason, without a behavioural test — their order is not independently observable today, and an
exemption whose reason is "probably not iterated" is not worth writing.

**This slice closes no audit finding.** F33 is the step-result map, which slice 5 closed.

### 6 — This campaign owns `project.build.outputTimestamp`

F74 is double-owned: the plan's campaign map assigns it to the release campaign and its slice list
files it here. It lands here, and the claimed ordering dependency on pinning the lifecycle plugins
is dropped — that dependency was measured false, and nothing has ever shipped for it (no `pom.xml`
in the tree contains the string; `git log -S 'outputTimestamp'` now returns exactly one commit, the
one that added this sentence). A reproducible jar is worth
little while the documents inside it are not reproducible, which is the other reason it belongs
beside decision 4 rather than in a release campaign that is parked.

Slice 8 shipped it and measured both halves. The property alone is not enough: `maven-jar-plugin`
is the archiver that has to honour it, and it was the one lifecycle plugin the root
`pluginManagement` did not pin — so the plugin the reproducibility claim depends on was the plugin
the build did not choose. Both land together. Measured on `tesseraql-core`: without the property,
two clean builds produced different jars (`15f4ba13…` and `c04bcbb1…`); with it, two clean builds
produced the same jar (`29a36549…` twice).

## The slices

Each is one pull request, branched from fresh `origin/main`.

| # | Slice | Size | Closes |
| --- | --- | --- | --- |
| 1 | This design document, registered in both internal-doc lists | S | — |
| 2 | `OrderedCopies`, its two methods, and `config/flags.yml` keeps the author's key order | M | F27 |
| 3 | A declared `input:` keeps its order — route, job, and the import re-copy | M | F27 |
| 4 | The model's remaining maps keep their declared order, response headers included | M | F27 |
| 5 | An MCP tool answers in authored step order | S | F33 |
| 6 | A declared payload keeps its key order on the wire and in the file | M | — |
| 7 | The ordered-copy ledger | L | the guard |
| 8 | The signed documents reproduce, and the build stamps a reproducible timestamp | L | F74 |

Slices 3 through 6 are independent once slice 2 lands, so they can be written in any order; slice 7
must be last of those seven, because it is the census over what they left.

## The guards

- `OrderedCopyLedgerTest` — decision 2, the campaign's real deliverable.
- A byte-for-byte double-generation assertion for the SBOM and the evidence document — decision 4.
- One behavioural order test per converted surface, each verified red before its slice.

## What this breaks

Nothing an application declares, and nothing an application can observe except by having depended
on an order that was never stable. The risk runs the other way: a sweep that changes a map's
identity can change *null* behaviour, which is what decision 1 exists to prevent.

## Open questions

1. **The exemption vocabulary.** Seventeen of the matched files are converted by no slice above and
   need either a conversion or a recorded exemption. Whether the exemption is a comment marker or a
   list in the test is a decision the ledger slice makes.
2. **`Set.copyOf`.** The same salt applies. It is in scope for the ledger and out of scope for the
   conversions above, because no shipped output iterates one — that should be re-measured before
   slice 7 rather than assumed. **Slice 2 re-measured the premise and it is false.** `LintContext`
   already carries a shipped comment saying the opposite in as many words — "Not `Set.copyOf`: the
   declaration order feeds finding messages, and `copyOf` randomizes it" — so a set's iteration
   order does reach output today, and the site that proves it has already been hand-fixed. The
   question stays open, but it is now "which sets, and does `OrderedCopies` grow a third method",
   not "does this happen". Slice 2 deliberately did not grow that method: the decision is slice 7's
   and building ahead of it is how a shipped slice gets reverted.

## What the plan got wrong

Recorded so nobody re-derives them from `REMEDIATION-PLAN.md`.

1. **The salt model.** The plan reasoned about `n!` orderings. `ImmutableCollections.MapN` iterates
   a rotation of a hash-determined slot cycle, so there are roughly `2n` — which is why a small map
   agrees with insertion order often enough for a careless test to pass.
2. **`RouteDefinition` is not a sweep.** Only `input` is salted there; the five fields below it are
   already insertion-ordered on purpose, and converting the rest is churn on correct code. But
   **slice 3 measured that "one line" to be one line in that *file* and three in the *slice***:
   the same `input:` construct is salted at `JobDefinition` for a `kind: job` document, and
   `FileImportProcessor` re-copies the route's map at the import processor. See slice 3.
3. **`ResponseSpec` is the wrong file** — decision 5.
4. **One `OrderedCopies.map` is not enough** — decision 1.
5. **There was no slice for the signed documents**, which are the worst instances — decision 4.
6. **The precedent count and the scope are both larger than stated**: nine hand-rolled precedents,
   not eight, and forty-three files in scope, not thirty-nine.
7. **F74's ordering dependency on the plugin pins is false**, and its ownership is contradictory
   between the plan's two halves — decision 6.
