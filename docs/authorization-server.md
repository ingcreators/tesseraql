# TesseraQL as an authorization server

Status: **designed 2026-08-15, and deferred.** The design below is complete enough to build
from. The recommendation is not to build it yet, and the conditions for revisiting are written
down at the end so the question does not have to be re-researched from scratch.

Two things escape the deferral, and both moved into
[audit-hardening.md](audit-hardening.md) so they are not deferred with it: the SAML hardening of
open question 4 is that campaign's Decision 10 and slice 13, and the companion-authorization-
server documentation of "What to do instead" item 3 ships with its wave E, because the resource-
server slices serve nobody without an authorization server to name.

The question arrived from MCP. [audit-hardening.md](audit-hardening.md) Decision 2 makes
TesseraQL an OAuth 2.0 *resource server*: it validates a bearer token an identity provider
issued and names that provider in its protected-resource metadata. That decision has a
precondition — the operator must already run an OAuth authorization server — and an application
whose only identity source is SAML or TesseraQL's own login does not have one. SAML identity
providers do not issue OAuth tokens, and TesseraQL mints none: it serves no `/authorize` and no
`/token`, and `POST /_tesseraql/login` returns a session cookie, which is not a credential an
MCP client carries.

So the shape of the question is: **should TesseraQL issue its own tokens, federating the
authentication step to whichever login the application already has?**

## Scope it correctly first — MCP does not ask for this

The specification is explicit that the authorization server is a separate role: "The
implementation details of the authorization server are beyond the scope of this specification.
It may be hosted with the resource server or a separate entity." Every server-side MUST —
RFC 9728 metadata, the `WWW-Authenticate` challenge, audience validation, no token
pass-through — is satisfied by Decision 2 without issuing anything.

This document is therefore not about MCP conformance. It is about one deployment shape:
**an operator with no OAuth authorization server who wants the hosted assistants to reach their
application.** Naming that correctly matters, because it is a much smaller population than
"everyone using MCP", and it changes the cost-benefit.

## What already exists

The starting position is unusually good, because the expensive half is done. Authentication —
three mechanisms, all converging on one `Principal` — is exactly the part that normally makes an
authorization server hard to bolt onto an application.

| Needed by an AS | Present today |
| --- | --- |
| User authentication | Local login, OIDC relying party, SAML SP — all producing one `Principal` |
| Durable session layer | `JdbcSessionStore` (389 lines): cross-node, TTL, idle timeout, per-device revoke |
| PKCE | `Pkce` — 256-bit verifier, `S256` challenge, `state` and `nonce`, JDK-only |
| Signing primitives | `Signatures` — JDK `KeyPairGenerator`/`initSign`, PKCS#8 and X.509 encoding |
| JWT verification | `JwtAuthenticator`, `Jwks`, `JwksKeySource` with rotation and refresh-floor |
| Authorization | `PolicyEngine`, deny-by-default |
| Consent screen | An HTML page; the template layer already exists |

What is missing is the OAuth protocol surface: `/authorize`, `/token`, RFC 8414 metadata, a
published JWKS, signing-key lifecycle, an authorization-code store, refresh rotation with reuse
detection, consent persistence, and a client identification story.

## Decision 1 — neither build nor adopt, yet

The natural next question is whether to write that surface or take a library. Both answers are
unattractive right now, and the reasons are specific rather than general.

**There is no embeddable, actively maintained, non-Spring OAuth 2.1 authorization server library
in Java.** The candidates and what each actually is:

| Candidate | What it is | Why it does not fit |
| --- | --- | --- |
| Spring Authorization Server | A real AS library | Built on Spring Security's servlet filter chain. The Spring adapter was deliberately removed; it does not come back for this |
| Nimbus `oauth2-oidc-sdk` | A **toolkit** — protocol messages, framework-independent, Apache 2.0 | Parses and builds requests and responses. You still write the endpoints, the grant state machine, the client registry, consent and rotation |
| Apache CXF `rs-security-oauth2` | **The only embeddable non-Spring AS that exists** — see the correction below | JAX-RS API on the endpoint classes, no CIMD, and a 2026 CVE record that has to be weighed |
| Authlete | Apache-2.0 wrapper | The protocol logic runs in a paid service the application calls out to |
| Keycloak | Standalone product | Every in-process adapter was **removed in 25.0.0**; embedding is not merely discouraged, it is gone |
| ORY Hydra, Zitadel | Standalone products, both Go | Cannot be embedded in a JVM at all. Zitadel also relicensed to **AGPL-3.0-only** in 2025 |
| **http4k `http4k-security-oauth`** | Apache-2.0, Kotlin, no Spring, no servlet, actively released | The one candidate whose runtime model fits — and **its own KDoc disclaims production use** (see below) |
| Apereo CAS, Apache Syncope WA | Real OIDC providers | Spring Boot applications; Syncope's is built on CAS. Not liftable |
| Apache Oltu, AzIdP4J, kotlin-oauth2-server, MITREid Connect | Framework-agnostic attempts | All dead. `light-oauth2`, the one Undertow-based option, has had its **repository deleted** — though oauth.net still lists it |
| pac4j, Quarkus/SmallRye OIDC, Vert.x auth, Micronaut, Helidon | Client and resource-server side | No authorization server. `camel-oauth` is a relying party too |

