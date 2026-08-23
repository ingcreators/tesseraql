# Duplication consolidation

Implementation design for the follow-on to [contract SQL execution](contract-sql-execution.md)
and [SQL execution shapes](sql-execution-shapes.md): the same survey those campaigns ran over
JDBC, run over everything else. Four lenses — outbound HTTP, serialization and configuration
reading, error and span construction, filesystem and edge plumbing — each asking the question
the SQL work answered: **where do many hand-written sites do one job, each slightly
differently, with no single place a decision lives?**

Written 2026-08-23, measured against main at #1013. The remedy is the one the SQL campaign
proved: a primitive that owns the job, callers that own their meaning, and a **ledger test**
that names every remaining hand-rolled site so a new one is refused by default.

## The survey, measured

| Cluster | Sites | Primitive exists? | Verdict |
| --- | --- | --- | --- |
| Outbound HTTP | 14 bypassing, ~8 adopted | **yes** — `HttpCallClient` behind `OutboundGateway` | adopt + ledger (campaign 1) |
| Path confinement | 22 | no | build + adopt + ledger (campaign 2) |
| Error envelope over the wire | 10 hand-rolled | partial — `ErrorResponseRenderer` | lift + adopt + ledger (campaign 3) |
| code → HTTP status | 56 mapped of 555 | the renderer's switch | ledger (campaign 3) |
| `tesseraql.app.name` | 13 reads, 4 defaults | **yes** — `ApplicationName`, bypassed | adopt (campaign 4) |
| JSON `ObjectMapper` | 77 bare constructions | no (YAML has `YamlMappers`) | build + adopt (campaign 4) |
| HTML/XML escaping | 10 private copies | no | build + adopt (campaign 4) |
| Request-cookie read | 4 (1 package-private) | yes, invisible | widen + delete (campaign 4) |
| Atomic file replace / upload-part / PG listen loop | 5 / 3 / 2 | no | small follow-ons |

