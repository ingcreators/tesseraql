# Jackson 3: the framework moves to the 3.1 LTS line in one reviewed slice, keeps what an application sees, and reads YAML as YAML 1.2

> **Status: designed 2026-09-25, measured against main `d8466139c` (0.19.0-SNAPSHOT).** The
> maintainer first asked in conversation whether Vert.x's Jackson 3 support would make anything
> faster. It would not (row 16). They then asked for a plan to move to Jackson 3's long-term-support
> line. There are three slices:
>
> **S1 (the move).** OpenRewrite's migration recipe runs once over the reactor and its output is
> reviewed as code (the measured run found three traps, decision 3). Every mapper factory pins
> Jackson 2's value for each default that would change what an application sees (decision 4).
> YAML is read as YAML 1.2. The library fixes that reading, and the editor already uses it
> (decision 6). One new warning, `TQL-YAML-1078`, covers the only form whose meaning changes
> silently: `~`.
>
> **S2 (the one default adopted).** Trailing content after a JSON value or a YAML document is
> refused. Today it is dropped silently.
>
> **S3 (one Jackson line on the runtime).** Jackson 2 leaves the runtime closure, and a tripwire
> keeps it out.
>
> The campaign goes before Phase 34. Reading YAML 1.2 is a change to the authored contract, and
> it should land before the compatibility contract freezes that contract.

## Why move, and why not for speed

- **The runtime already ships Jackson 3, unused** (rows 2-3). vertx-core pulls it in on any
  JDK 21+ build. The runtime closure therefore holds two JSON libraries, and nothing selects the
  second.
- **3.1 is a long-term-support branch.** 2.22, the version in the build today, is not: 2.x's LTS
  is 2.21 (row 6).
- **One change reaches authors, and it should come before the freeze.** YAML 3.x reads scalars
  by YAML 1.2 rules (row 7). That changes the authored contract. It belongs before Phase 34
  freezes the schema and before 1.0, not after either of them.
- **Not for speed.** Jackson 3 claims no speed-up. The two speed-related default changes it makes
  are switches that 2.x already has (row 16). Decision 14 records how speed is checked anyway.

## What was measured

These are readings of the tree on main `d8466139c`, of the Jackson, snakeyaml-engine, Vert.x and
OpenRewrite sources and release pages, and of the local repository, taken on 2026-09-25. Rows 11
and 12 were **run**: the recipe was applied to a throwaway copy of the tree, which was compiled
and tested and then discarded.