**A correction worth stating plainly, because the first pass of this research got it wrong:
Apache CXF is not Spring-bound.** Its published POM lists all five Spring artifacts at `test`
scope and the servlet API as `provided` and `optional`; the documentation gives an explicit
bootstrap for a classpath with no Spring on it, registering the JAX-RS binding factory with the
bus by hand and starting services programmatically without a servlet container. It also fits
this stack unusually well: its CI matrix covers JDK 17, 21 and 25, and `camel-cxf-rest` 4.22.0
depends on CXF 4.2.3 — the same Camel LTS baseline this project pins. It ships the real
endpoints (`AuthorizationCodeGrantService`, `AccessTokenService`, introspection, revocation,
RFC 8414 metadata, a JWKS service), enforces PKCE through `requireCodeVerifier`, and **rotates
refresh tokens by default** (`recycleRefreshTokens = true`). Storage is an `OAuthDataProvider`
SPI of seven methods, so the database layer would be TesseraQL's own.

So the honest position is not "nothing fits." It is: **one thing fits, and its security record
is the reason to hesitate.** CXF's advisories list **thirteen OAuth2 and OIDC CVEs disclosed in
2026**, against one in 2021 and two in 2019. Among them: an authentication bypass in the
introspection service caused by a missing `throw`; PKCE and nonce silently overridden;
authorization-code replay, twice; a revocation bypass; scope self-escalation through dynamic
client registration; missing JWT audience and issuer validation; and a time-of-check-to-time-of-use
race in refresh-token processing. It is not on the OpenID certified provider list.

That reads two ways and both are true. The code was evidently under-reviewed for years and a
researcher walked through it in 2026 — but it is also being fixed fast and in the open, with
hardening still landing after 4.2.3 was cut, and several of the CVEs sit in the shipped
encrypting and cache-backed data providers that this project would replace with its own anyway.
The point that survives either reading is the one that matters here: **a twenty-year Apache
project with a complete authorization server shipped precisely the bug classes this document
would worry about writing by hand — including the refresh-rotation race, one of the six things
on the missing list above. Adoption does not buy correctness. It buys breadth.**

**A third option exists and should be recorded rather than discovered later.** CXF's domain
layer is separable from its endpoint layer: `AccessTokenGrantHandler`, `OAuthDataProvider`,
`Client` and `ServerAccessToken` are plain Java, and the only JAX-RS leak in the grant SPI is a
`MultivaluedMap` in one signature. The `*Service` classes are tightly bound to JAX-RS, but the
protocol logic beneath them is not. So a build could take CXF's grant handling and write its own
endpoints as ordinary compiled routes, needing the JAX-RS *API* jar but not a JAX-RS runtime.
That inherits the reviewed protocol logic without the runtime — and inherits its defect history
too.

**One further candidate deserves naming because it is shaped exactly right and still cannot be
adopted.** http4k's `http4k-security-oauth` is Apache-2.0, Kotlin, needs neither Spring nor a
servlet container, releases every few days, and models an HTTP handler as a plain function from
request to response — which bridges to a compiled route cleanly. It ships a token endpoint, an
authorize endpoint deliberately split so the consent UI stays yours, a JWKS binding, authorization-
server metadata, RFC 9728 resource metadata, and a `codeChallengeMethod` lens that rejects
anything but `S256`. And its own KDoc says: *"this implementation is intended for general
development, testing and prototyping rather than as a security-hardened production authorization
server."* Refresh-token rotation is not implemented and the client registry is an interface you
supply. It is a reference design worth reading before writing anything, not a dependency to lean
on for a security boundary.

Also worth knowing for whoever builds this: `navikt/mock-oauth2-server` is an actively maintained,
Spring-free, Nimbus-backed authorization server that issues real tokens against a real JWKS and is
explicitly a **test double** — no persistence, no client authentication, tokens for anyone. Useless
as a product and genuinely useful as the integration-test fixture on the other side of whatever
gets built.

**And the standards are not settled.** OAuth 2.1 is `draft-ietf-oauth-v2-1`, IESG state "I-D
Exists", with a working-group milestone to submit to the IESG in December 2026 — publication
realistically 2027 or later. Client ID Metadata Documents, the mechanism that would let this
design skip a client registry entirely, is at draft-02, and **both -01 and -02 added SSRF
protections**: CIMD makes the authorization server fetch a URL the client supplies, and the
specification is still actively revising the security requirements around exactly that. Building
CIMD today means tracking a moving security target in the one place an authorization server is
most exposed.

**And the client-identification question is itself in motion, which is a reason to let someone
else own it.** Revision 2026-07-28 **deprecated Dynamic Client Registration** and names CIMD as
its migration path, with removal possible in any revision from 2027-07-28 onward. The arc across
revisions reads SHOULD, SHOULD, MAY, then MAY-and-deprecated. So an implementation started today
would build the mechanism that is being retired, or the one whose security requirements are
still being drafted, or both. Neither is a good place for a first authorization server.

