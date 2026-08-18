# Application isolation model

Status: **complete 2026-08-10** — every slice shipped.

The framework carried two multi-app models. One was documented, reachable, and shared
everything; the other isolated properly and had never had a caller. This decides which serves
what, scopes each honestly, and records what the framework does **not** promise.

## What existed when this was written

Read as the starting state, not as the current one. This section is the survey the decisions below
were made against; the slices then changed two of the facts in it, and the corrections are marked
where they occur.

**① In-process mounting** (`SystemApps` / `AppSources`). One `TesseraqlRuntime` mounts several
apps from two sources: the five `AppSourceProvider` system apps discovered through
`ServiceLoader` — Studio, the ops console, IAM Admin, account, auth-ui — and any number of
user apps declared under `tesseraql.apps.<name>` as a local `.tqlapp`, a fetched URL, or a
directory. Everything is shared: the Camel context, the datasource set, the configuration
(only `responseHeaders` is overridden per app), and the URL space. This is what
`deployment.md` documents as shipping configurations B and C.

**② Isolated hosting** (`MultiAppHost` + `MultiAppGateway`). Each installed app in an
`AppCatalog` runs in its **own** runtime — its own Camel context, datasource set, and HTTP
port — with a single-port gateway in front routing by `Host` header or an `/apps/<appId>/`
prefix. It carries an upgrader and canary weights. It has **no CLI or plugin entry point**,
no user documentation, and recorded defects that were deferred precisely because it has no
production callers.