| # | Reading | Result |
| --- | --- | --- |
| 1 | The build today | `jackson-bom` 2.22.2 (`pom.xml:127`), imported. Ten module poms declare Jackson, twelve declarations in all: `jackson-databind` in `-yaml`, `-compiler`, `-operations`, `-report`, `-scim`, `-security`, `-mcp`, `-studio`, `-test-core` and `-docs-reference`, and `jackson-dataformat-yaml` in `-yaml` and `-test-core`. `tesseraql-core` carries no Jackson; its pom says so. |
| 2 | Jackson 3 is already on the runtime | `vertx-core-5.1.8.pom:842` declares profile `Java21` with `<jdk>[21,)</jdk>`, which adds `tools.jackson.core:jackson-core` and `jackson-databind`. Maven activates a dependency POM's JDK profile in the consumer's build. On JDK 25 both jars therefore resolve at **compile** scope in `-runtime`, `-studio-runtime`, `-cli`, `-host` and `-docs-reference`, all through `vertx-web → vertx-core` (`dependency:tree -Dincludes=tools.jackson.core`). The generated `runtime-closure.txt` lists Jackson 2.22.2 (lines 2-5) **and** 3.1.6 (lines 149-150): 598,694 + 1,948,538 bytes. |
| 3 | Vert.x selects Jackson 2 while it is present | The Java 21 multi-release `io.vertx.core.json.jackson.JacksonFactory` (read with `javap`) tries Jackson 2's `DatabindCodec`, then Jackson 2's `JacksonCodec`, then `v3.DatabindCodec`, then the v3 core codec. No main source imports `io.vertx.core.json`, so TesseraQL's JSON never passes through Vert.x's codec. |
| 4 | What the code uses | Jackson is imported by 165 main and 147 test files. Import counts: `ObjectMapper` 216, `JsonNode` 96, `@JsonIgnoreProperties` 56, `JsonProcessingException` 21, `ObjectNode` 16, `@JsonProperty` 15, `ArrayNode` 9, `@JsonCreator` 8, `YAMLFactory` 5, `StreamReadConstraints` 4, and single digits below that. Methods that 3.x renames: `.asText()` 791, `.fields()` 33, `.fieldNames()` 23, `.isTextual()` 4. Main has 13 mapper constructions (9 in `-yaml`), one custom deserializer (`StepsDeserializer`) and 10 `@JsonCreator` uses. There is no `module-info`, no direct SnakeYAML, no `@JsonView` and no JSR-310 module. |
| 5 | Jackson types in public signatures | **`-mcp`:** `McpServer.handle(JsonNode)`, `McpTool`/`McpResource` `ObjectNode` schema and meta, `McpSchema.build()`. **`-yaml`:** `JsonMappers`/`YamlMappers.constrained()` return an `ObjectMapper`, `OpenApiDiff.diff(JsonNode, JsonNode)`, `StepsDeserializer`. **`-security`:** `SecurityJson.constrained()`, `Jwks.fromJwk(JsonNode)`. **`-scim`:** `ScimAttributeCapture.syncResource`, `ScimPatchRequest.Operation`. **`-studio`:** one protected override. The `-yaml` factories are called by every module above `-yaml`. |
| 6 | The target line | Jackson's release page lists 3.1 as LTS (3.1.0 on 2026-02-23; "kept open for minimum of 2 years"), 3.2 as the latest non-LTS branch (3.2.3), and 3.3 as in development. For 2.x, 2.21 is LTS and 2.22 is the latest release. `jackson-bom` **3.1.7** is the newest 3.1 patch. It pairs `jackson-annotations` 2.21 (3.x keeps the 2.x annotations and their `com.fasterxml.jackson.annotation` package) with `snakeyaml-engine` 3.0.1, which `jackson-dataformat-yaml` 3.1.6 declares. |
| 7 | YAML 3.x reads scalars by the YAML 1.2 JSON schema, and the choice is fixed | In 3.1's `YAMLParser`, line 63 is `protected final ScalarResolver _yamlResolver = new JsonScalarResolver()`. It is not taken from `LoadSettings`. snakeyaml-engine's `JsonScalarResolver` defines: bool `^(?:true\|false)$`; null `^(?:null)$` or empty; int `^-?(0\|[1-9][0-9]*)$`; float JSON-shaped plus `.inf`/`.nan`. 3.1's `YAMLReadFeature` holds only `EMPTY_DOCUMENT_AS_EMPTY_OBJECT` and `EMPTY_STRING_AS_NULL`; 2.x's `PARSE_BOOLEAN_LIKE_WORDS_AS_STRINGS` is gone. Under YAML 1.1 (today), `yes`/`no`/`on`/`off`, `True`/`TRUE`, `~`/`Null`, `0x1F`, `017`, `1_000`, `+1`, `.5` and `1:30` are booleans, nulls and numbers. Under 1.2 they are strings. |
| 8 | Who writes those forms | None of the 406 tracked YAML files holds a value whose reading changes. There are two apparent hits: `y: n` in `stats.view.yml`, where `n` is a string under both versions, and the keys `y:` and `no:`, which Jackson reads as text under both. **One fixture embedded in Java does:** `AppLinterTest.java:3081` has `audience: ~`, a JWT audience that says nothing. The editor already reads 1.2: the extension's schemas reach authors through `redhat.vscode-yaml` ([vscode-extension.md](vscode-extension.md)), whose `yaml.yamlVersion` defaults to `"1.2"`. |
| 9 | Where an authored boolean is read by hand | `AppConfig.getBoolean` (`AppConfig.java:99`) accepts `true/false`, `yes/no`, `on/off` and `1/0`, and refuses anything else with `TQL-YAML-1107`, so it is safe under 1.2. **`StackIssuer.java:60`** reads the stack file's `security.oauth.enabled` with `Boolean.parseBoolean(String.valueOf(…))`. `enabled: on` is true today; under 1.2 it becomes `false`, **silently**. The other raw-map `Boolean.TRUE.equals(…)` reads in `-compiler`, `-studio` and `-runtime` read maps that the code itself built. |
| 10 | Defaults that 3.x changes | From the 3.0 migration guide and the core and databind release notes. **Mapper features:** `SORT_PROPERTIES_ALPHABETICALLY` → on; `DEFAULT_VIEW_INCLUSION`, `USE_GETTERS_AS_SETTERS` and `ALLOW_FINAL_FIELDS_AS_MUTATORS` → off; `FIX_FIELD_NAME_UPPER_CASE_PREFIX` → on. **Deserialization:** `FAIL_ON_NULL_FOR_PRIMITIVES` → on, `FAIL_ON_TRAILING_TOKENS` → on, `FAIL_ON_UNKNOWN_PROPERTIES` → **off**. **Serialization:** `WRITE_DATES_AS_TIMESTAMPS` → off. **Enums** are read and written through `toString()`. **Core:** fast floating-point reading on (#1231), default `maxNestingDepth` 500 (#1233), `INTERN_PROPERTY_NAMES` off (#378). Pretty printing does not change: the `Separators` default is `Spacing.BOTH` (`" : "`) in both 2.22 and 3.1 (read in both sources). |
| 11 | The recipe, run | OpenRewrite `org.openrewrite.java.jackson.UpgradeJackson_2_3`, using `rewrite-maven-plugin` 6.46.1 and `rewrite-jackson` 1.29.0 (the newest on Central; the recipe's docs page names versions not yet published). It took 1 min 45 s, touched **267 files (+1,859/−1,669) including 11 poms**, and moved the BOM to `tools.jackson` **3.1.7** by itself. Compiling afterwards: `-security`, `-mcp`, `-identity` and `-pipeline` compiled clean. `-yaml`, then `-operations` (3 errors), `-apptasks` (2) and `-studio` (4) failed with the same three families, and the run stopped there, since the families were the finding. **Trap a — the factory is discarded.** `new ObjectMapper(factory)` became `new JsonMapper()`/`new YAMLMapper()` at five of the seven constructions that take a factory (`JsonMappers` ×2, `YamlMappers`, `FlagsSpec`, `MenuSpec`). Their read constraints, duplicate-key detection, ASCII escaping and no-document-marker writer feature were gone, and the code still compiled. `SecurityJson` and `McpJson` kept theirs; `TestSuiteLoader` had passed a bare factory. **Trap b — `IOException` arms.** The recipe rewrote 63 `catch` clauses. Where a `Files.readString` sits inside a Jackson call's argument, it dropped the `IOException` arm (`DecisionRules`, `ReleaseDiff`, `PackagedModules` …) and javac refuses the result. Where the enclosing method itself declares `IOException`, a dropped arm compiles and the error handling changes. **Trap c — what the recipe leaves.** `DatabindException`'s constructor is not public (use `DatabindException.from`). An immutable mapper cannot be `.enable()`d after it is built (use `rebuild()`). `UncheckedIOException` cannot wrap a `JacksonException`, which is now unchecked. |
| 12 | Tests on Jackson 3, `-yaml` (1,265 tests) | **As the recipe left the tree:** 131 failures and 101 errors. Nearly all are ``Cannot map `null` into type `boolean` ``: an absent `boolean` record component, now `FAIL_ON_NULL_FOR_PRIMITIVES`. **With the factories restored and the 2.x defaults pinned at them:** 10 failures and 3 errors, from five causes. (1) `lock: version` in 8 tests: `LockSpec.of(String)`'s single-argument `@JsonCreator`. With parameter names now built into 3.x, a single *named* argument is read as a properties creator. (2) `audience: ~` (row 8). (3) The YAML emitters' document marker (trap a). (4) The duplicate-key sentence (`AppLinterHttpSourceTest.java:228` pins `Duplicate field 'rates'`). (5) `DecisionRules`' own mapper: on 3.x defaults its read of the schema sidecar fails, is caught as a `RuntimeException`, returns `null`, and the check is **skipped without a word**. |
| 13 | The mapper ledger has two holes today | `JsonMapperLedgerTest.java:67` matches `new ObjectMapper(`, the builder forms and the YAML/XML siblings. It does not match `new JsonMapper(`, which is the form the recipe writes. It also does not match a fully qualified `new com.fasterxml.jackson.databind.ObjectMapper(`, and that is exactly how `DecisionRules.java:473` builds one on main today, outside the ledger. |
| 14 | Which guards see trap a | `JsonMappersTest` asserts nesting at `JsonLimits.MAX_NESTING_DEPTH` = 100 and 101 (`JsonLimits.java:21`). 3.x's default is 500, so losing the JSON factory fails it. Nothing pins the YAML factory's bound or `constrainedAscii`'s escaping, and duplicate detection is pinned only through the lint's message. |
| 15 | Other Jackson 2 dependents | Reactor-wide `dependency:tree -Dverbose`: vertx-core → `jackson-core` 2 (compile) and `jackson-databind` 2 (optional); `flyway-core` → `jackson-databind` (optional) and `jackson-annotations`; `docker-java-api` (Testcontainers, test scope) → `jackson-annotations`. Both lines share `jackson-annotations`. vertx-core's own build runs a `jackson-absence` suite with every Jackson excluded. |
| 16 | Speed | Neither the 3.0 release notes nor the migration guide claims a speed-up. The two speed-related default changes, fast float parsing and no interning, exist in 2.x as switches (`StreamReadFeature.USE_FAST_DOUBLE_PARSER`, which `JsonMappers` does not enable today). |
| 17 | Where the repo names Jackson outside Java | `.github/dependabot.yml`'s maven block (groups and an ignore list). `tesseraql-scim/pom.xml:107`, an enforcer include naming `com.fasterxml.jackson.core:jackson-databind`. [security-hardening.md](security-hardening.md), a site page, whose line 41 says "over Jackson/SnakeYAML". [module-channel.md](module-channel.md), which names `jackson-databind` in the closure. The other mentions are in records. |

## The decisions

### 1 — The target is the 3.1 LTS line, and the build stays on it

`tools.jackson:jackson-bom` 3.1.7 replaces `com.fasterxml.jackson:jackson-bom` 2.22.2. Patch
releases keep flowing through Dependabot, and minor releases do not. The maven block gains an
ignore entry for `tools.jackson*` covering semver-minor and semver-major updates, with its reason,
removed when Jackson declares the next LTS. 3.2 is refused (decision 15).

### 2 — One slice moves the whole reactor

The factories in `-yaml` hand a Jackson mapper to every module above them (row 5). Moving one
module at a time would mean a second set of factories, and two Jackson APIs in the *code*, not
just on the classpath, for the length of the campaign. One review of the recipe's output and one
hand-finish cost less than that.

### 3 — The recipe runs once, and its output is reviewed as code

OpenRewrite is the standard tool. It does the package moves and the 791 `asText()` renames that no
one should do by hand. It runs once, in S1, and is not added to the build. The PR description
names the plugin and recipe versions. The measured run found three traps (row 11), and they become
S1's review list:

- **a.** Check every `new ObjectMapper(<argument>)` on main by hand, all seven listed in row 11,
  to confirm the factory survived.
- **b.** Check every `catch` clause the recipe edited (63 in the measurement). An `IOException`
  arm stays wherever the `try` does non-Jackson I/O, including I/O inside a Jackson call's
  argument. The clauses to read closely are the ones javac accepts because the method declares
  `IOException`.
- **c.** Fix the leftovers by hand: `DatabindException.from`, `rebuild()`, and a wrapper that
  takes an unchecked cause.

### 4 — S1 changes nothing an application sees: Jackson 2's defaults are pinned at the factories, and a ledger holds them

Each factory builds through its format's builder with Jackson 2's state for every flip an
application would observe:

- `FAIL_ON_NULL_FOR_PRIMITIVES` off
- `FAIL_ON_TRAILING_TOKENS` off
- `FAIL_ON_UNKNOWN_PROPERTIES` on
- `SORT_PROPERTIES_ALPHABETICALLY` off
- `READ_ENUMS_USING_TO_STRING` and `WRITE_ENUMS_USING_TO_STRING` off

The factories are `JsonMappers.constrained()` and `constrainedAscii()`, `YamlMappers.constrained()`,
`SecurityJson`, `McpJson`, the `FlagsSpec`/`MenuSpec` emitters and `TestSuiteLoader`.

The other changed defaults take 3.x's value because nothing observes them today. There is no
`@JsonView`. Nothing writes a `java.time` value through Jackson: 2.x had no JSR-310 module and
would have refused. The models are records. Fast float parsing yields the same doubles. S1's full
verify is the measurement that these really are unobserved.

A new **`JacksonDefaultsLedgerTest`** builds each factory's mapper and asserts every pinned state
along with the stream constraints, YAML duplicate detection, ASCII escaping and the emitters'
missing document marker. Neither trap a nor a later bump can then drop one of them quietly.
`SecurityJson` and `McpJson` sit below `-yaml` and cannot share a helper with it; the ledger holds
all of them to the same states.

### 5 — Every mapper comes from a factory, and the ledger sees every construction shape

`JsonMapperLedgerTest`'s pattern gains three shapes (row 13): `new` with an optional qualified
name followed by `ObjectMapper`, `JsonMapper` or `YAMLMapper`; the 3.x `builder(` forms; and
`.rebuild()`, because a rebuilt mapper is a new configuration. `DecisionRules` moves onto
`JsonMappers.constrained()`. On main today it is the hole in the ledger; under 3.x it is the
silent skip (row 12). Test sources stay out of scope, as they are today.

### 6 — YAML is read as YAML 1.2, and TesseraQL does not fork the scalar path

TesseraQL adopts the schema the library fixes (row 7), for four reasons:

- **Keeping 1.1 would mean owning a fork.** The resolver is a final field of the parser. Keeping
  1.1 means subclassing `YAMLParser` and `YAMLFactory._createParser` and maintaining the scalar
  path through every Jackson patch.
- **The editor already reads 1.2** (row 8). Today an author's `required: yes` is an error in the
  editor that the runtime accepts. The move ends that disagreement.
- **No tracked file is affected.** None holds a value whose reading changes (row 8).
- **Most changes are the author's intent winning.** `code: 0123` stays `"0123"` instead of
  becoming 83. `label: no` stays `"no"` instead of `"false"`. A typed boolean given `yes` is now
  refused loudly (Jackson's coercion error under `TQL-YAML-1001`) instead of being guessed.

**`~` is the one silent change.** It is null under 1.1 and the one-character text `"~"` under 1.2,
and it lands without a word in any string field: a JWT audience (row 8), an input default. S1
adds **`TQL-YAML-1078` (warning)**. It fires when a value in an authored document is exactly `~`,
`Null` or `NULL`: "YAML 1.2 reads `~` as the text "~"; write `null`, or leave the value empty".
The parsed tree does not record a scalar's style, so a quoted `"~"` that was meant as text also
warns. That false positive is accepted, and it is why the finding is a warning.

### 7 — One rule reads an authored boolean

`StackIssuer.enabled` (row 9) reads through the same spelling rule as `AppConfig.getBoolean`:
true/false, yes/no, on/off and 1/0, case-insensitive, with `TQL-YAML-1107` for anything else. The
rule is factored out so both callers share one list. S1 sweeps for any other place where authored
YAML reaches `Boolean.parseBoolean`; row 9 found only this one.

### 8 — A single-argument creator names its mode

There are 10 `@JsonCreator` uses in main (row 4). With parameter names built into 3.x, a
single-argument creator without a mode is read as a properties creator (row 12, `lock:
version`). Every `@JsonCreator` in main declares `mode = DELEGATING` or `PROPERTIES`. A
reflection test over the main model packages refuses a single-parameter `@JsonCreator` that does
not declare a mode.

### 9 — The library's sentence changes; the code does not

A broken document's entry carries Jackson's own sentence (`ManifestLoader.java:372`). A lint
message is not a contract; the code is (`TQL-YAML-1001` and the rest). S1 moves the two tests that
pin the library's wording (`AppLinterBrokenDocumentTest.java:82`, `AppLinterHttpSourceTest.java:228`)
to the 3.x wording, as measured, and rewrites no library text. snakeyaml-engine's parse errors
also differ from SnakeYAML's. `SimpleYamlParserFuzzTest` checks that every failure is still a
coded rejection.

### 10 — Written bytes stay the same

**JSON.** Pretty printing is unchanged (row 10), and property order is pinned (decision 4). Every
generated artifact is therefore byte-identical: OpenAPI, the schema, the SBOM, release evidence
and its signature input, the reports, the modules lock and the bench output. The deterministic-output
guards, the dogfood byte-compare and the reference regeneration prove it, and any diff in them is
a defect of S1.

**YAML.** Studio writes the `FlagsSpec` and `MenuSpec` files. They keep "no document marker"
(trap a). S1 measures the rest of their shape (3.x quoted the key `n` as `"n": 5`, row 12) and
pins the 2.x shape wherever `YAMLWriteFeature` allows it. Where snakeyaml-engine's emitter cannot
reproduce the 2.x bytes, the round trip (write, then load, gives back the value) is the contract,
not the bytes, and S1 records which bytes changed.

### 11 — S2: each changed default is adopted or refused on its own evidence

| 3.x default | Decision | Why |
| --- | --- | --- |
| `FAIL_ON_NULL_FOR_PRIMITIVES` on | **refuse, permanently** | An absent `boolean` in authored YAML means false. That is the authored contract, and it produced the 232 failures in row 12. |
| `SORT_PROPERTIES_ALPHABETICALLY` on | **refuse, permanently** | Declaration order is the deterministic-output contract ([deterministic-output.md](deterministic-output.md)). Alphabetical order would churn every generated artifact. |
| `FAIL_ON_UNKNOWN_PROPERTIES` off | **refuse, permanently** | The authored models opt out class by class (108 `@JsonIgnoreProperties(ignoreUnknown = true)`), and `TQL-YAML-1043` owns unknown keys. For request and stored JSON, 2.x's strictness is the reviewed behaviour, and relaxing it would be silent tolerance. |
| `FAIL_ON_TRAILING_TOKENS` on | **adopt (S2)** | Today `{"a":1} x` in a body, or a second `---` document in an application file, is read as its first value and the rest is dropped. No application YAML in the tree has a second document; the two multi-document files are `deploy/` manifests that TesseraQL never reads. |
| enums through `toString()` | **refuse** | `LintFinding.Severity.toString()` returns the lowercase wire form, and `LintFindingWireShapeTest` pins the finding's JSON. S2 records which way each enum Jackson writes goes, and keeps it. |
| `WRITE_DATES_AS_TIMESTAMPS` off; `java.time` built in | adopt (in S1) | Nothing writes `java.time` through Jackson today. If something starts to, ISO-8601 text is what [temporal-semantics.md](temporal-semantics.md) prescribes. |
| fast float parsing; no interning | adopt (in S1) | Parsing is exact (the same doubles), and nothing can observe it. |
| `DEFAULT_VIEW_INCLUSION`, `USE_GETTERS_AS_SETTERS`, `ALLOW_FINAL_FIELDS_AS_MUTATORS`, `FIX_FIELD_NAME_UPPER_CASE_PREFIX` | adopt (in S1) | There is no `@JsonView`, and the models are records. S1's verify is the check. |

The permanent refusals stay pinned in the factories, each with a one-line reason, and
`JacksonDefaultsLedgerTest` names them.

### 12 — S3: one Jackson line on the runtime

After S1, Jackson 2's `databind`, `dataformat-yaml` and SnakeYAML are gone from the closure.
`jackson-core` 2 stays, because vertx-core declares it at compile scope (row 15). S3 excludes
`com.fasterxml.jackson.core:jackson-core` from vertx-core in the poms that declare Vert.x. Vert.x
then selects its v3 `DatabindCodec` (row 3), a configuration its own `jackson-absence` suite
covers. The runtime-footprint deny-lists ([runtime-footprint.md](runtime-footprint.md), the
runtime and host rules) gain `com.fasterxml.jackson.core:jackson-core` and `jackson-databind` as
tripwires, so Jackson 2 cannot return under a different root. `jackson-annotations` stays, since
both lines share it. S3 records the closure's size before and after.

**Flyway is the dependent to watch.** Its `jackson-databind` is optional, and today the runtime
supplies databind 2 through `-yaml`. If Flyway loads Jackson unconditionally on a path TesseraQL
uses, S1 is where it breaks. S1's verify runs every Flyway path: framework and application
migrations on PostgreSQL and H2, `--embedded-db`, and the gated dialect suites before merge.

### 13 — The published Java surface changes packages, recorded rather than shimmed

The signatures in row 5 move from `com.fasterxml.jackson` types to `tools.jackson` types. Before
1.0, the CHANGELOG records that and no adapter is written. Whether Jackson types stay on the public
surface of `-mcp`, `-security` and `-yaml` at all is filed as an input to Phase 34's compatibility
contract.

### 14 — Speed is not a goal, and it is checked

This design makes no claim about speed (row 16). S1's PR records `tesseraql bench` against an
example app on the same machine, once before and once after, as a non-regression reading. It is
not a gate: the bench's own readings vary (a refusal count one apart between the harness and the
scrape on #1446's CI run), which would make a gate noisy.

### 15 — What this design refuses, each with its trigger

- **A YAML 1.1 fork of the parser.** *Trigger: a report that the 1.2 reading changed an
  application's behaviour with neither a refusal nor a `TQL-YAML-1078` warning.*
- **Jackson 3.2 or 3.3.** *Trigger: a fix that exists only there, or 3.1's branch closing.*
- **A lint for `yes`/`no`/`on`/`off`.** These words have legitimate uses as text (a choice
  list's `enum: [yes, no]` now means what it says), and a typed boolean already refuses them
  loudly. *Trigger: a report of a silent misreading.*
- **Keeping a Jackson 2 API for extensions.** No extension SPI carries a Jackson type; core has
  none. *Trigger: an SPI that needs one.*
- **The OpenRewrite plugin in the build.** It runs once. *Trigger: none.*
- **Any 3.x strictness beyond trailing tokens.** *Trigger: its own finding.*

### 16 — Docs, ledgers, CHANGELOG

In S1, [security-hardening.md](security-hardening.md) line 41 names snakeyaml-engine, and the
page records snakeyaml-engine's own load limits (code points and aliases) as S1 measures them.
[module-channel.md](module-channel.md)'s closure line follows S3. The Dependabot ignore entry
lands in S1. `CHANGELOG.md` gets an entry per slice under Unreleased, listed under "Docs and
CHANGELOG" below.

## What this breaks

- **YAML scalars (S1).**
  - `~`, `Null` and `NULL` are read as text, with the `TQL-YAML-1078` warning.
  - `yes`/`no`/`on`/`off` and the capitalised `True`/`False` spellings are read as text. A typed
    boolean refuses them. A configuration boolean still accepts yes/no/on/off, through the one
    rule of decision 7.
  - `0x`/`0o` numbers, leading zeros, underscores, a leading `+` and sexagesimal forms are read
    as text.
- **Jackson types in the Java surface change package (S1):** `-mcp`, `-security`, `-scim` and
  `-yaml` (row 5).
- **Lint entries that quote Jackson's sentence change wording (S1).**
- **Trailing content is refused (S2):** a request body or stored JSON with content after the
  value, and an application YAML file with a second document.
- **Jackson 2 leaves the runtime (S3).** A module-channel module that relied on the runtime's
  Jackson 2 must bring its own. None of the framework's own modules does: `-s3` uses the AWS SDK's
  shaded copy, and `-excel` and `-pdf` use none.

## Filed, not fixed

- **Jackson types on the public Java surface.** This is an input to Phase 34 (decision 13).
  *Trigger: Phase 34's design.*
- **The dead Jackson 3 on today's runtime (row 2)** gets no fix of its own. S1 makes it the only
  line, and S3 removes the other one.

## Deliberately not in this design

- Replacing Jackson.
- Adopting Jackson 3 APIs beyond the renames: new node methods, the `JsonNode` stream helpers.
- The JavaScript projects (docs site, VS Code extension).

## The slices

### S1 — the move (L)

The BOM moves to `tools.jackson` 3.1.7, and the twelve poms and the `-scim` enforcer include move
with it. The recipe runs, followed by decision 3's review (a, b, c) and the hand-finish. The
factories are rebuilt with the pinned states and `JacksonDefaultsLedgerTest` is added. The mapper
ledger's pattern is widened and `DecisionRules` moves onto the factory. S1 also brings the
creators' modes and their test, `TQL-YAML-1078`, the shared boolean rule and `StackIssuer`, the two
library-wording tests, the emitters' shape, the Dependabot entry, security-hardening.md, the bench
reading and the CHANGELOG. The full clean verify runs with network, the gated dialect suites run
before merge, and the reference is regenerated.

| Guard | Variant that must fail it |
| --- | --- |
| `JacksonDefaultsLedgerTest` (new): each factory's pinned states, stream constraints, YAML duplicate detection, ASCII escaping, the emitters' document marker | a factory built as `new JsonMapper()` or `new YAMLMapper()` (trap a); one pinned state flipped |
| `JsonMapperLedgerTest`, widened | `DecisionRules`' construction put back as `new JsonMapper()`; a fully qualified `new com.fasterxml…ObjectMapper()` |
| a YAML nesting-bound test (new): `YamlMappers.constrained()` refuses depth 101 | the YAML factory discarded |
| the `@JsonCreator` mode test (new) | `LockSpec.of` with the mode removed |
| `TQL-YAML-1078` in `AppLinterTest` (the `audience: ~` fixture) | `~` no longer warned |
| the stack-file boolean test (new): `security.oauth.enabled: on` enables the issuer | `Boolean.parseBoolean` restored in `StackIssuer` |
| the deterministic-output guards, the dogfood byte-compare, the reference regeneration, both docs guards | any generated byte changed |
| `git grep -n 'com\.fasterxml\.jackson' -- '*.java' '*pom.xml'` | any hit outside `com.fasterxml.jackson.annotation` |

### S2 — trailing content refused (S)

`FAIL_ON_TRAILING_TOKENS` is adopted in the factories. S2 records the permanent refusals from
decision 11 in the factories and the ledger, and adds CHANGELOG.

| Guard | Variant that must fail it |
| --- | --- |
| a JSON body `{"a":1} x` refused with the route's JSON-parse error | the feature left off |
| an application YAML file with a second document refused at load, and listed by the tolerant lint | the feature left off in the YAML factory |
| `JacksonDefaultsLedgerTest`'s refusal rows | one refused default adopted without its row changed |

### S3 — one Jackson line (S)

S3 adds the vertx-core exclusion and the two footprint tripwires, records the closure size before
and after, updates module-channel.md's closure line, and adds CHANGELOG.

| Guard | Variant that must fail it |
| --- | --- |
| the runtime and host footprint rules, with `com.fasterxml.jackson.core:jackson-core` and `jackson-databind` denied | the exclusion removed |
| the full verify: every HTTP, SSE and edge path on Vert.x's v3 codec | — |

## Docs and CHANGELOG

`CHANGELOG.md`, Unreleased:

- **S1 `### Changed`**
  - **Jackson 3.1 (LTS).** The framework's JSON and YAML now run on Jackson 3.1 (`tools.jackson`),
    the long-term-support line, instead of 2.22. The defaults that would change what an
    application sees keep their 2.x values.
  - **YAML is read as YAML 1.2.** `yes`/`no`/`on`/`off`, `~`, leading-zero, hex and underscore
    numbers are now text, as the editor already read them. A boolean field refuses them. A
    configuration boolean still accepts yes/no/on/off.
  - The Java types of the MCP, security and YAML modules' public methods move from
    `com.fasterxml.jackson` to `tools.jackson`.
- **S1 `### Added`:** `TQL-YAML-1078`, a warning on a value written as `~`, `Null` or `NULL`,
  which YAML 1.2 reads as text.
- **S1 `### Fixed`:** the stack file's `security.oauth.enabled` accepts `yes` and `on`, as every
  other configuration boolean does.
- **S2 `### Changed`:** content after a JSON value or a YAML document is refused instead of being
  dropped.
- **S3 `### Removed`:** Jackson 2 no longer ships in the runtime.

## Error codes

- **`TQL-YAML-1078`** (warning, S1): a value written as `~`, `Null` or `NULL`, which YAML 1.2
  reads as text.