Worth recording for the companion-deployment path, because it changes what to recommend: **DCR
is not actually required by the clients.** Claude's custom connectors accept an operator-supplied
pre-registered client ID with an *optional* secret, and Claude selects CIMD only when the
authorization server advertises both `client_id_metadata_document_supported` and `none` among its
token-endpoint auth methods, falling back to DCR otherwise. Pre-registration is therefore both
the lowest-ceremony option and the one that avoids exposing an anonymous registration endpoint.

## Decision 2 — an authorization server is a different category of code from what is hand-rolled here

There is a tempting argument that this project already hand-rolls a SAML service provider and a
JWT verifier, so an authorization server is more of the same. It is not, and the difference is
worth stating because it is the whole reason for the recommendation.

**What is hand-rolled today is closed, stateless and testable.** A signature either verifies or
it does not. The inputs are one document and one pinned key. The attack classes are enumerable
and each has a specific, reviewable defence — and the implementation gets them right:
`SamlResponseValidator` sets `disallow-doctype-decl` and `org.jcp.xml.dsig.secureValidation`
(the documented mitigation for the XSLTC compiler RCE reachable through signature verification),
pins the identity provider's key with a `KeySelector` that ignores the in-message `KeyInfo`
entirely, requires the signature to cover exactly one same-document reference, and consumes only
the assertion inside the signed subtree. `HmacSignatureVerifier` compares with
`MessageDigest.isEqual`. `JwtAuthenticator` binds the algorithm from configuration rather than
from the token and rejects a mismatch before touching key material, which closes algorithm
confusion structurally — a stronger posture than the default usage of several JOSE libraries.

**An authorization server is stateful protocol policy.** Authorization-code to PKCE-verifier
binding, one-time code redemption under concurrency, refresh-token rotation with reuse
detection, consent persistence and revocation, redirect-URI matching, and — with CIMD — fetching
an attacker-supplied URL. None of that is cryptography. All of it is the kind of logic where the
bug is a race or a missing binding rather than a wrong algorithm, which is why CXF's rotation
race was a CVE and why "we were careful" is a weaker defence here than it is for a signature
verifier.

The supply-chain posture recorded in [authentication.md](authentication.md) — "All JWT and
API-key crypto is JDK-only — there is no JOSE/JWT third-party dependency, matching the SAML
module's supply-chain posture" — is therefore not a blanket rule to extend. It is a
per-area judgement that happens to be right for verification and does not automatically transfer
to issuance.

## Decision 3 — if it is built: two modes, declared rather than inferred

Should the decision reverse, the shape below is the one to build.

An application chooses between pointing at someone else's authorization server and being one
itself, and that choice is a **declared configuration value**, not something inferred from which
login the application happens to have enabled. `tesseraql.oidc.enabled` and
`tesseraql.saml.enabled` are independent booleans and both can be true at once, with the local
login mounted alongside; there is no single fact to infer from. Inference would also couple two
unrelated decisions: an application may well use OIDC for browser login and still need TesseraQL
to be the authorization server, because its provider issues opaque access tokens or will not
mint a token bound to this resource.

Delegate mode is already designed — it is Decision 2 of the hardening campaign, and amounts to
naming the upstream issuer in `authorization_servers`. Two failure modes belong to it and should
be checked at startup rather than discovered as a runtime 401: the provider may not support
resource-bound audiences (Microsoft Entra ID requires the MCP server URL registered as an
Application ID URI, or the token request fails outright), and it may issue **opaque** access
tokens, which signature verification cannot validate at all and which would need RFC 7662
introspection that does not exist here.

## Decision 4 — if it is built: self mode federates for authentication only

In self mode TesseraQL owns `/authorize`, `/token`, its metadata and its keys, and the
authentication step delegates to whatever the application already has. The client is redirected
to `/authorize`; if there is no session, the existing login path runs — local form, OIDC
redirect, or SAML SP-initiated flow, unchanged; the resulting `Principal` is the same object
every route already consumes; consent is recorded; an authorization code is issued and exchanged.

This is the classic reason to place an authorization server in front of SAML, and it is the one
genuinely uncovered case: a SAML identity provider cannot issue OAuth tokens, so nothing else in
the deployment can bridge SAML to OAuth. It also means the audience is always TesseraQL's own,
which makes the two delegate-mode failure modes above disappear entirely.

If this is built, build the state machine here and take `oauth2-oidc-sdk` only for message
parsing if anything at all. Do not take Spring.

## Decision 5 — if it is built: scopes are the unsolved problem, not the endpoints

The endpoints are the visible work and the smaller half. The real design problem is that **OAuth
consent is expressed in scopes and TesseraQL has no scope concept at all.** `Policy.Rule`
supports role, permission and claim-name-plus-value; no `scope` or `scp` claim appears anywhere
in the codebase. A consent screen has to show the user something, and "this application may act
as you" is not an acceptable answer.

Three candidate models, none free:

1. **Scope equals policy name.** Reuses what exists, but exposes internal policy identifiers on
   a consent screen users read, and freezes them as a public contract.