Measured and found **not** worth a campaign, recorded so nobody re-derives it: retry loops
(four exist, each a structurally different shape — no cluster); executor creation (`Lane` is
the primitive; the other creations are named single threads with real lifecycles); HTML page
rendering (already one path: `HtmlResponseRenderer` → `ShellChrome` → templates);
`ContentDisposition` (five writers, five adoptions); `Durations` (37 call sites, one parser);
audit trails (three deliberately distinct shapes, each with one writer, the divergence
documented in `GrantHistory`'s javadoc).

## Campaign 1: outbound HTTP — the seam exists, the ledger does not

`OutboundGateway`'s javadoc calls itself "the one seam every outbound HTTP call leaves
through … with no second HTTP stack anywhere" (docs/lookups.md, decision 15), and
`HttpCallClient` behind it delivers everything the SQL primitive delivers for statements:
configured connect/request timeouts, a deny-by-default egress allow-list, TQL-BATCH-53xx
classification, a per-host circuit breaker, and a `tesseraql.http.call` span. Eight call
paths use it. **Fourteen main-source sites across eight modules do not**, and of the fifteen
places that send an outbound request, exactly one opens a span.

The bypasses are not one population. They divide by what policy they need:

- **Cluster A — third-party egress (5 sites)**: OIDC discovery and token exchange
  (`OidcHttp`), JWKS fetch (`HttpJwksFetcher`), SAML IdP metadata (`SamlMetadataSource`),
  SCIM outbound provisioning (`ScimOutboundClient`), and the Studio copilot
  (`CopilotService`). These want exactly the gateway's policy — and three of them re-implement
  its allow-list gate inline, with three different refusal codes.
- **Cluster B — intra-stack loopback (6 sites)**: the ops shell and Studio shell calling a
  member runtime on its own port (`OpsShellProviders`, `OpsShellRoutes`, `WorkshopTargets`,
  `CopilotProxyRoutes`, `StudioSupport`), and the readiness probe in `MultiAppHost`. These
  must **not** go through the egress allow-list: loopback is deliberately absent from
  `allowedHosts`, and forcing operators to allow-list their own stack would weaken the
  posture the list exists for.
- **Cluster C — the CLI (3 sites)**: `TokenCommand`, `DeployCommand`, `UpdateNotifier`. No
  tracer exists in the CLI process and no egress policy applies to a developer's own
  machine; what they need is timeouts, not the primitive.

Defects the measurement surfaced, each an instance of the "unbounded, unclassified,
unobserved" class the SQL campaign named:

- **`ScimOutboundClient` sets no timeout at all** — neither connect nor request. A hung SCIM
  provider hangs the provisioning thread indefinitely.
- **`DeployCommand`'s upload sets no timeout at all.**
- **`HttpJwksFetcher` omits `ProxySelector.getDefault()`**, which `OidcHttp` and
  `HttpCallClient` both set and both document as required. Behind a corporate proxy, OIDC
  discovery succeeds and JWKS verification fails against the same IdP.
- **`StudioSupport` builds a new `HttpClient` per request** — the selector-thread leak
  `OpsShellRoutes`'s own javadoc records having fixed one surface over.
- **`OpsShellRoutes`'s streaming download bounds headers only**; the body transfer is
  unbounded.
- **`HttpCallClient`'s span never records an unchecked failure**: `recordError` fires only in
  the two constructed-refusal branches, so an exception escaping `send` ends the span clean —
  the same defect class the Vert.x-native campaign fixed in `Completion`.

### Structural decision 1: cluster A adopts the gateway, and auth is the caller's header

The five egress sites route through `HttpCallClient`. What they add over today's gateway
callers is authentication — a bearer from `ScimTarget`, `client_secret_basic`, an API-key
supplier — and that stays theirs: the primitive already takes caller headers; it gains
nothing credential-shaped. The three inline allow-list gates retire in favour of the
gateway's one, and their three refusal codes collapse to the gateway's classification. Each
converted site keeps its own outer domain exception (an `OidcException` stays an
`OidcException`), built from the classified failure instead of a raw `IOException` — the
"primitive classifies, the caller maps" rule, verbatim.

### Structural decision 2: loopback is its own primitive, not a gateway exception

A `LoopbackCall` primitive (working name) owns the intra-stack hop: one shared pooled
client, mandatory connect and request timeouts, the forward header set (`Cookie`,
`X-CSRF-Token`, `Content-Type`, `Authorization`) declared in one reviewable place, the
shared form/query encoding, a streaming form for the transfer-file proxy, and one transport
failure signal each caller maps to its own refusal. `OpsShellProviders` and
`WorkshopTargets` are today near-identical programs differing in URL shape and error text;
they become two callers. Two details settled at implementation, against the first draft: the
`404 / non-2xx / unparseable` triad stays with each caller rather than in the primitive —
the workshop hop re-throws the member's own error code where the ops hop deliberately does
not, so "mapped once" would have flattened a real difference — and the hop opens no
client-side span, because the member's own pipeline opens the authoritative span and a
second one would double every shell navigation in the trace.
Punching a loopback hole through the egress gateway instead was considered and rejected: the
allow-list's meaning ("what may this stack reach outside itself") should not acquire an
asterisk.

### Structural decision 3: the CLI gets timeouts, not the primitive

`DeployCommand` gains connect/request bounds; `TokenCommand` and `UpdateNotifier` already
have them. A shared `CliHttp` helper is not worth its surface for three sites in one module —
recorded here so the ledger's CLI entries carry a reason.

### Structural decision 4: the ledger

`HttpClientLedgerTest`, modeled on `SqlExecutorLedgerTest`: every main-source file that
constructs a `java.net.http.HttpClient` or a Vert.x `HttpClient` is named; a new entry is
refused by default. The Vert.x relay (`MultiAppGateway`/`StackRelay`) stays on the ledger
with its reason — it is a streaming reverse proxy with its own consolidated policy, and
folding it into a request/response primitive would be shape for shape's sake.

## Campaign 2: the confined path

Twenty-two sites guard a caller-influenced path under a root with hand-written
`resolve().normalize()` + `startsWith(root)` sequences, and there is no central helper. They
already disagree, which is the tell that this is `ContentDisposition`'s story at five times
the size with a traversal outcome:

- `FileConnectors` holds the only fully correct form — both candidate and root are
  absolutized **and** normalized before comparison.
- `TemplateResolution` normalizes the candidate but compares against a root that was never
  absolutized or normalized: a relative or `..`-carrying app home makes the guard vacuous.
- `PdfFileCodec` absolutizes the root but compares the candidate as-is; `FileScopes`
  normalizes but absolutizes neither side.
- Some add `isRegularFile`, one (`FileSecretResolver`) forbids nesting entirely — real
  policy differences, currently indistinguishable from accidents.

### Structural decision 5: one primitive, strictness declared

`ConfinedPath` in `tesseraql-core` (beside the other filesystem plumbing): constructed from
a root it absolutizes and normalizes once, with one method that resolves a candidate and
refuses escape with one TQL code, and declared options for the two real policy variants
(must-be-regular-file, no-subdirectories). Zip extraction's entry check (`AppInstaller`'s
zip-slip guard) is the same primitive applied per entry. All 22 sites adopt; each keeps its
own outer refusal message where one exists today. The ledger names every main-source
`Path#startsWith` caller, refusing new hand-rolled guards.