> **Corrected 2026-08-16 — that last sentence stopped being true inside this document's own
> campaign, and was never updated.** Follow-up 4 below records `tesseraql host --install-root <dir>
> --mode <suite|isolated>` as **Done (#693)**, and `hosting.md` now documents both modes with a
> comparison table on the published site. ② has an entry point, user documentation and a reference
> page. It is still true that it has no *known* production callers, which is a weaker claim and the
> only one that should be relied on. It was relied on twice in `stack-architecture.md` before this
> correction: read that document's Decision 12 and slice 3 note, not this paragraph.

### What ① actually costs its users

The two mechanisms are not equivalent, and ① is weaker than its position in the
documentation suggests:

- **The URL space is flat.** A mounted app serves the paths it declares; there is no per-app
  prefix. `requireNoRouteConflicts` *detects* collisions at boot and refuses to start. Two
  independently authored apps that both declare `/orders` cannot be co-hosted, and the only
  remedy is editing one app's source. The system apps work only because they impose
  `/_tesseraql/...` on themselves by convention.
- **Studio is host-only.** `StudioService` receives the host manifest and holds no reference
  to a `MountedApp`. Mounted user apps are invisible to the explorer, the source editor, the
  scaffolder, and the migration author, and outside the reload path. The roadmap already
  carries "multi-app Studio scope" as an open question.
- **Traces are process-wide.** `RingTracer` is an in-memory ring, so today every mounted
  app's spans land in one buffer — an artefact of sharing a process, not a designed feature.

### What ② costs to run

Measured on 2026-08-10 by starting isolated runtimes of `examples/user-admin-app` (17 routes,
4 jobs, identity, SAML/SCIM, PDF) one at a time in one JVM against a shared PostgreSQL:

| | first app | each additional app |
| --- | --- | --- |
| Startup | 2,196 ms | ~600 ms |
| Heap | +14 MB | **+8 MB** |
| Metaspace | +34 MB | **+0 MB** |

The first app pays the framework's class loading and JIT warm-up for all of them; later apps
reuse it, which is why metaspace stops growing. **Per-app overhead is not a reason to reject
②.** The reasons to be careful about ② are elsewhere in this document. (Idle figures: pools,
threads and caches grow under load, and splitting ② across processes would reintroduce the
JVM baseline — 22 MB heap, 29 MB metaspace — per process.)

## Decisions

### 1. ① is for system apps only

`AppSources.discover` keeps the `ServiceLoader` providers and drops `configuredDirectories`:
`tesseraql.apps.<name>.package` / `.url` / `.path` stop mounting user apps. One host runtime
serves one user application plus the framework's own surfaces.

The two jobs ① performs today have opposite requirements, which is why one mechanism served
neither well. System apps **must** share the host's state — Studio edits the host's files,
the ops console reads its job repository, IAM Admin uses its identity service. User apps
mostly must not. Scoping ① to the first job lets it stop pretending to do the second.

`requireNoRouteConflicts` stays: a host app can still collide with a system app's paths.
`tesseraql.apps.<name>.enabled` stays — it disables individual system apps.

### 2. ② has two modes

> **Superseded.** Independent hosting was removed in #833 (`stack-architecture.md` Decision 12:
> every deployment shares one origin and one sign-in), and the deployment vocabulary is now
> **stack** (#836) — "suite" stays reserved for declarative test files. The table below records
> what existed when this decision was made.

The mode is a deployment choice, and it decides three things together — they cannot be mixed
independently, because sharing a session across apps requires a cookie that reaches them all.

| | **Shared suite** | **Independent hosting** |
| --- | --- | --- |
| Intended for | related apps of one organization | unrelated apps, or apps from different authors |
| Gateway routing | `/apps/<appId>/` path prefix (one origin) | `Host` header per app |
| Session cookie | shared — one sign-in across the suite | per host, not shared |
| `tesseraql.framework.datasource` | shared | per app |
| Business datasource | shared | per app |
| ops console | per app, scoped (decision 4) | per app |
| Traces | that app's runtime only | that app's runtime only |

Both modes give the isolation ① cannot: a separate runtime, a separate URL space, a Studio
per app, and per-app traces.

### 3. Data isolation is enabled, never guaranteed

The framework does not verify that co-hosted apps reach different data, and must not claim to.
**An application's configuration is not the authority on its database connection** — the app
declares the shape and the operator supplies the value per environment, through profiles and
secret references. Under ②, the operator installing the apps decides the connections.

Nor is there a checkable proxy for isolation: the same URL with different schemas is
isolated, different credentials against the same tables are not, and requiring distinct URLs
would break stack hosting outright.

So ② provides **runtime isolation, not a security boundary**. It separates Camel contexts,
URL spaces, Studio instances, traces and configuration. It does not separate data — that is
the operator's arrangement — and it cannot confine bytecode, because the JVM offers no
in-process mechanism to do so.

Two things follow. `MultiAppHost`'s javadoc claim that apps "share a process without sharing
route paths **or data**" is wrong and is corrected. And the admission profile's
declarative-only rule keeps its justification: while isolation is arranged rather than
enforced, "the framework is the sandbox" remains the only ground on which an app nobody
reviewed can be run, so a third-party app still may not carry bytecode.

### 4. The ops console is per-app, and scoped

> **Reversed by stack-architecture Decision 14, shipped as the stack shell
> (docs/stack-shells.md).** One console per stack now mounts at the origin scope with an
> application switcher — the caller's `tql.ops.view.<name>` atoms applied to the member
> list — and each application's pages delegate to its own runtime, so what this decision
> protected (a runtime's data shown by that runtime) survives the reversal. The scope
> clauses in the queries stay, exactly as written below.

Like Studio, the ops console shows **one application** — the one whose runtime serves it —
and its queries stay scoped to that app even when several apps share a business database.
`ops.app.<name>` becomes the permission to open an app's console rather than a filter
selecting which apps a shared console reveals; the scope clauses in the queries stay, because
a stack puts several apps' rows in one database.

This resolves more than it costs. Traces need no cross-runtime aggregation, because a
runtime's own in-memory spans are exactly what its console should show. "Which runtime's ops
do I open" stops being a question. And the console stops behaving differently depending on
whether the deployment happens to share a database.

The cost is real and accepted: an operator running a stack loses the single screen listing
every app's jobs. Cross-application monitoring belongs to the metrics exposition, which
already labels job runs by `job`, `app`, and `status`, rather than to N console tabs. A
future aggregate view on the gateway would build on per-app consoles, not replace them.

### 5. Route audit moves to the business datasource

`JdbcRouteAuditStore` is the one bucket-3 store on `tesseraql.framework.datasource` that is
not part of the authentication path, and it writes **once per audited request** — business
request rate. That key exists to keep a long-running business query from starving *login* of
a connection, so putting business-rate writes on the login pool defeats its purpose.

The bucket-3 test in `framework-datasource.md` — no transactional or integrity coupling — is
satisfied by audit, so the classification was not wrong by its own criteria; the criteria
did not capture the intent. That document is amended: bucket 3 is **ambient
authentication-path state**, and audit rejoins the business datasource with the other ops
stores.

This also removes an incoherence the ops console would otherwise carry: seven of its eight
pages read the business datasource (jobs, executions, outbox, events via bucket-1 stores) and
one read a different database.

### 6. ② does not inherit URL-fetch distribution

`HttpAppSource` — fetch a `.tqlapp` from a URL, pin its hash, cache under `work/downloads` —
goes with ①'s configured directories. ② keeps `AppCatalog`, `AppInstaller`, `AppUpgrader` and
canary weights, and expects packages to already be in its install root.

Getting bytes onto a host is a deployment concern with better tools than a runtime fetcher.
Baking the package into an image gives every node identical bytes with no runtime download,
which is what configuration C used the fetcher to approximate.

### 7. ② becomes usable before ① is reduced

The order is forced, not chosen: while ① still mounts user apps it is the *only* way to host
several of them, and `deployment.md` documents it. Reducing ① first would leave a gap with no
replacement.

## Slices

1. **Gateway hardening.** The recorded defects in `MultiAppGateway`: `close()` releasing
   neither its `HttpClient` nor its executor, unbounded request/response body buffering, and
   ingress header handling that does not behave like the trusted edge it forwards to.
   **Done** (#691).
2. **Route audit to the business datasource** (decision 5), amending
   `framework-datasource.md`'s bucket-3 criteria. **Done** (#690).
3. **Per-app ops console** (decision 4): the console shows its own app, `ops.app.<name>`
   becomes an open permission, scope clauses stay. **Done** (#692).
4. **An entry point for ②**: a CLI verb that starts `MultiAppHost` behind the gateway, with
   the mode (shared suite / independent hosting) declared in configuration. **Done** (#693):
   `tesseraql host --install-root <dir> --mode <suite|isolated>`.
5. **Studio under ②**: confirm a per-runtime Studio behaves correctly behind the gateway —
   path prefixes, redirects, CSRF, and asset URLs. **Done** (#701) — it needed the whole base
   path campaign first, and `StackModeIntegrationTest` (named `SuiteModeIntegrationTest` before the #836 vocabulary) now opens Studio through
   `/apps/<id>/`, checks its CSRF token and command palette, and requests every URL it emits.
6. **Reduce ①** (decision 1): drop `configuredDirectories` and `HttpAppSource`; simplify the
   `mountedApps` plumbing in `TesseraqlRuntime` and `RouteReloader`. **Done** — `AppSources`
   discovers the `ServiceLoader` providers and nothing else, and `HttpAppSource`,
   `ZipAppSource` and `DirectoryAppSource` are deleted with it. The `mountedApps` plumbing
   stays as it is: the five system apps still travel through it, and it is the same code path
   whether one app is mounted or five. `deployment.md` loses shipping configurations B and C,
   which is the point of doing this after slice 4 rather than before it.
7. **Documentation.** `deployment.md`'s shipping configurations, a user page for ② and its
   modes, the corrections outstanding in `extending.md` (a mounted `.tqlapp`'s `plugins/` is
   never read; admission is opt-in, so internal and vendor distribution may extend freely),
   and the admission profile's marketplace-named heading. **Done** — `hosting.md` is the user
   page, on the site under "Running in production"; `extending.md` says that `plugins/` is
   read from the one application a runtime serves, and that admission is a gate for
   distribution rather than a house style; the admission profile is no longer named after a
   marketplace that does not exist, in its page, its command and its findings.

## Out of scope

- **The curated marketplace** (roadmap Phase 37, unscheduled). This document decides hosting,
  not publishing. Capability permits belong there.
- **Per-app plugins.** Under ② each runtime reads its own `plugins/`, so an installed app's
  extensions would load — but `ExtensionContext` is runtime-wide with no per-app scoping, and
  decision 3 means isolation is not guaranteed anyway. Extensions stay a host decision.
- **Cross-application console aggregation.** Metrics answer it today; a gateway view is a
  later addition over per-app consoles.
- **Splitting ② across processes.** The measurements assume one JVM.

## Testing

- The gateway's hardening lands with tests for each recorded defect: a closed gateway
  releasing its client and executor, a body past the cap refused rather than buffered, and
  ingress headers not forwarded as if trusted.
- Stack hosting: two apps behind one gateway, one sign-in reaching both.
- Independent mode: two apps on different hosts, a session from one not authenticating
  against the other.
- Per-app ops: an app's console lists only its own jobs and executions when both apps share a
  business database.
- Audit placement: with `tesseraql.framework.datasource` set to a second datasource, audit
  rows land in the business database and session rows do not.