2. **A small fixed vocabulary** (`mcp.read`, `mcp.write`, …) mapped to policies by configuration.
   Comprehensible on a consent screen, and the coarseness is honest about what consent means.
3. **Scope equals a set of MCP primitives.** Most precise, worst consent screen, and it grows
   without bound as an application grows.

The second is the most defensible, and it is still a design decision of its own weight. The MCP
specification also expects servers to account for scope hierarchies, where a broader scope
implies narrower ones — a concept with no home here.

## What to do instead

1. **Audience validation on the bearer path** — [audit-hardening.md](audit-hardening.md) slice 1.
   Roughly twenty lines of logic, closing a real confused-deputy hole, and the same defect CXF
   shipped as a CVE. This is the highest-value security change available in either document.
   Do not read those twenty lines as the cost: because the audience becomes *required*, the
   fan-out reaches seven gallery apps, fourteen files declaring `auth: bearer`, the scaffolder
   template and 28 test sources. The hardening campaign's §Slices prices it, and it is not a day.
2. **The resource-server slices** — Decision 2 of the same campaign: the metadata document and
   the challenge. Satisfies every MCP server-side MUST without issuing anything.
3. **For an application with a SAML identity provider**, the supported answer is a companion
   authorization server that federates to it, putting the token-issuing responsibility in
   software built for it. **Recommend Keycloak specifically, not a list.** On brokering alone
   several products qualify, but on fit for this job they are no longer comparable: Keycloak is
   Apache-2.0, turnkey, brokers SAML to OIDC as a first-class feature, shares an existing
   PostgreSQL through a schema setting, reaches a development instance in one `docker run`,
   ships CIMD as well as DCR, and is the only one of them whose vendor publishes an MCP
   authorization-server guide with a Claude integration section.

   Three things belong in that documentation beside the name. **Prefer a pre-registered client
   over dynamic registration** — the clients accept an operator-supplied client id, it is less
   ceremony, and it avoids exposing the registration endpoint that carried two of Keycloak's
   August 2026 high-severity advisories. **Wire the audience explicitly**, because Keycloak does
   not support RFC 8707 resource indicators; an audience mapper has to put the MCP resource
   identifier into `aud`, which is exactly what the bearer path validates — the same defect
   class as slice 1 of [audit-hardening.md](audit-hardening.md), arriving from the other side.
   This is not a Keycloak deficiency to route around but a fact about the whole field: no
   self-hostable identity provider surveyed implements resource indicators, which is precisely
   why open question 6 settled on matching the token's `aud` against a declared audience list
   rather than requiring the authorization server to honour RFC 8707. Had that gone the other
   way, there would be nothing to recommend.

   Note also what is *correctly* absent: none of these publish RFC 9728 protected-resource
   metadata, and none should. That document describes the resource, so TesseraQL serves it —
   Keycloak's documentation says as much, calling it out of scope because it belongs to the
   resource server. An identity provider without it is not incomplete.
   And **say that running current is not optional**: Keycloak's advisory stream lands hardest on
   precisely the two features this topology depends on, SAML brokering and client registration.

   Field evidence says the audience mapper is not optional. Practitioners running this exact
   topology report that Claude.ai did not send the `resource` parameter at all, leaving `aud`
   empty and forcing them to disable audience validation — the failure this framework's own
   slice 1 exists to prevent. Since no authorization server in the field honours resource
   indicators anyway, binding the audience is a configuration step the operator performs, not
   something to expect from the protocol. The same reports note that Claude Code omits `scope`,
   which has been an open defect since 2025, so a first integration should expect to debug the
   client as much as the server.

   The alternatives were checked and none displaces it. Zitadel is closer than the rest — it
   shipped dynamic registration in August 2026 with the MCP clients named as the motivation, is
   MCP-aware enough to infer a native application type from a custom-scheme redirect, and has
   CIMD in flight — but it carried three critical advisories in three weeks, one of them
   unauthenticated pre-hijacking through a forged external identity provider callback, which is
   this exact path. That security record, not the licence, is what keeps it second. State the
   licence precisely rather than as a disqualifier: the core is AGPL-3.0-only with the login
   application MIT, and running an unmodified copy as a separate process does not reach the
   user's own application, because the network clause attaches to modified versions. The
   exposure is real for an operator who patches it, and for any legal review that flags an
   AGPL component on sight — a consideration to document, not a reason to strike it out. Its disclosure practice is fast and its release
   cadence weekly, which is the charitable reading; the operational catch is that ten of its
   recent advisories carry no CVE identifier at all, so dependency scanners see none of them.

   ORY Hydra is headless by design: it manages no users and its own quickstart has you deploy a
   login and consent application, so it adds the work the topology exists to avoid — and its
   dynamic registration is currently unusable by MCP clients, emitting fields that strict
   RFC 7591 parsers reject, with the one-line fix unmerged for nine months and third parties
   shipping proxies to strip them. **One honest inversion belongs here though:** for an
   application whose only identity source is its own user table, Hydra's headlessness stops
   being a cost. Credential checking happens in a login application the operator writes, so
   querying an existing table is ordinary code — whereas Keycloak requires a User Storage SPI
   provider, a Java module built into the image. Neither is free, and the Hydra route means
   owning a web application on a security boundary, but the ordering genuinely reverses for
   that one case. authentik gates production use of its enterprise directory
   behind a subscription, has no stable open-source release with dynamic registration, and has
   two high-severity advisories on the SAML source path — one signature wrapping, one NameID
   comment truncation. Janssen has the best CIMD implementation of the field, with private-address
   blocking on by default, but publishes an 8 GB and four-CPU requirement and a demo of six
   containers including two additional datastores. Authelia is the smallest and cleanest and has
   no inbound SAML at all, with its OIDC provider still in beta after five years. Dex is the only
   other one with an inbound SAML connector, and its own documentation calls that connector
   unmaintained and likely vulnerable to authentication bypass.