## Campaign 3: the error envelope, and the status table's missing guard

`TqlException` needs nothing — 795 construction sites through one builder — and
`ErrorResponseRenderer` is installed as the catching handler by fourteen route families,
with the only code→status table in the tree. The consolidation-shaped residue is at the
edges, where **ten sites hand-build the "exception → HTTP body" step**. Six of them spell
the framework envelope `{"error":{"code":…,"message":…}}` by string concatenation, each with
its own escaping or none; `StackRelay`'s copy drops the `message` key; `McpHttpHandler`
ships the flat `{"error":"…"}` shape `FederationErrors`'s javadoc documents as removed for
carrying no searchable code. Two deviations are legitimate and stay: OAuth's RFC 6749 body
and SCIM's RFC 7644 body, each mandated by its spec.

### Structural decision 6: `ErrorEnvelope` in core, lifted from the one correct copy

`FederationErrors.body` — the only concatenation site with a real JSON escaper — moves to
`tesseraql-core` beside `TqlException` as `ErrorEnvelope`, with a write form that also
applies the `Retry-After` rule currently duplicated in three places. The six concatenation
sites and `LoginRoutes`'s Jackson-built map adopt it; `LoginRoutes`'s string-literal code
becomes the typed constant it shadows. MCP's flat bodies become enveloped, coded refusals —
a breaking change to an error shape, recorded not bridged (AGENTS.md rule 10). A ledger
names every main-source file writing an error body outside the renderer and `ErrorEnvelope`;
OAuth and SCIM stay listed with their RFC citations as the reason.

### Structural decision 7: every code answers the status question

The renderer's `httpStatus` switch names 56 codes; 555 exist. Sixteen domains fall through
to 500 wholesale, and the switch's own comments record the consequence shipping **twice**
(access-governance slices 2 and 7: a refusal added without a mapping reads as "Internal
Server Error"). The guard: a ledger test over the same registry `ErrorIndex` already scans,
asserting every registered code is either named in the switch or on an explicit
never-surfaces-over-HTTP list. Building that list *is* the audit; codes it catches
mid-classification (refusals that currently 500) are fixed in the same slice or recorded
individually. From then on, a new code fails the build until its author answers the status
question the moment they mint it.

## Campaign 4: the accessors that already exist, and the two that do not

**`tesseraql.app.name`.** `ApplicationName.of()` exists precisely to refuse a missing name
(TQL-YAML-1404), and its javadoc records why defaulting is a defect: two unnamed
applications against one database shared a migration history and each other's outbox claims.
Seven sites still read the raw key and default it — to three different values.
`RuntimePools` names the OpenTelemetry service with one of them, so unnamed apps merge in
traces. All seven adopt the accessor; `SecurityConfigFactory`'s half-adoption (hand-rolled
trim/validate beside a call into the same class) collapses. A guard asserts the key has one
read site, the way `SqlDefaults` guards its timeout key. The generated config reference
regenerates — provenance collapses to one file.

