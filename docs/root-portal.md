# The root redirect and the stack portal

Implementation design for [stack-architecture.md](stack-architecture.md) Decision 24: the origin
root always answers a 307 — to `root.redirect`'s application when `tesseraql-stack.yml` names one,
to the stack's portal when it does not — and the portal is a real framework surface inside the
`/_tesseraql/` fence. Decision 24 records the requirement and the drafts review removed; this
document records how it is built: the one structural decision, the code it touches, the slices, the
guards, the tests, and what is deliberately left out. Written 2026-08-18, before implementation.

Everything below was measured against main at #849 unless marked otherwise.

**Status 2026-08-18: all three slices shipped as designed.** Slice 1 (the surface runtime and
the origin fence), slice 2 (the portal page and provider), slice 3 (the root's 307,
`root.redirect`, TQL-APP-4215). The open questions below were each closed on their
recommendation; two implementation details the design flagged resolved cleanly (`createAll`
already treats an override as permission for `main` to be absent from config, so the portal
declares no datasources; `new` now writes a stack-level `.gitignore` covering `work/`). One
defect the design did not predict: the portal's first empty-state markup invented an
`hc-empty__body` class, which `HcMarkupContractTest` caught — the kit's word is
`hc-empty__description`.

## What exists today, measured

- The origin scope serves exactly two framework paths, both answered by the gateway itself:
  `/_tesseraql/health/live` and `/_tesseraql/health/ready` (`StackRelay.handle`, exact-match).
  Every other origin path that matches no member's `/<name>` prefix — including `/`, and including
  every other `/_tesseraql/*` path — is a 404 carrying `TQL-APP-4040`.
- Sign-in exists only at application scope. `StackModeIntegrationTest` pins the bounce:
  an unauthenticated HTML GET under `/shop-a/` redirects to `/shop-a/_tesseraql/login?next=…`.
  There is no `/_tesseraql/login` at the origin; `MultiAppGatewayIntegrationTest`
  (`frameworkEndpointsMoveUnderThePrefixToo`) pins that absence as a 404.