4. **For an application whose only identity source is TesseraQL's own login**, the locally
   running clients — Claude Code, the Codex CLI, the ChatGPT desktop app — accept a fixed bearer
   token today and need none of this. The uncovered case is narrower than it first appears: it is
   specifically *the hosted assistants* plus *no identity provider at all*.

5. **And the companion need not be the operator's problem to install.** Keycloak is a Java
   application, so the JVM this framework already ships can run it — no second runtime, no
   separate JDK. That was verified rather than assumed: Keycloak 26.7.1 starts on OpenJDK 25 in
   about five seconds and serves its key set, including on a jlink runtime built from the exact
   `--add-modules` list the app images use. Keycloak's own supported-configurations document
   lists OpenJDK 17, 21 and 25 and recommends 25 for production; only FIPS mode is restricted to
   the older two. There is no native binary and the project says plainly that Keycloak needs a
   JVM, which makes sharing the one already present the intended shape rather than a trick.

   The distribution is published to Maven Central as `org.keycloak:keycloak-quarkus-dist`, a
   167 MiB zip that unpacks to 184 MiB. That fits the mechanism `dev --embedded-db` already
   uses: nothing is bundled in the CLI, the artifact is resolved on demand through the same
   embedded resolver, pinned to a version property. It is in fact a better fit than the database
   is, because the unpacked tree is platform-independent — the native libraries ship inside the
   jars for every platform at once, so one copy serves Linux, macOS and Windows.

   Four mechanics decide whether this works, and each was confirmed by running it:

   - **Launch with `java -jar lib/quarkus-run.jar`, never `-cp`.** The runner jar's manifest
     carries `Add-Opens: java.base/java.lang` and `Enable-Native-Access: ALL-UNNAMED`, which the
     JVM honours only for `-jar`. Under `-cp` the same command fails with an illegal-access error
     on the Vert.x threads and a restricted-method warning from the cache layer.
   - **Pre-build at packaging time and start with `--optimized`.** Keycloak augments its own
     bytecode; doing it once when the artifact is assembled removes it from startup entirely.
   - **The supervisor must implement the relaunch protocol.** When Keycloak decides it needs to
     re-augment, it exits with **code 10** and expects its launcher to start it again with
     `-Dkc.config.built=true`. A parent that treats exit 10 as a crash will see a server that
     appears to die on startup — which is exactly what a first attempt here did.
   - **Point every writable path outside the distribution and pass `--cache=local`.** The tree
     itself runs read-only, but the database, the temporary directory and the transaction logs
     default to locations inside it, and the default clustered cache opens JGroups ports that a
     single companion process has no use for.

   The costs are honest and worth stating beside the mechanics: roughly 168 MiB added to what an
   operator downloads on first use, around 560 MB resident for the child process, and a version
   pin that has to be maintained — the same lifecycle tail `--embedded-db` already carries, with
   the same eventual question about upgrades across major versions.

   **And the same scope limit, for a stronger reason.** `--embedded-db` is documented as being
   for development and demos rather than multi-node production, with operators pointed at a
   shared server instead. An embedded identity provider inherits that caveat and does not merely
   inherit it — it is an architectural mismatch rather than a single-process limitation.

   This framework's multi-node model is that every replica is identical, the database arbitrates,
   and nothing else is shared. Keycloak cannot be deployed that way. Its own production guidance
   requires the cluster port to be reachable from every other node and a round-trip latency under
   ten milliseconds between instances; database-backed discovery became the default in 26.1 and
   removed the need for multicast, but it discovers peers only — the cache traffic itself rides
   TCP between nodes on two ports that must be open pairwise. Persistent user sessions, default
   since 26.0, narrow the gap without closing it: authentication sessions, action tokens,
   brute-force counters and the invalidation cache that makes multi-node correct at all remain
   cluster-resident.

   Running several nodes with the cache set to local instead is not a workaround, and the reason
   to say so plainly is that **it fails silently rather than loudly**. Realm and user cache
   invalidation stop propagating, so an administrative change is simply not seen by the other
   nodes until a cache entry expires. Single-use tokens become replayable, because without the
   clustered cache they are held per node. Brute-force budgets multiply by the node count.
   Keycloak's own scheduled work arbitrates through that same cache, so every node runs
   everything. And the newest feature that looks like it would rescue this — a database outbox
   for cross-cluster invalidation — selects its recipients from the rows the cluster-discovery
   protocol writes, so with a local cache the recipient list is empty and every invalidation
   event is discarded without a word.

   There is also a trap specific to the embedded shape: **clusters sharing a database merge
   unless they are given distinct names.** Several child processes started with default
   configuration would discover one another through the shared database and then demand exactly
   the node-to-node connectivity the deployment model excludes, failing with cluster-formation
   timeouts rather than with a clear message.

   One thing does work cleanly in the embedded shape and is worth recording so nobody re-derives
   it: **signing keys are persisted in the database**, in the component-configuration rows rather
   than in memory, so every node reading one database publishes the same key set. The Infinispan
   cache named for keys is a red herring — it holds external public keys fetched from clients and
   identity providers, not the realm's own signing material. Realm import must also be set to
   ignore an existing realm; the overwrite mode would recreate it on every restart.

   **Concurrent first starts are a weaker guarantee than they look, and this correction matters
   more than the reassurance it replaces.** Keycloak does take a global database lock around
   bootstrap, and schema migration takes another. But its maintainers say plainly that starting
   instances in parallel is not recommended, and have acknowledged that the documentation wrongly
   implies the lock makes it safe. The record supports them: a missing bootstrap lock shipped in
   the 20 line, model migration went unlocked again in 26.3.0 through 26.3.3, and — directly
   relevant to any version pin chosen here — **the lock's own wait timeout was miscomputed in
   26.6.0 through 26.6.4 and in 26.7.0**, turning a thirty-minute wait into under two seconds. If
   this is ever built, pin at 26.7.1 or later, and seed the database once before anything scales
   rather than relying on winning the race.

   So the honest scope is: **one companion instance per deployment, not one per replica.** A
   single Keycloak with a local cache and sessions persisted in the shared database is a
   supported configuration; its only cost is that it becomes a single point of failure, which is
   documentable. Anything beyond that is a Keycloak cluster with its own network requirements,
   which the operator runs deliberately — not something this framework should start on their
   behalf.