**`JsonMappers`.** `YamlMappers.constrained()` sets explicit `StreamReadConstraints`, and
docs/security-hardening.md records why: limits the code declares rather than defaults a
dependency upgrade could move. The JSON side was never swept: 77 bare `new ObjectMapper()`
constructions, zero of them constrained, several parsing untrusted request bodies
(`RequestBinder`, `McpHttpHandler`, `ScimRoutes`, `OidcRoutes`, `StudioRoutes`). A
`JsonMappers` sibling ships the constrained default; the untrusted boundaries adopt it by
name, the rest mechanically — all 77 are configuration-free today (three set only
indentation or lenient-unknowns, each kept explicitly), so the sweep is behavior-preserving
by construction. `MessageCatalog`'s per-call unconstrained YAML read joins `YamlMappers`.

**`Escapes`.** Ten private `escape(String)` copies; two drop the double quote, which is
unsafe the moment output lands in an attribute — `ViewEjector` already hand-writes `&quot;`
entities to work around its own escaper. One `Escapes` class in core with the HTML/XML text
and attribute forms; the ten adopt; the Prometheus, Markdown-table and JSON escapers stay
where they are (different grammars, correctly local).

**`Cookies`.** The session store's cookie parser is package-private, and `ShellChrome`'s
javadoc names that visibility as the reason it hand-rolls a copy; `OidcRoutes` and
`SamlAcsRoutes` carry a third, byte-identical pair. The parser becomes public; three copies
delete.

## Point fixes riding the campaign

Ships first, because it is the instrument the config work is measured with: **the config
reference's scanner regex names methods `AppConfig` does not have and omits two it does**
(`requireString`, `getDouble`). Six keys — all mandatory, exactly the ones an operator needs
the reference for — are absent from the generated page. With it ride three one-line defects:
`OpenApiGenerator` reads `app.version` where every sibling reads `tesseraql.app.version`, so
generated API documents always claim version 1.0.0; `tesseraql.temp.maxBytes` and the mail
notifier's `maxAttachmentBytes` accept only raw integers while attachment limits accept
`25MB` (they adopt the existing size parser, which moves to core with a code instead of its
silent `-1`); `StackSettings` parses durations with bare `Duration.parse` so `30s` fails
there and works everywhere else.