- The portal's ingredients — session resolution against the framework datasource, the
  `auth: browser` login bounce, shell rendering, `service:` providers — all live in
  `TesseraqlRuntime`. The relay deliberately performs no I/O on the event loop ("every decision
  here is an in-memory lookup on purpose"), and the gateway has no template mechanism, no session
  store handle, and no error renderer beyond a hand-written JSON line.
- No test asserts anything about `/`. The behaviour change breaks no existing root assertion; the
  one assertion it does break is named in the test plan below.

## The structural decision: the stack hosts its own runtime

The portal cannot be gateway content — that was Decision 24's first rejected draft, and the
measurement above shows it would also mean rebuilding sessions, rendering and providers inside a
component whose design principle is to decide everything from memory. It cannot ride a member
either: the relay forwards URIs verbatim, so no member's routes can answer an origin-scope path,
and electing one member would make the portal's availability depend on an arbitrary neighbour.

So **`MultiAppHost` starts one additional, framework-owned runtime beside the members — the stack
surface runtime — whose main application is a new bundled `portal` application, bound at the origin
scope** (base path `""`, the empty prefix `StackRelay.addresses` already understands but nothing
produces since #841). This is the first increment of Decision 14's "hosted alongside user
applications" mechanism, deliberately scoped: it carries sign-in, the account surface and the
portal, and nothing else. Studio, the ops console and IAM Admin stay per-application until their
own slices (7, 8, and the slice-4 remainder).

The surface runtime is not a member:

- it is not in the catalogue and has no `/<name>` address;
- it always starts — `--app-name` narrowing filters members, and the portal is how a narrowed
  stack still explains itself;
- the relay's tenant-entitlement header check does not apply to it (a framework surface serves
  every tenant);
- canary weighting does not apply to it;
- if it fails to start, the stack fails to start. It is the stack's sign-in; a stack that comes up
  without it would be the silent-degradation shape this campaign keeps deleting.

Cost, stated: one more in-JVM runtime per `dev`/`host` start — about 8 MB heap and 600 ms by
Decision 15's measurements. That is the price of Decision 12's parity (development and production
get the same screen), paid once per stack rather than once per application.

### The relay's origin fence

`StackRelay.handle` gains two steps between the health pair and member routing, making the order:

1. `/_tesseraql/health/live|ready` — answered by the gateway itself, unchanged, and deliberately
   **before** the fence: a load-balancer probe must not depend on the surface runtime being up.
2. `/` — the 307 (see "The root redirect" below).
3. **The origin fence: `/_tesseraql/*` and `/assets/*` forward to the surface runtime**, verbatim,
   through the same `vertx-http-proxy` path members use. A forward failure is the same 502
   `TQL-APP-5020` a member's would be.
4. Longest-member-prefix routing, tenant check, forward — unchanged.
5. No match — 404 `TQL-APP-4040`, unchanged. `/favicon.ico` and `/robots.txt` keep 404ing
   until a slice owns them. `/.well-known/*` joined the fence with token-issuance slice 7
   (2026-08-19): the surface runtime serves RFC 8414 metadata at the bare well-known.

**Why `/assets/*` is in the fence.** The framework already claims `<scope>/assets` at every scope:
the login page at `/shop-a/_tesseraql/login` loads `/shop-a/assets/vendor/…`, because asset URLs
are base-relative and the kit's WebJar is served by each runtime under its base path. The surface
runtime's base path is `""`, so its shell pages reference `/assets/vendor/…` at the origin. This is
the same one rule at the stack scope, not a new claim — but it makes the segment `assets`
shadowable by a legal member name where `_tesseraql` is not, so:

**TQL-YAML-1405 gains one reserved segment: an application may not be named `assets`.** The rule's
own definition — segment safety for the URL space — already covers it in spirit; this makes it
literal. The collision exists at application scope today too (an application's declared
`web/assets/…` route shares a namespace with the runtime's asset handler), so the rule is not
stack-specific. Decision 25's "what is honestly given up" paragraph is amended with the code PR:
the framework's root claim is `/_tesseraql/`, `/assets/`, and every application name.

### The portal application

A new bundled application at `tesseraql-camel-runtime/src/main/resources/tesseraql/apps/portal/`
(beside `auth-ui` and `account`, its co-residents), with the usual `.app-index`.

**It is not an `AppSourceProvider`.** The SPI mounts a surface into every runtime, which is exactly
the N-copies anomaly Decision 14 names; a portal mounted into members would answer at
`/<name>/_tesseraql/portal` once per application, each copy wrong. Instead `MultiAppHost`
materializes it directly — `ClasspathAppSource`'s unpacking, called once, into a stack-level work
directory (`<stack dir>/work/portal`; `AppDirectory`'s one-level scan never reaches it, and the
`work/` child itself has neither `config/` nor `web/` so it scans as NOTHING). The published
"five system apps" count therefore stays five. A consequence for source-tree stacks: the stack
directory grows a `work/` entry, which `new`'s gitignore template should cover with the same line
application homes already carry — verify at implementation.

Its bundled `config/tesseraql.yml` is the host config of its own runtime (not a mounted app's
whitelisted config), so it can and does declare:

- `tesseraql.app.name: portal`; `server.port: 0` (internal, ephemeral, like every member since
  #846);
- `security.defaults.routes`: `- match: /** → auth: browser, csrf: auto` — the same posture the
  five bundled apps carry;
- `tesseraql.apps.studio.enabled: false`, `tesseraql.apps.ops-console.enabled: false`,
  `tesseraql.apps.iam-admin.enabled: false` — a plain `TesseraqlRuntime` mounts all five system
  apps via `ServiceLoader`, and an origin-scope Studio editing the portal's own synthetic source
  would be worse than useless. *(Amended by docs/stack-shells.md: slice 1 removed the
  `ops-console` disable — the ops shell mounts at the surface — and slice 2 removed the
  `iam-admin` disable, one admin door to the stack's one store; only the Studio disable
  remains, until slice 8's own design.)* `auth-ui` and `account` stay enabled: `auth-ui` is what makes
  `/_tesseraql/login` exist at the origin, and `account` keeps the shell's account chip and
  "change my password" live at stack scope — Decision 14's own example of state that is
  stack-wide, arriving here a slice early because the shell references it;
- `tesseraql.mcp.enabled: false` — the portal declares no tools and the origin needs no empty MCP
  endpoint before the AS slices give it a purpose.

**The page**: `web/_tesseraql/portal/get.yml`, `recipe: query-html`, one source:

```yaml
sources:
  main:
    service:
      name: portal.apps.list
      params:
        tenantId: principal.tenantId
```

with a template on the shell. **The portal does not use the shell's `page()` fragment** — that
fragment carries `system-nav`, whose three links (Operations, Studio, IAM Admin) would all dangle
at the origin scope until slices 7/8. It uses the `shell()` variant with its own minimal nav, the
way application-owned pages do. The tile list itself follows the `app-menu` shape: one link per
application, `href` = the derived `/<name>`, label = the name (see open question 3).

The anonymous case costs nothing: `auth: browser` plus an unauthenticated HTML GET already
produces `302 Location: /_tesseraql/login?next=%2F_tesseraql%2Fportal` through
`ErrorResponseRenderer.redirectToLogin`, base-relative and open-redirect-guarded
(`LoginRedirects.isSafe`). Note the status: the login bounce is and stays **302**; Decision 24's
307 is the root's redirect, a different hop. `auth-ui`'s login route, mounted into the surface
runtime, answers at the origin because the runtime's base path is `""` — no auth-ui change at all.

**The provider**: `PortalProviders.register(serviceProviders, members)` in the runtime module,
mirroring `DocsProviders`. It is registered only when the host hands the runtime a member list:
`HostContext` gains `stackMembers` — a `List<StackMember>` (`record StackMember(String name,
List<String> entitledTenants)` or simply the `InstalledApp` list re-used) — set **only** on the
surface runtime's context, `null` for members, so no member runtime can see its siblings and
Decision 26's no-shared-declarations rule is untouched. The provider filters by the only
entitlement model that exists: `isEntitled(principal.tenantId)` — empty list means every tenant,
and the source-tree/dev path synthesises empty lists, so in `dev` every member lists. Per-principal
application grants are deliberately **not** invented here: `ops.app.<name>` means operational
visibility today, its shift toward "which applications appear in the switcher" is
stack-architecture open question 4 and belongs to slice 7. When that model lands, the portal's
filter widens in one provider. *(Landed for operations — docs/stack-shells.md: the ops shell's
switcher is the caller's `tql.ops.view.<name>` atoms applied to the member list. The portal's
own tile grant, `tql.app.use.<name>`, landed with that design's slice 2: the provider filters by
tenant entitlement ∧ the caller's `tql.app.use` atoms, deny by default — and the member's own
fence refuses on the same atom, so the tiles and reach are one answer.)*

**Datasources.** The surface runtime's main datasource is the stack's framework coordinate,
supplied through the existing `MainDatasourceOverride` path (`HostContext.forApplication(basePath,
override)`, shipped with #846) — the same coordinate `MultiAppHost` already resolves for the
migration pool: the stack file's when supplied, else the coordinate the members agree on
(TQL-APP-4211 is what makes agreement the stack's coordinate). Its `security` component validates
like every hosted runtime (the TQL-APP-4214 path); its `operations` component migrates
`tql_schema_history_portal` onto the framework database — named and accepted: the surface runtime
runs no jobs and serves no ops console, so the tables are inert bookkeeping. Whether the portal's
config needs a placeholder `db.main` declaration for the override to land on is an implementation
detail to verify against `carryingDeclaredQuery`.

**The name `portal` is an ordinary name, and a member may also use it.** The grammar cannot fence
it (no leading underscore is *forbidden*, not reserved-for-framework), and a reserved-word guard
would be a second mechanism defending a shape. The overlap, measured: a member named `portal` owns
`/portal` (no URL collision — the surface lives under the fence); its history table lives on its
own business database (no collision unless an operator points a business datasource at the
framework database, which is already their own doing); the residual overlap is that a
`tql.ops.view.portal` grant names both, and the ops shell's switcher lists members only — the
surface runtime is not a member — so the grant reaches nothing extra today. Revisit if the framework ever wants a fenced identity namespace —
that question belongs to the AS slices, which mint framework identities anyway.

### The root redirect

`StackRelay` answers `/` — after health, before everything else — with:

```
307  Location: <target><?query when present>
```

where the target is `"/" + name` when `tesseraql-stack.yml` declares `root.redirect: <name>`, else
`/_tesseraql/portal`. The query string is carried over verbatim; 307 preserves method and body by
definition, which for the root in practice means GETs, and the choice over 301/308 is Decision
24's cache argument. The relay learns the target as one constructor argument — a resolved string,
decided at start, no per-request lookup.

`StackSettings` gains `Optional<String> rootRedirect()`, reading the bare key `root.redirect`
(stack-file keys are bare — `framework.datasource.jdbcUrl`, `externalOrigin` — not
`tesseraql.*`-prefixed). `NewCommand.STACK_FILE_TEMPLATE` gains the commented example beside
`externalOrigin`'s.

**Validation**: at gateway start, against the **full** membership, before `--app-name` narrowing —
the file describes the stack, and the flag filters a run. An unknown name refuses the start with
**TQL-APP-4215** (next free after 4214), message in the 4211/4212 shape: name the configured
value, list the names the stack holds, name the two edits that fix it (correct the name, or remove
`root.redirect` from `tesseraql-stack.yml`). Under narrowing the redirect still emits; a
narrowed-away target then 404s exactly like every other link to a narrowed-away neighbour —
narrowing is a filter, not a second shape, and the portal (one flag away) is how the run explains
itself.

**Plumbing**: the gateway does not load `StackSettings` today; `MultiAppHost.start` does. The
widest `MultiAppHost.start` overload gains a `StackSettings` parameter, the narrower overloads
keep loading it themselves, and `MultiAppGateway.start` becomes the one loader on the gateway
path: load once, validate `root.redirect`, hand the same object down. No second read of the file.

**One normalization, owed to the design doc**: routes derive their paths from the `web/` layout,
so the portal's address is `/_tesseraql/portal` — no trailing slash, like `/_tesseraql/login`.
Decision 24 and Decision 17's table write `/_tesseraql/portal/`; amend both to the slashless form
with the code PR rather than teaching the relay a cosmetic second address.

## Slices

Three PRs, each independently green and observable:

| # | Slice | Contents | End state |
| --- | --- | --- | --- |
| 1 | The surface runtime | `MultiAppHost` materializes and starts the portal app (skeleton config, no page yet) as the surface runtime; `HostContext.stackMembers`; the relay's origin fence (`/_tesseraql/*`, `/assets/*`); the TQL-YAML-1405 `assets` segment; rewrite of the origin-404 test | Sign-in, logout and the account surface work at the origin; `/` still 404s |
| 2 | The portal page | `web/_tesseraql/portal/get.yml` + template; `PortalProviders` with the tenant filter; posture-test and surface-guard coverage | `/_tesseraql/portal` lists the entitled members; anonymous users bounce to origin sign-in and return |
| 3 | The root | The 307 in `StackRelay`; `StackSettings.rootRedirect()`; TQL-APP-4215 at gateway start; template comment in `new`; docs sweep | `/` does exactly one thing |

Slice 1 carries the portal application's skeleton because the runtime needs a main application to
exist at all; the page arrives with slice 2. Slices 1+2 can merge if review prefers one PR for
"the portal exists"; slice 3 stays separate either way, because shipping the 307 before its
default target exists would turn today's honest 404 into a redirect onto a 404.

## Guards

- **TQL-APP-4215** — `root.redirect` names an application the stack does not hold; raised at
  gateway start. Declared at the raise site with the constant-javadoc-as-reference-wording
  convention `MultiAppHost`'s 4211/4212 established. Mentioned in `hosting.md` so the generated
  reference gets a "Documented in" link (a mention only in internal docs gets none).
- **TQL-YAML-1405** — gains the reserved segment `assets`, enforced where the rule already lives
  (lint via `ApplicationNameRules`, boot via `ApplicationName.of`).
- **`FrameworkSurfaceGuardTest`** — the portal's route must carry an explicit `auth:`; satisfied
  by the app's `security.defaults.routes`, verified by the existing walk.
- **`BundledAppSecurityPostureTest`** — the portal's resource root joins the `@ValueSource` list,
  so every portal route resolves an explicit browser/public auth and CSRF on writes.
- **No new guard for the name `portal`** — see the overlap analysis above; a reserved-name
  mechanism is declined on the two-mechanisms signal.

## Test plan

Per slice, naming the existing files they extend:

**Slice 1**
- `StackRelayTest`: origin `/_tesseraql/<anything>` and `/assets/<anything>` forward to the
  surface stub's port; `/nope` still 404s `TQL-APP-4040`; the health pair is still answered by the
  gateway even when the surface origin is down.
- `MultiAppGatewayIntegrationTest.frameworkEndpointsMoveUnderThePrefixToo` **is rewritten**: it
  pins "origin `/_tesseraql/health` → 404", and that property flips by design. Its replacement
  pins the new pair of properties: per-app framework endpoints still answer under the prefix, and
  the origin now answers from the surface runtime.
- New IT (or `MultiAppHostIntegrationTest` case): the surface runtime starts against the stack's
  framework coordinate, validates rather than migrates `security`, and the stack refuses to start
  when the surface runtime cannot.
- Login at origin: `GET /_tesseraql/login` → 200 with `action="/_tesseraql/login"` (the
  origin-scope mirror of `aBundledAppPageUnderThePrefixPostsBackToItself`).
- Lint: `ApplicationNameRules` refuses `assets` (`TQL-YAML-1405`), boot path ditto.

**Slice 2**
- `StackModeIntegrationTest`: anonymous `GET /_tesseraql/portal` with `Accept: text/html` → 302 to
  `/_tesseraql/login?next=%2F_tesseraql%2Fportal`; sign in at the origin → portal 200 listing
  `/shop-a` and `/shop-b` links; the same session then browses `/shop-a/…` (one sign-in, pinned
  end to end across scopes).
- `PortalProviders` unit test: tenant filtering (entitled, unentitled, empty-list-means-all,
  anonymous never reaches it).
- `BundledAppSecurityPostureTest` + `FrameworkSurfaceGuardTest` as above.

**Slice 3**
- `StackRelayTest`: `/` → 307 `Location: /_tesseraql/portal`; with a configured target, 307
  `Location: /orders`; query string carried; health still wins over the redirect.
- `StackSettingsTest`: `root.redirect` parses; absent key → empty.
- Gateway-start refusal (the `StackFrameworkGuardTest` shape): unknown `root.redirect` name →
  `TQL-APP-4215` naming the members; a valid name passes; narrowing does not refuse.
- `MultiAppGatewayIntegrationTest`: end-to-end `/` → 307 → portal 200 through the front port.

## What moves in the docs, and when

With the code PRs, not before (they describe the CLI and URL space as shipped): `hosting.md` (the
root's behaviour, `root.redirect`, TQL-APP-4215), `CHANGELOG.md` (Added: the portal and the root
redirect; Changed: origin `/_tesseraql/*` now answers), the Decision 24 shipped-note in
`stack-architecture.md`, the Decision 17 table's trailing slash, Decision 25's root-claim
amendment (`/assets/`), reference regeneration (4215; `tesseraql.apps.portal.enabled` and the
other config reads appear automatically). `app-isolation-model.md`'s "five system apps" survey
stays true and untouched — the portal is not an `AppSourceProvider`.

## Deliberately not in this design

- **Removing the per-application `auth-ui`/`account` copies** (the slice-4 remainder). The
  per-app login bounce is base-relative and must change scope when the copies go; until then the
  copies are duplicates against one session store — harmless, and shared by construction.
- **IAM Admin at the origin scope** (slice-4 remainder) and the **Studio/ops switchers** (slices
  7/8). The portal's nav deliberately links neither.
- **Per-principal application entitlement.** No model exists; open question 4 owns it.
- **Tile metadata** — titles, icons, descriptions. Version 1 renders names, which are the address
  contract. See open question 3.
- **`/.well-known/*` at the origin** — token-issuance territory; claimed by its slice 7.

## Open questions

Each gated on the slice it blocks, with a recommendation. **All four were closed on their
recommendations at review (2026-08-18)** — kept as written because the alternatives they weighed
are the ones a reader is likely to propose again:

1. **The `assets` reserved segment** — *gates slice 1.* Recommended: yes, extend TQL-YAML-1405.
   The alternative (letting a member named `assets` shadow the surface shell's stylesheets) is a
   silent-breakage shape, and the claim already exists at every other scope.
2. **`account` mounted at the origin from slice 1** — *gates slice 1.* Recommended: yes. The
   shell's account chip makes it load-bearing; mounting it is a strict subset of the slice-4
   remainder, not a divergence from it.
3. **Tile labels** — *gates slice 2.* Recommended: names only. If human titles are wanted, the
   host already loads every member's config for the framework guard, so a `tesseraql.app.title`
   read is one line — but it is a new config key and deserves its own yes/no.
4. **The trailing slash** — *gates slice 3 (doc amendment).* Recommended: normalize the documents
   to `/_tesseraql/portal`, matching `/_tesseraql/login`, rather than teach the relay a second
   spelling.