6. **The login screen can carry this framework's own identity, and the cheap route is unusually
   available here.** Keycloak's FreeMarker theme system is still the only supported way to change
   those pages — there is no bring-your-own-login mode, no headless authentication API, and the
   one internal SPI that renders forms is marked private and unstable — but the shape it takes
   fits the embedded arrangement above without friction.

   A theme placed as a **folder** under `<dist>/themes/<name>/login/` requires no `build` step,
   which means it works with `start --optimized`, triggers no exit-code-10 relaunch, and is
   read-only content compatible with a read-only distribution. A theme packaged as a JAR into
   `providers/` would require a rebuild; the folder form does not. That decides the mechanism.

   Most of the work is not template work. Keycloak's shipped login theme routes essentially every
   CSS class through `theme.properties` — roughly 140 entries such as the input, primary-button
   and form-group classes — so remapping those to the component kit's own classes restyles the
   whole flow without overriding a single template. That matters because this framework's design
   system is class-based and already ships its tokens as a separate stylesheet, parameterised by
   the configured neutral, exactly as the bundled sign-in page links it. Copy those two
   stylesheets into the theme's resources at packaging time and the palette is not reimplemented,
   it is reused.

   The starting point is better still: a sign-in page in this framework's own idiom already
   exists, standalone rather than inside the app shell, a plain HTML form with no inline script.
   Porting that markup from one server-side template language to another is mechanical, and it is
   the same HTML on the other side. Override `template.ftl`, `login.ftl` and the consent page and
   the visual identity is carried; every further override is a file to merge by hand later.

   Three constraints belong in whatever ships:

   - **Parent from `keycloak.v2`, never `keycloak`.** The older login theme is deprecated and its
     removal is scheduled for Keycloak 27. The two have disjoint CSS vocabularies, so a theme
     built against the wrong parent is not portable to the right one.
   - **Bundle the assets; do not serve them from this framework's origin.** Keycloak's default
     policy is permissive enough to allow it — it sets only frame and object directives, with no
     style or script source restriction — but a login page whose stylesheet depends on the
     application server being reachable is a worse failure mode than a copied file, and a
     stricter default policy is on Keycloak's own roadmap.
   - **Scope the theme to the client** rather than the realm where only this framework's own
     login should change; the client attribute takes precedence.

   The account and admin consoles are a different matter and should be scoped out: both are React
   applications booted from a single template, so a theme can change the favicon, the title and
   an injected stylesheet, and nothing structural. Not exposing them to end users at all is the
   simpler answer.

   **The cost to state plainly is the maintenance contract.** Keycloak offers no backward
   compatibility for themes — a core maintainer has said so directly — and the documented upgrade
   procedure is a manual three-way diff against the new built-in templates. Every minor release
   in the 26 line so far has shipped a theme-affecting change: resource paths rewritten and two
   JavaScript libraries dropped, dark mode turned on by default, a shared partial that began
   rendering an extra field, message-bundle filenames realigned, HTML disallowed in message keys,
   base themes made abstract, and the FreeMarker engine moved off its legacy defaults. At roughly
   four minors a year, a custom theme is a standing obligation, not a one-time piece of work —
   which is an argument for the `theme.properties` route over template overrides wherever the two
   would both do.