Riding their campaigns: `JdbcFileTransferService`'s spans get the parent context every other
child span passes (campaign 1's slice 4, beside the other span fix); the interrupt-preserving
sleep and `closeQuietly` copies consolidate where campaign follow-ons touch their files.

## Slices

Fourteen, in the recommended order. Each is one PR, branched from fresh origin/main.

1. **This design** — plus nav/ErrorIndex registration.
2. **Instruments and one-line defects** — the config-reference regex, `OpenApiGenerator`'s
   key, the two size-parse adoptions, `StackSettings`'s duration parse; reference regen.
3. **Egress adoption, identity trio** — `OidcHttp`, `HttpJwksFetcher`, `SamlMetadataSource`
   through the gateway; inline allow-list gates retire; JWKS gains the proxy selector.
4. **Egress adoption, provisioning** — `ScimOutboundClient` (bounded at last). **Departure,
   recorded at implementation: `CopilotService` stays on its own client.** Its primary path
   is a streaming SSE read (`BodyHandlers.ofLines`), which the gateway's raw form — a
   complete byte-array response — cannot carry; it is dev-only (the Studio ships only in
   dev), already bounded (10s connect / 60s request), and already boot-gated against the
   same allow-list (`TQL-SEC-4085`). Splitting its two calls across two transports would
   trade one honest ledger entry for a seam nobody can reason about; it stays listed on the
   HTTP ledger with this reason, like the Vert.x relay.
5. **`LoopbackCall`** — the primitive plus its six adopters; the per-request client dies
   here. Recorded at implementation: the streamed download's *body* transfer remains
   governed by the edge's connection lifecycle rather than a request timer — the JDK
   client's request timeout runs to the response headers, and buffering the body to bound
   it would defeat the reason the streaming surface exists.
6. **The HTTP ledger** — `HttpClientLedgerTest`; `DeployCommand` timeouts;
   `HttpCallClient`'s span records unchecked failures. Corrected at implementation: the
   transfer service's spans do **not** gain a parent — a transfer runs detached from the
   request that started it (the caller gets the transfer id back immediately), so a root
   span is the correct shape, exactly as a job's is; the survey misread the missing parent
   as a defect.
7. **`ConfinedPath`** — the primitive, with the security-loaded adopters: runtime file
   scopes and assets, the template resolver, the zip-slip guard, the PDF resolvers.
8. **`ConfinedPath` sweep** — the remaining adopters (yaml, studio, cli, apptasks) and the
   path-guard ledger.
9. **`ErrorEnvelope`** — the primitive lifted from `FederationErrors`, seven adopters, the
   two shape defects fixed, the envelope ledger.
10. **The status ledger** — every registered code mapped or explicitly recorded; the
    refusals it catches answering 500 get their real statuses.
11. **`ApplicationName` adoption** — seven sites, the half-adoption collapsed, the one-read
    guard, reference regen.
12. **`JsonMappers`** — the constrained default, named adoption at the untrusted boundaries,
    the mechanical sweep, `MessageCatalog`'s YAML read.
13. **`Escapes` and `Cookies`** — the escaper with its ten adopters; the parser public with
    its three deletions.
14. **Small follow-ons** — atomic file replace (five sites, one of which silently lacks
    `ATOMIC_MOVE`), the multipart upload-part resolver (three sites that already
    cross-reference each other in comments), the PG listen-loop base under
    `TopicNotifyBridge`/`PgNotifyListener`.

## Guards

- `HttpClientLedgerTest`, the path-guard ledger, the envelope ledger, the status ledger —
  each a named list where a new entry is refused by default and an adoption shrinks the
  list.
- The `tesseraql.app.name` one-read guard, beside `SqlDefaults`' precedent.
- `GeneratedReferenceTest` regenerates on every config-read move (slices 2 and 11).
- Existing suites carry the conversions: every adoption slice is behavior-preserving under
  its module's tests, and every defect fix ships a revert-proven regression test.

## Test plan

- **Unit, per primitive**: `LoopbackCall`'s header sets and refusal triad; `ConfinedPath`'s
  escape refusal, including the relative-root and `..`-root cases that defeat today's
  guards; `ErrorEnvelope`'s escaping against the strings that break naive concatenation;
  `JsonMappers`' constraints actually refusing a hostile document; `Escapes` against
  attribute context.
- **Per-slice revert-the-fix**: each named defect (SCIM timeout, JWKS proxy, per-request
  client, message-less envelope, MCP shape, OpenAPI version) pins red when its fix reverts.
- **Integration**: the egress conversions run under the existing OIDC/SAML/SCIM suites; the
  loopback conversions under the ops-shell and studio-shell suites; a container test proves
  a bounded SCIM call times out instead of hanging.

## Deliberately not in this design

- **Span coverage for outbox, poll, messaging, webhooks, attachments and MCP.** Those
  subsystems open no spans at all; giving them observability is a roadmap item about
  telemetry, not a duplication to consolidate.
- **A retry/backoff helper.** Four loops exist and share no shape; a helper would be
  invented, not extracted.
- **Folding the Vert.x relay into a request/response primitive** — it is a streaming proxy
  with its own consolidated policy.
- **`Headers`/`MediaTypes` constant sweeps.** String-constant duplication without logic;
  cheap, but a mechanical rename campaign of its own if ever worth doing.
- **A `CliHttp` helper** (structural decision 3).

## Open questions, settled by recommendation

1. **Where does `LoopbackCall` live?** Its callers span `tesseraql-runtime` and
   `tesseraql-studio-runtime`. Recommended: the lowest module both already depend on that
   can see the pipeline's exchange types — settled concretely in slice 5 when the dependency
   graph is in hand, recorded there.
2. **Does the status ledger fix mid-classification findings inline or record them?**
   Recommended: fix refusals (4xx-shaped answers currently reading 500) inline in slice 10;
   record genuine judgment calls individually rather than batching them into the sweep.
3. **Does the JSON sweep convert all 77 sites or only the boundaries?** Recommended: all —
   the sites are configuration-free, so the sweep is mechanical, and a half-swept mapper
   population is exactly how the next bare construction gets pasted in.
