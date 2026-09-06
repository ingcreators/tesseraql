# Deterministic output

> **Status: in progress.** Eight slices, closing the findings the 2026-09-04 whole-repo audit filed
> as F27, F33 and F74 — plus the two the audit's own finder and the remediation plan both missed,
> which are the worst instances in the set.
>
> **The plan this document replaces was re-measured twice**, once against `ecdd5d6c8` and again
> against `a8c34e6f7` after the base-path emission campaign moved several of its cited lines. The
> corrections are recorded in [What the plan got wrong](#what-the-plan-got-wrong). Read the
> decisions below, not the plan.

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
| **`SbomGenerator`** | `:47-48` builds a four-entry `Map.of` into the CycloneDX metadata, under a javadoc at `:40` that says "ordered by purl **for reproducibility**". |
| **`ReleaseEvidence`** | `:29` builds `Map.of("name", …, "version", …)` into the evidence document, under a javadoc at `:17` that says "The document is **deterministic** … so it is **reproducible and signable**" — and the build then Ed25519-signs those bytes. |

The last two are the worst instances and neither the audit's finder nor the plan names them. A
document whose own javadoc promises reproducibility, which the build signs, and which does not
reproduce, is the sharpest form this defect takes.

The signature never fails: the Mojo signs the bytes it just wrote. The harm is precisely that two
builds of identical source produce different bytes, which is what a reproducible build claim means.

## Decisions

### 1 — One primitive, and it has two methods

`OrderedCopies` does not exist yet (`grep` over the tree returns nothing). It is created here, and
it takes **two** methods rather than one:

- `map(Map)` — insertion-ordered and **null-rejecting**, matching `Map.copyOf`'s contract.
- an explicitly named null-permitting variant, for the sites that need it.

The second method is not a convenience. `Map.copyOf`'s `NullPointerException` is today's **only**
load-time guard for a null value in a model map: `SimpleYamlParser` catches it and turns it into a
clean per-file schema error, and `ManifestLoader` dereferences without checking. A one-method
`OrderedCopies` that tolerated nulls would silently delete that guard at every model site it swept
— an ordering change that quietly converts a named schema error into a raw
`NullPointerException` much later.

The null-tolerant client set is **four** sites, not the three the plan implies. Each already
hand-rolls `Collections.unmodifiableMap(new LinkedHashMap<>(…))` precisely because it tolerates
nulls, and each keeps its reason in a comment when it moves.

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

### 3 — The scanner strips comments before it matches

Four of the forty-three files in scope match the census pattern **only inside a comment** — a
comment saying that this site deliberately avoids `copyOf`. Two consequences, and both are traps:

- Slice 7 deletes those comments, so a whole-file matcher moves the census under the very pull
  request that writes the ledger.
- A future author who writes the *correct* explanatory comment gets a red build for it.

So the scanner lexes out comments first and matches code only. This is the same shape as the
`ErrorIndex` trap the 2-way SQL parser campaign recorded, arriving from the other direction.

### 4 — The signed documents get their own slice

`SbomGenerator` and `ReleaseEvidence` are not swept with the model classes. Their literals are
nested inside document builders, the ledger's regex cannot see them structurally, and their
correctness claim is stronger than everyone else's — their javadoc promises it and the build signs
it. They land as one slice with a byte-for-byte reproducibility assertion: generate twice in
separate JVMs, compare the bytes.

### 5 — `ResponseHeaders` is the emission point, not `ResponseSpec`

A declared response header block is re-copied where it is emitted, so converting `ResponseSpec`
alone changes nothing observable and the slice's own promised test would fail after the fix. The
conversion belongs at the emission point, and the test asserts on the wire.

### 6 — This campaign owns `project.build.outputTimestamp`

F74 is double-owned: the plan's campaign map assigns it to the release campaign and its slice list
files it here. It lands here, and the claimed ordering dependency on pinning the lifecycle plugins
is dropped — that dependency was measured false, and nothing has ever shipped for it
(`git log -S 'outputTimestamp'` is empty across the whole history). A reproducible jar is worth
little while the documents inside it are not reproducible, which is the other reason it belongs
beside decision 4 rather than in a release campaign that is parked.

## The slices

Each is one pull request, branched from fresh `origin/main`.

| # | Slice | Size | Closes |
| --- | --- | --- | --- |
| 1 | This design document, registered in both internal-doc lists | S | — |
| 2 | `OrderedCopies`, its two methods, and the first conversion | M | F27 |
| 3 | A route's `input:` keeps its declared order | M | F27 |
| 4 | The model's remaining maps keep their declared order | M | F27 |
| 5 | An MCP tool answers in authored step order | S | F27 |
| 6 | A declared payload keeps its key order on the wire and in the file | M | F33 |
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
   slice 7 rather than assumed.

## What the plan got wrong

Recorded so nobody re-derives them from `REMEDIATION-PLAN.md`.

1. **The salt model.** The plan reasoned about `n!` orderings. `ImmutableCollections.MapN` iterates
   a rotation of a hash-determined slot cycle, so there are roughly `2n` — which is why a small map
   agrees with insertion order often enough for a careless test to pass.
2. **`RouteDefinition` is not a sweep.** Only `input` is salted there; the five fields below it are
   already insertion-ordered on purpose. The fix in that file is one line, and converting the rest
   is churn on correct code.
3. **`ResponseSpec` is the wrong file** — decision 5.
4. **One `OrderedCopies.map` is not enough** — decision 1.
5. **There was no slice for the signed documents**, which are the worst instances — decision 4.
6. **The precedent count and the scope are both larger than stated**: nine hand-rolled precedents,
   not eight, and forty-three files in scope, not thirty-nine.
7. **F74's ordering dependency on the plugin pins is false**, and its ownership is contradictory
   between the plan's two halves — decision 6.