## Revisit when

Concrete triggers, so this does not get re-litigated on vibes:

- OAuth 2.1 reaches IESG submission or later (the working-group milestone is December 2026), and
  Client ID Metadata Documents stops revising its SSRF requirements between drafts.
- An embeddable, non-Spring, maintained OAuth 2.1 authorization server library appears — or
  `oauth2-oidc-sdk` grows endpoint implementations rather than message objects.
- A real deployment presents the uncovered case: hosted-assistant access, a SAML or local-only
  identity source, and a refusal to run a companion authorization server. Two of the three is
  not enough; the third is what makes this unavoidable.
- The scope model is decided for another reason, removing Decision 5 from this document's cost.

## Out of scope

- **Being an OpenID Connect provider.** Issuing ID tokens, a UserInfo endpoint, and the
  attendant conformance surface is strictly more than issuing access tokens for one resource,
  and nothing in the driving question needs it.
- **Replacing the SAML service provider with OpenSAML** — rejected, and this is now the
  best-evidenced decision in either document, because the alternative was measured rather than
  assumed. OpenSAML 5.2.3 was resolved and probed on Java 25.

  The footprint is **43 to 47 jars and roughly 25 MB**, against a module that currently has
  **zero** third-party dependencies. It is essentially irreducible — BouncyCastle alone is
  8.6 MB, and a full template engine arrives for the HTTP-POST binding form. (Correcting an
  earlier draft of this document: there is no Woodstox or StAX in that tree.)

  Three costs are structural rather than merely large. **OpenSAML 5.x is not on Maven Central**,
  which has been frozen at 4.0.1 since February 2021; it lives only in the Shibboleth
  repository, where `.sha256` files are absent. **Shibboleth issues security advisories without
  requesting CVE identifiers**, so between that and the absence from Central, Dependabot and
  Renovate are blind to it — a project that keeps its dependency alerts at zero would be
  trusting a mailing-list subscription instead. And **the binding layer is hard-wired to the
  servlet API**: every HTTP encoder and decoder references `HttpServletRequest`, so a runtime
  with no servlet container must either adapt to it or bypass that layer entirely.

  The security argument is the decisive one, and it runs the opposite way to the intuition.
  Adopting OpenSAML would not have produced safety by default. Probed directly: a correctly
  signed assertion with **no `Conditions` element, no expiry and no `InResponseTo`** validates
  as VALID unless the integrator sets four separate `*_REQUIRED` flags; `SAML20AssertionValidator`
  accepts `null` for both the trust engine and the signature prevalidator, silently disabling
  signature verification and the XSW check; an `rsa-sha1` signature passes; and the same
  assertion validated six times in a row, because replay protection is a separate component
  nothing wires for you. Each of those is something this implementation already does
  correctly — `secureValidation` refuses SHA-1, the key is pinned, and `SamlReplayGuard` is
  database-backed with one-time consumption.

  One genuine advantage OpenSAML has is worth recording precisely, because it points at a better
  hardening item than the one first proposed. OpenSAML Java survived the 2018 comment-injection
  wave through a **parser default** — its pool strips comments before the DOM exists — not
  through any SAML-level check, and an integrator who set `ignoreComments = false` could switch
  that off. Shibboleth's response was the instructive part: from v3.4 the setting became
  mandatory and a comment or CDATA child inside a SAML element is **rejected at unmarshalling**,
  a hard error rather than something downstream code has to be careful about.

  This implementation is immune by a different route — `text()` calls `getTextContent()`, which
  concatenates and excludes comments — which is correct but *behavioural*. Copying Shibboleth's
  answer is cheap and converts it to structural: reject comment nodes inside signed SAML elements
  at parse time, so no future accessor choice can matter. See
  [audit-hardening.md](audit-hardening.md) Decision 10.

  Worth recording as validation rather than as a change: the algorithm this service provider
  uses — resolve the element the signature actually covers, then consume only what is inside it —
  is the same one SimpleSAMLphp used, which was one of only two implementations to resist every
  test case in the study that broke eleven of fourteen SAML frameworks. OpenSAML was among the
  eleven, and its own XSW hardening is earned rather than inherent: it carried CVE-2011-1411 and
  was broken again by a novel variant in that study, after which the node-identity check it now
  ships was added.

  Shibboleth itself discourages the use being contemplated, describing the libraries as not
  providing a complete service provider and as "meant solely to support individuals who have
  taken the time to read and understand the specifications", citing "the serious risks
  associated with implementing security software". Nor is there a turnkey alternative:
  `com.onelogin:java-saml` last published in February 2022, the Keycloak SAML adapters were
  removed, and pac4j-saml — despite being servlet-free — is **not Spring-free**, since
  `org.springframework.core.io.Resource` appears in the public API of its mandatory
  configuration class.

  Re-open only if encrypted assertions, IdP-initiated SSO or multi-provider metadata rotation
  are added — assertion decryption in particular adds padding-oracle surface where hand-rolling
  stops paying.
- **Replacing the JWT verifier with a JOSE library** — declined, but on narrower grounds than
  the supply-chain argument that rejected OpenSAML, because that argument does not transfer.
  `nimbus-jose-jwt` has **zero required transitive dependencies**: one jar, and the Gson and
  concurrency annotations it once pulled are shaded inside it as of 9.24. Its algorithm pinning
  is one constructor argument, so the config-bound guarantee is re-imposable rather than lost.
  (That concern *does* apply to `jjwt`, which structurally cannot pin an algorithm — a token
  signed RS512 is accepted by a parser intended for RS256. And it applies to
  `java-jwt`, whose JWKS story costs eleven jars and 5.4 MB because its cache is Guava-backed.)

  The reason to decline is simply that the verifier works, is small, and is tested — and the JDK
  covers what it needs. Two facts found while researching this are worth keeping because they
  make the hand-rolled position cheaper than it looks: `SHA256withECDSAinP1363Format` has existed
  since JDK 9 and emits the raw R‖S form JOSE wants, removing the DER transcoding that is usually
  the ugliest part of hand-rolling ECDSA; and JDK 25's SunEC does Ed25519 natively, where nimbus
  routes EdDSA through Google Tink and — verified across three versions — silently drops OKP keys
  from its standard JWKS verification path.

  Two counterweights belong with them. `Signature.verify()` on a corrupted Ed25519 signature does
  **not** reliably return `false`: with single-bit flips it threw `SignatureException` roughly a
  quarter of the time, so a forged token becomes a 500 unless that is caught. And the JDK has no
  JSON API and no near-term path to one — JEP 198 was withdrawn and its successor is an incubator
  module proposed for a much later release — so JSON parsing stays supplied here indefinitely.

  The calculus flips only on *issuing*: publishing a JWKS means key generation, rotation with
  overlap, `kid` assignment and public-key-only serialisation. `nimbus-jose-jwt` is the only
  candidate that closes all of that in one artifact, and `java-jwt` cannot publish a JWKS at all.
  JOSE adoption is therefore downstream of the decision in this document — decline the
  authorization server and it is never needed. Keep `oauth2-oidc-sdk` at arm's length either way:
  that is the artifact that carries json-smart and seven extra jars, not the JOSE library.

## Open questions

1. **Is the uncovered population real?** This document assumes it is small — hosted assistants,
   no identity provider, unwilling to deploy one. Nobody has counted. A single concrete
   deployment would change the recommendation more than any argument here.
2. **Should the companion-authorization-server topology be documented, tested, or shipped?**
   Documenting it is cheap. A tested reference deployment is a hosting-documentation project.
   Shipping one — bundling Keycloak the way the embedded database is bundled — is a much larger
   commitment and a different kind of product.
3. ~~**Does a session-to-token exchange stand on its own merits?**~~ **Closed 2026-08-15: yes,
   and it is being built** — [session-token-exchange.md](session-token-exchange.md). It reaches CI,
   scripts and every locally running assistant, and it still does not reach the hosted assistants,
   so this document's question is untouched: an operator who needs those needs an authorization
   server. What changed is the ordering. The exchange is what the deployments that exist today can
   actually use, so it goes first, and the resource-server slices wait for a deployment that needs
   them.
4. ~~**Two SAML hardening items surfaced while researching this.**~~ **Closed: they are
   [audit-hardening.md](audit-hardening.md) Decision 10, shipped as that campaign's slice 13 in
   wave A.** Recorded here in full because the reasoning is where it was found; the campaign
   carries the decision and the schedule. Neither item is deferred with this document.

   **Fail closed on comments rather than only testing for them.** The current defence is correct
   — `text()` uses `getTextContent()`, which concatenates text children and excludes comments —
   but it is behavioural and untested, so a refactor to `getFirstChild().getNodeValue()` would
   silently reintroduce the 2018 attribute-truncation class with every existing test still green.
   Probed on a stock parser the two accessors genuinely differ on the same payload: one returns
   the full signed string, the other the attacker's truncation. A regression test is the obvious
   fix and the weaker one. The better fix is Shibboleth's: **reject a comment or CDATA child
   inside a signed SAML element at parse time**, which is what OpenSAML has done as a hard
   unmarshalling error since v3.4, after discovering its own protection was a parser default an
   integrator could disable. That is a few lines, it removes the accessor question permanently
   rather than pinning one answer, and the regression test then guards a structural property
   instead of a habit.

   And `registerIds` marks every `ID` attribute as an XML id without rejecting duplicates, while
   the signed element is resolved twice — once inside signature validation and once by
   `getElementById`. They share one DOM registry so they should agree, but XML enforces no
   uniqueness, and rejecting duplicates removes the question for a few lines. The 2024–2026
   record says this is the class worth spending those lines on: what keeps breaking SAML
   implementations is two answers to "which element was signed" disagreeing — including through
   parser differentials, where the component doing the cryptography and the component feeding
   the application build different trees from identical bytes.
