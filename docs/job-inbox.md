# The job inbox: the transfer row records who started it, and "My exports" lists the signed-in user's exports of this application

> **Status: designed 2026-09-22, measured against main `6f5f68773` (0.19.0-SNAPSHOT). Three
> implementation slices; the user names each move.** This is the trigger
> [list-export.md](list-export.md) decision 11 named for a job inbox — "*the first gallery
> flow in which an export outlives the page that started it*; the snapshot pager's
> whole-document POST is where that will first be felt" — and its precondition, stated
> there and taken here as the starting point: `tql_file_transfer` records no subject, so
> nothing in the tree can say whose a transfer is. The column comes first; the page and the
> region that read it follow.
>
> **S1** — the column: `subject` on the transfer row, recorded at every start that has a
> principal, carried on the request records so the reviewed commit's frozen copy cannot drop
> it; the owner index; the two owner queries; the operations console's transfers page says
> who: **shipped, #1432** (every decision as recommended; a revert probe confirmed the new
> guard sees the frozen copy dropping the owner). **S2** — the page: a bundled `exports` app at `/_tesseraql/exports`, mounted with the
> application whose transfers it lists — a hosted member included — rendering the shipped job
> card per row over the caller's own exports; the account menu links it where an export can
> start. **S3** — the region remembers: a list page's job region is filled at render with the
> caller's exports of that route that still need them, and a kick-off adds a card instead of
> replacing one. Each slice ships its docs and its CHANGELOG entry.

## What was measured

Readings of the code, the records and the upstream contracts on main `6f5f68773`. Nothing here
was run; every mechanism this design composes is pinned by an integration test already (rows
4, 9, 11, 14), and the first slice's guards are where the composition meets a database.

| # | Reading | Result |
| --- | --- | --- |
| 1 | The transfer row (`operations/V1__framework_operations.sql:51-66`, `V13`, `V15`, `V16`; `JdbcFileTransferService.java:2109-2114`, `:2116-2170`, `:362-389`) | `tql_file_transfer` carries the route, the app, the direction, the format, the file, the counts, the `after:` declaration and its params, `downloaded_at`, `created_at`, `expected_rows` (V13), `tenant_id` (V15) and what the follow-up announces (V16). **No subject, no actor of any kind.** `insertTransfer` is a 14-argument private method, `TransferRow` a 17-component private record; `ensureSchema` lists V1, V12, V13, V15 and V16 by hand because Flyway covers only the four bundled vendors and H2 has nothing else. `VendorMigrationSetTest` pins name parity across `operations`, `operations-oracle` and `operations-sqlserver` (no MySQL variant of this component). |
| 2 | Where the framework already records a subject (`operations/V12__import_review_batch.sql:19-45`, `inbox/V1:7-8`, `:16-17`, `V3__job_execution_actor.sql`) | `tql_import_batch.subject varchar(256) not null` — "who parked it" — with the owner index `(app_name, subject)` and the V12 comment's reasoning about key length; `tql_user_notification.subject varchar(255)` with `(tenant_id, subject, created_at)`; `tql_job_execution.triggered_by varchar(256)` — "the principal's login id for manual runs, null for scheduled and system-initiated runs" ([ops-console-actions.md](ops-console-actions.md)). A transfer's execution row records null there: `startExport` and `launchImport` pass `null` as `triggeredBy` (`JdbcFileTransferService.java:452-453`, `:412-413`). |
| 3 | Where the subject is read today (`FileImportProcessor.java:233-238`, `ImportCommitProcessor.java:99-100`, `JdbcFileTransferService.java:1339`, `:1435-1445`, `:1489-1502`) | `FileImportProcessor.subject(exchange)` = the request principal's `subject`, or `""` when there is none; the review parks it on the batch and the commit refuses a batch whose subject is not the confirmer's (`BATCH_FOREIGN`, "the same answer shape as an unknown batch"). The commit then rebuilds the `ImportRequest` as a frozen copy — the copy that until #1405 dropped `emit`, `tenantId` and `pool` — and `launchImport` writes the transfer with nothing of the subject it just checked. |
| 4 | The start sites (`FileExportStartProcessor.java:103-118`, `FileImportProcessor.java:145`, `PollImportProcessor.java:74-76`, `JdbcFileTransferService.java:446-471`, `:410-426`, `:472`, `:2116-2121`) | Five: the route's export start (a principal on the exchange), the one-shot import (same), the reviewed commit (the subject in hand), the polled import (no principal — a consumer thread), and a job step's inline export (`exportInline`, the 9-argument `insertTransfer` overload; no principal). `ExportRequest` is an 18-component record with `announcing`/`invalidating`/`on` withers (`FileTransferService.java:125-211`), `ImportRequest` 11 components with the same three (`:37-92`). |
| 5 | Who may read a transfer (`TransferScope.java:38-52`, [edge-hygiene.md](edge-hygiene.md) decisions 1-4, [list-export.md](list-export.md) decision 7) | The `{transferId}` subtree answers for transfers whose `app_name`, `route_id` **and `tenant_id`** are the route's; "not by subject … a colleague under the same policy can fetch a link the exporter shared". Pinned by `TransferRouteScopeIntegrationTest`. E0 is a rule about *readers*; nothing in it says whose a transfer is. |
| 6 | The status and summary shapes (`FileTransferService.java:270-336`, `JdbcFileTransferService.java:730-808`, `:948-989`) | `TransferStatus` has 13 components (four delegating constructors) and **no timestamp**; `TransferSummary` has `createdAt` and serves one caller, the console's `recent(50)` (newest first, `left join tql_job_execution`). The retention sweep clears `spool_uri` and keeps the row; `JobReaperSweep` closes abandoned `RUNNING` transfers per application. Neither reads or writes anything an owner column would touch. |
| 7 | The per-subject page precedent (`tesseraql/apps/account/web/_tesseraql/tasks/get.yml`, `tasks.html`, `OpsAccountProviders.java:311-317`, `AccountViews.java:301-357`, `:625-632`; [workflow-surface.md](workflow-surface.md) decision 6) | `/_tesseraql/tasks` is a `query-html` route of the bundled `account` app whose `sources.main` is `service: account.tasks.view` with `subject: principal.subject` and `groups: principal.groups` — "the queue can only ever list the caller's work". The provider caps at 200 plus one hedge row, derives each row's link from the manifest (`workflowDetailPaths`, `:60-90`: route id to `urlPath()`), and answers the honest empty state when the store is absent. The app's `config/tesseraql.yml` declares `/** auth: browser, csrf: auto`; `.app-index` lists every file; the page carries its own nav block (three links; the inbox and account pages carry two). |
| 8 | The topology (`TesseraqlRuntime.java:1476-1479`, `SystemApps.java:62-70`; [stack-shells.md](stack-shells.md) structural decisions 2 and 3; [stack-architecture.md](stack-architecture.md) "the framework schema is migrated once"; [hosting.md](hosting.md) "The root, the portal, and the origin scope") | A hosted member mounts none of `ops-console`, `auth-ui`, `account`, `iam-admin`, `studio` — they are the stack's, served once at the origin — and "a single application is a stack of one": `dev` and `host` both stand the surface runtime up. The criterion is the state's scope: identity is stack-wide, so its surfaces moved; `operations` — the component `tql_file_transfer` lives in — "is per-application and stays there", on each application's main datasource. **A page in the account app would run at the origin and see no member's transfers.** The ops shell reaches a member's transfers by delegating over loopback with the caller's cookie, one member at a time (`OpsShellProviders.java:210-222`, `:277-278`). |
| 9 | What a service provider is given (`ServiceStep.java:26-46`, `RequestBinder.java:165-176`, `BasePath.java:33-87`, `job-card.html:23-31`) | A `service:` source hands the provider the route-resolved params and nothing else — no exchange. The expression context carries `principal`, `tenant` (`tenant.id` binds), `flags`, `preference` and `request` — **which holds `locale` alone**. The card fragment emits its `hx-get`, cancel and download URLs verbatim, by design ("wire URLs by construction … a link expression around them prefixed a second time, and the button was a 404 on every stack deployment"), so a provider building cards must know the base path; `BasePath.url` needs the exchange it does not have. |
| 10 | The card helpers and the transfer page (`JobCards.java:24`, `:40-79`, `ImportPages.java:22`, `:48-79`, `job-page.html`, `HtmlResponseRenderer.java:281`) | `JobCards.of(status, statusUrl, cancelUrl, locate, catalog, locale)` and `ImportPages` are **package-private** in `io.tesseraql.compiler.binding`. The transfer page is rendered by `Templates.render` with `c`, `_csrf` and the base-path variable — **without `ShellChrome`**, so it shows the shell with no menu and no account chip; a YAML route's page gets both from `HtmlResponseRenderer`. |
| 11 | The render-time judgement of a route (`ViewBinding.java:1613-1633`, `HtmlResponseRenderer.java:217-224`) | `visibleExports` drops an `exports:` entry whose `security.policy` the `PolicyEngine` refuses for the request principal, or whose `auth:` needs a principal the page has none of; the route re-authorizes the click. The rule a per-row link could reuse. |
| 12 | The console's transfers page (`OpsViews.java:142-170`, `…/{member}/transfers/transfers.html:19-48`, `OperationsRoutes.java:494-497`, `:814-842`, `OpsConsoleIntegrationTest.java:235-241`) | Columns: transfer, route, app, direction, format, status, rows, file, downloaded, started; a completed export links `/_tesseraql/ops/console/transfers/{id}/file` under the console's own grant; the `ops.data.transfers` JSON is the same provider's rows, so the shell's delegated page shows whatever the rows carry. Nothing says who started a transfer. |
| 13 | The shell's account chrome (`ShellChrome.java:114-146`, `:236-246`, `:380-382`, `shell.html:107-170`, `TesseraqlProperties.java:212`, `:228`) | `_account` renders when the request rides a session; `accountHref` is origin-absolute on a hosted member (`STACK_MEMBER_BEAN` bound) and `BasePath.url(exchange, "/_tesseraql/account")` when `ACCOUNT_SURFACE_BEAN` is bound; the inbox bell follows the same rule. The popover holds "Account settings" and "Sign out" — **two literal strings, not catalog keys**. |
| 14 | The grid page's job region (`list.html:73-88`, [list-export.md](list-export.md) decision 5 and "Filed, not fixed") | `#<id>-export-job` is empty until an htmx kick-off swaps the running card in with `hx-swap="innerHTML"`; "a second kick-off replaces the card"; a snapshot list's pager is a whole-document POST that "loses the card (not the run); recorded, and the reason a job inbox is filed". Pinned by `ListExportIntegrationTest` (helpdesk tickets, PostgreSQL). |
| 15 | The upstream shapes (`recipes/async-job/contract.md`, `recipes/data-region/contract.md`, hc 0.4.2) | "**A job inbox (list of my recent jobs) is this recipe applied per row + data-region for the list itself.**" "Retry creates a new job, the failed one stays queryable." "Expired / unknown id — a tombstone card, HTTP 200: staleness is a state, not an error." A data-region is `<section id class="hc-data-region" hx-get hx-swap="outerHTML" hx-trigger="load, <event> from:body">`, answered whole, with its empty state inside, refreshed by an `HX-Trigger` header or a body event. |
| 16 | Free codes, catalogs, guards | `TQL-LD-2801…2868` are taken in source, `2869` free; `TQL-ACCOUNT-4802/4804/4806/4807/5807` taken. The catalogs are the compiler's `tesseraql/messages/{en,ja}.yml` (`tql.job.*`, `tql.tasks.*`, `tql.ops.*`); the page path `/_tesseraql/exports` and the stem `tql.exports` appear nowhere. `FrameworkSurfaceGuardTest` requires an `authenticate` step or a registry entry on every framework HTTP route; a bundled app's `auth: browser` default supplies the step. `AppSourceProvider` is a `ServiceLoader` SPI (`META-INF/services`, `AuthUiAppProvider` + `AccountAppProvider` in the runtime); `AppSources.discover` honours `tesseraql.apps.<name>.enabled`. |
| 17 | Sessions in a test (`WorkflowSurfaceIntegrationTest.java:52-63`, `:212-222`) | `sessions.create(new Principal("actor-1", …), ClientInfo.NONE)` mints a cookie for a named subject; the tasks page is then asserted per subject — the actor sees "case M-3", the approver does not. The shape S2's guard takes. |

## The mechanism

A transfer learns whose it is at the one moment that is knowable — the request that starts it —
and keeps it as a column beside the tenant it already keeps. Two readers use the column: a page
of the application that ran the transfers, listing the signed-in user's own exports as the
shipped job card per row, each card polling the route's own status subtree; and the grid page
that started an export, which fills its job region at render with the same user's exports of
that route that still need them. Nothing about who may *read* a transfer changes: the column
is an owner for listing, and every link the page renders goes through the route's subtree,
which answers as it always has.

```
POST /tickets/export?q=vpn                          tql_file_transfer
  principal.subject = "u-42"  ──────────────▶   … tenant_id | subject  | created_at
                                                  …  null     | u-42     | 10:02
GET /_tesseraql/exports                            …  null     | u-42     | 09:40
  service: exports.mine                            …  null     | u-7      | 09:12   (not mine)
    subject: principal.subject  ───▶ mine(app, "u-42", tenant, 50)
    tenant:  tenant.id                     │
    base:    request.basePath              ▼
                                   ┌ tickets.csv · started 10:02 ─────────────────────┐
                                   │ [Running] 12,300 rows                   [Cancel] │ ← hx-get /tickets/export/{id}
                                   └───────────────────────────────────────────────────┘
                                   ┌ tickets.csv · started 09:40 ─────────────────────┐
                                   │ [Done]                              [Download]   │ ← /tickets/export/{id}/file
                                   └───────────────────────────────────────────────────┘
```

## The decisions

Each is recommended unless it says otherwise; the alternatives are named where one was close.

### 1 — The transfer row records who started it: `subject`, nullable, the principal's stable subject

**Recommended.** `V17__transfer_subject.sql` in the three vendor directories adds
`subject varchar(256)` (Oracle `varchar2(256)`, SQL Server behind the `col_length` guard V15
and V16 use), listed in `ensureSchema` beside V16 so H2 has it, and the owner index
`idx_tql_file_transfer_owner (app_name, subject, created_at)` — the inbox reads by owner,
newest first, and V12's key-length arithmetic holds: two `varchar(256)` and a timestamp stay
under InnoDB's ceiling that three would meet exactly. The type is the batch's, because the
value is the batch's: `Principal.subject`, the stable identifier the reviewed commit already
compares — the JWT `sub`, `tql_users.user_id` for a session, the client's declared subject or
id for an API key or an mTLS client. Not the login id: `triggered_by` on the execution row is
a display value for the console's manual-run line, and a name that can change is not a key to
list by.

**Null, never the empty string.** `FileImportProcessor.subject` answers `""` for a request with
no principal because the batch uses the value as an equality key; the transfer row uses it as
an owner, and a public route's export, a polled import and a job step's inline export belong to
nobody — they appear in no inbox and the console shows a dash. The three sites with a principal
record it: the export start, the one-shot import, and the reviewed commit, where the subject is
already in hand for the foreign-batch check.

**Carried on the request, not fetched from the exchange inside the service.** `ExportRequest`
and `ImportRequest` gain a `subject` component and a `by(subject)` wither beside `announcing`,
`invalidating` and `on`; `startExport`, `launchImport` and the 14-argument `insertTransfer`
write it; `TransferRow` reads it. The reviewed commit's frozen `ImportRequest` copies it like
the topics and the pool — the copy that once dropped all three (#1405) is the place a new
component is forgotten, and the S1 guard confirms a reviewed import through a second subject's
session is refused before it is ever recorded. The `TransferStatus` face does **not** carry
the subject: whose a transfer is stays off the wire the route's readers see (decision 2); the
console's `TransferSummary` gains it (decision 7).

*Weighed and declined:* recording `triggered_by` on the transfer's execution row instead. The
execution row is the batch platform's, `triggered_by` is its login-id display field, and a
transfer's owner would then live one join away from the transfer under a different identity
than the batch uses. One fact, one column, the identity the framework already keys on.

### 2 — E0 stands: the owner narrows nothing on the subtree

**Recommended.** `TransferScope.own` keeps comparing app, route and tenant; the status, file and
cancel processors are untouched. The column answers "whose is it" for a page that lists; it
does not answer "who may read it", which [edge-hygiene.md](edge-hygiene.md) decided the other
way for a reason this design keeps — a shared link works for a colleague under the same
policy. The inbox therefore shows the caller only their own rows, and every control it renders
is the route's own URL: the poll answers the tombstone for a transfer the route no longer
admits, the download answers the route's refusal, and the cancel checks the scope before
asking the run, exactly as from the grid page. Under tenancy the owner query carries the
request's tenant with the same rule `TransferScope.own` applies (`Objects.equals` on
`tenant_id`, so a pre-0.18.0 row with no tenant is nobody's under tenancy — recorded there,
unchanged here).

### 3 — Two owner queries on the service, and a timestamp on the status

**Recommended.** `FileTransferService` gains

- `mine(appName, subject, tenantId, limit)` — the subject's transfers of this application,
  newest first, as `TransferStatus` rows with the execution's status joined the way `recent`
  joins it; both directions, so the console-side reader and the page can each choose;
- `pending(appName, routeId, subject, tenantId, limit)` — the subject's **exports** of one route
  that still need them: `RUNNING`, or `COMPLETED` with `downloaded_at` null and `spool_uri`
  present. What is running wants watching or cancelling; a finished file nobody has fetched
  wants fetching; everything else has been dealt with and lives on the page.

`TransferStatus` gains `createdAt` as its 14th component (the delegating constructors kept, the
old ones passing null), because a row on a page needs to say when, and the status JSON face
carries it as `createdAt`, ISO-8601 like every timestamp on that wire — additive. `mine`
answers `limit + 1` so the page can hedge its count the way the tasks page does; the page's cap
is 50, the console's own number, because an inbox lists the recent, not the archive.

The subject of both queries is the caller's; the service does not check that, the routes do
(decision 4's `principal.subject` mapping is the only way the page can ask), the same trust the
tasks and inbox providers extend to their routes.

### 4 — The page is the application's: a bundled `exports` app at `/_tesseraql/exports`, mounted where the routes are

**Recommended.** A third bundled app in the runtime jar, beside `auth-ui` and `account`:
`tesseraql/apps/exports/` with `config/tesseraql.yml` (the account app's defaults, `/** auth:
browser, csrf: auto`, the same header block), `.app-index`, and one route,
`web/_tesseraql/exports/get.yml`:

```yaml
version: tesseraql/v1
id: tql.exports.home
kind: route
recipe: query-html
sources:
  main:
    service:
      name: exports.mine
      params:
        subject: principal.subject      # the caller's, always
        tenant: tenant.id               # the resolved tenant, the subtree's own rule
        base: request.basePath          # decision 5: the cards carry wire URLs
        locale: request.locale
response:
  html:
    status: 200
    template: exports.html
    model: { exports: main }
    headers: { Cache-Control: "no-store" }
```

`ExportsAppProvider` registers it through the `AppSourceProvider` SPI, enabled exactly when
the account surface is (console login on, `tesseraql.apps.exports.enabled` the kill switch),
and it is **not** in the hosted member's skip set — that is the point of a separate app. The
skip set holds the surfaces whose state is the stack's; a transfer's state is the
application's, in the `operations` component on its own main datasource, beside the routes
whose subtree serves the file. The page lists the application it is mounted with, under the
member's prefix, and the account menu on a member page links to that member's own page.

**Why not the account app.** It is skipped on every hosted member and served at the origin,
whose runtime holds no member's transfer table: the page would answer "nothing here" in every
deployment `host` produces, and a stack of one is such a deployment. (The account app's tasks
page reads this runtime's `main` for the workflow task store, which under a stack is the
origin's — the same limitation, recorded here, not fixed here.)

**Why not a Java-mounted route.** The runtime's Java pages render the shell without its menu
and account chip (row 10, the transfer page's own gap); a YAML route gets the chrome, the
catalog, the CSRF wiring and the L2 override for free, and the guard's `authenticate` step by
declaration. The provider is Java either way.

**Why not the origin, delegating.** The ops shell's shape — one member at a time, over
loopback with the caller's cookie, a switcher — is an operator's, and "everything I exported
anywhere in the stack" is a different page from "my exports of this application". *Trigger:
the first stack whose users export from several members and ask for one list.*

**Which application.** The page lists `app_name = this runtime's application`. The bundled apps
mounted beside it declare no file routes, and a mounted business app on one runtime is not a
shape the framework runs ([app-isolation-and-base-path](app-isolation-model.md): one runtime,
one application, plus the system apps).

### 5 — Each row is the shipped card; the provider builds it from the manifest, with the base path as a request expression

**Recommended.** The provider `exports.mine` (registered beside the account providers) calls
`mine` for the subject and the tenant, keeps the `EXPORT` rows, and for each resolves the route
by id over `manifest.routes()` — the `workflowDetailPaths` shape, route id to `urlPath()` — and
builds the card with `JobCards.of(status, base + path + "/" + id, base + path + "/" + id +
"/cancel", locate, catalog, locale)`: the same map the status poll answers with two seconds
later, one markup source. `JobCards` (and the `tombstone`) become public — the smallest change
that lets a runtime provider render the card the compiler owns. Each row renders the card
under a heading line the card does not carry: the file name, the route's label (its id), and
"started" with the row's `createdAt`. A running card polls the route's subtree on the
server's cadence and stops when the run does; a done card's Download is the route's file leg;
a reclaimed export is the expired card (list-export decision 5); a failed card names the code's
sentence. **A row whose route the manifest no longer declares** renders its heading and a
static badge with no poll, no download, no cancel: the transfer happened, the route that
produced it is gone, and a URL into a subtree that does not exist would be a lie.

**`request.basePath`.** Row 9: a card's URLs are wire URLs, the provider has no exchange, and
the request map holds `locale` alone. `RequestBinder` adds `basePath` beside it — the prefix
`BasePath.url` would apply on this request (the base path and the activation segment) —
resolvable in any expression a route binds, documented with `request.locale`. A general
primitive, not a hook for this page: an author composing an absolute wire URL into a
notification body or a service param has had no way to say it.

**The list is not a `data-region`.** The upstream note pairs the per-row recipe with a
`data-region` "for the list itself", so an event can refresh the list. Nothing on this page
fires one — a kick-off happens on a grid page, and a card that finishes is already the card
that finished — so the list renders once, as the page, and a browser refresh is the refresh.
Recorded, not adopted; *trigger: a kick-off on this page* (decision 8's retry).

**Without JavaScript** the page is a list of cards; a card's Cancel is a form that posts
natively; states arrive by refreshing. The tasks page's shape.

### 6 — The account menu links the page where an export can start

**Recommended.** `ShellChrome.account()` adds a "My exports" item to the popover between
"Account settings" and "Sign out", `BasePath.url(exchange, "/_tesseraql/exports")` — never
origin-absolute, because the page is this runtime's — when `EXPORTS_SURFACE_BEAN` is bound.
The runtime binds the marker when the app mounted **and** the application declares at least
one `file-export` route: the link appears where an export can start, and a runtime with no
export routes (the origin's own, an application of forms) shows no menu entry for a page that
would say nothing. The page stays reachable by URL either way and answers its empty state.
The item's label is a catalog key (`tql.exports.menu`); the two literals beside it are a
measured quirk, filed below.

The page's own nav block carries one item, itself; the account, inbox and tasks pages are
another app's under a stack, and the shell's chip already reaches them by the right rule.

### 7 — The console shows who

**Recommended.** `TransferSummary` gains `subject`; `OpsViews.transfers` publishes it as `by`
(a dash when null), the transfers template gains the column between *App* and *Direction*,
and the `ops.data.transfers` JSON — the same rows — carries it, so the shell's delegated page
shows it without a change of its own; the header is a new `tql.ops.by` key (en/ja). An operator
reading a stuck export can now see whose it is; the execution row's `triggered_by` stays null
for transfers, as decision 1 says.

### 8 — The grid page's job region remembers, and a kick-off adds a card

**Recommended.** At render, `HtmlResponseRenderer` — which holds the exchange, the principal
and the transfer service — asks `pending(app, route, subject, tenant, 5)` for each
`file-export` target of the view and hands `ViewBinding.listModel` the cards, which the region
renders newest first; a kick-off button's `hx-swap` becomes `afterbegin`, so a second export
joins the first instead of replacing it, and both keep polling. The region is outside the
swapped table region already (list-export decision 5), so the in-place pager, sort and search
leave it standing; the snapshot pager's whole-document POST rebuilds the page — and the region
with it, from the store. Both entries list-export filed under "a second kick-off hides the
first card" and "turning a snapshot page loses the card" close here, on the page where the
trigger said they would first be felt, without a visit to the inbox.

**What the region shows is what still needs the caller**: running, or done and not yet
downloaded, of this route, at most five. A downloaded file, a failure, a cancel leave the
region on the next render and stay on the page. A public list page (no principal) renders the
region empty, as today. The region's cards are the same `JobCards.of` maps the kick-off answers
with, so the markup a test asserts is one markup.

*Weighed and declined:* keeping `innerHTML` and showing only the newest. It is the upstream's
single-job shape, and it is exactly the shape that hid the first card.

### 9 — What this design refuses, each with its trigger

- **Imports on the page.** The column records them (decision 1); the page lists exports, as
  its name says, because the thing a user comes back for is a file. An import's card is the
  import page's, and its report is on the transfer page by URL. *Trigger: the first import
  whose report someone needed after leaving the page that confirmed it.*
- **Retry from the inbox.** Upstream: "retry creates a new job". A transfer row carries the
  bound params, not the URL that carried them, and the kick-off is on the grid page one
  click away with the current question. *Trigger: the first request for it.*
- **A stack-wide inbox at the origin.** Decision 4's third alternative.
- **A completion notification** (mail, push, the inbox bell). The transfer machinery announces
  what an `after:` statement commits; a file is not an event. *Trigger: the first export whose
  owner is not at a screen when it finishes* — a scheduled job's export is that already, and
  it has `push:`.
- **Clearing or deleting rows from the page.** Retention is the application's policy
  (`tesseraql.transfers.retentionDays`); a user-facing delete would need the row to be
  theirs to delete, which decision 2 declines.
- **A filter by owner on the console.** The column renders; the console's tables carry no
  filters yet beyond the audit page's.

### 10 — Docs, ledger, CHANGELOG

**Recommended.** [file-transfers.md](file-transfers.md): the status shape gains `createdAt`;
a "My exports" section after "The page" — the bundled page, what it lists, the link, the
region's memory; the "Security" paragraph gains one sentence (the owner is recorded, the
readers are the route's). [hypermedia-ui.md](hypermedia-ui.md) "Exporting a list": the region's
memory and the `afterbegin` swap, and one sentence pointing at the page.
[declarative-views.md](declarative-views.md) "Exporting the filtered set": one sentence.
[ops-console.md](ops-console.md) "Transfers": "who started it".
[multi-tenancy.md](multi-tenancy.md): the owner query carries the tenant.
[account.md](account.md) "The shell account region": the menu item.
[hc-recipe-alignment.md](hc-recipe-alignment.md): the async-job section gains a "Designed:"
block naming this record, and the status block a bullet. [list-export.md](list-export.md):
decision 11's inbox bullet and the two filed entries point here. `CHANGELOG.md` per slice,
under Unreleased: S1 `### Added` (the row records who started it; the console shows it;
`createdAt` on the status) and `### Changed` (`ExportRequest`/`ImportRequest` carry the
subject); S2 `### Added` (the page, the menu item, `request.basePath`); S3 `### Added` (the
region remembers) and `### Changed` (markup contract: the kick-off adds a card).

## What this breaks

- **Positional constructors.** `TransferStatus` grows a 14th component and `TransferSummary` a
  12th; `ExportRequest` and `ImportRequest` grow one each. The delegating constructors keep
  every existing call site compiling; a test constructing the full shape positionally is
  patched (the `TestCase` precedent). `insertTransfer`'s 14 arguments become 15; the
  9-argument overload passes null.
- **The status JSON gains `createdAt`.** Additive; documented in the status list.
- **The console JSON gains `by`.** Additive.
- **A fourth bundled app mounts by default** wherever console login is on. Its one route is
  session-authenticated by the app's defaults, so the framework-surface guard sees a gated
  route; `SystemApps.requireNoRouteConflicts` refuses a main app that ever declared
  `/_tesseraql/exports`, a path the framework's `/_tesseraql/` fence reserves anyway. The kill
  switch is the account app's shape.
- **The kick-off's swap changes** (S3): `hx-swap="innerHTML"` becomes `afterbegin` on the
  export button — a markup-contract change on `tql/view/list.html`, and a level-2 override
  that copied the old attribute keeps replacing the card until it adopts the new one; said
  in the CHANGELOG as list-export said its own.
- **`request.basePath` is a new reserved expression.** An application binding a source param
  literally named `request` shadowed nothing before either: the request map has been
  reserved since `request.locale`.

## Filed, not fixed

- **The account popover's "Account settings" and "Sign out" are literal English** (row 13),
  not catalog keys, on a shell every page of every application renders. The new item is a
  key; the two beside it are one small slice of their own, not this design's.
- **The account app's tasks page reads this runtime's `main`** (row 8, decision 4): under a
  stack that is the origin's, and no member's tasks are listed. Recorded so the same
  limitation is not reported as this page's.
- **The transfer page renders the shell without its menu and account chip** (row 10): a
  Java-rendered page that `Templates.render` builds with three variables. It is the no-JS
  landing for a kick-off and the target of a bookmarked status URL, and it works; it just
  looks like nowhere. A `ShellChrome` pass over its model is a short slice when someone
  minds.
- **A transfer recorded before S1 has no owner** and appears in no inbox; the console shows
  a dash. Recorded, not shimmed (the pre-0.18.0 tenant precedent).

## Deliberately not in this design

- Any change to the card's states, cadence or markup: the card is the csv-import campaign's
  and the contract's; this design renders it in two more places.
- Any change to the retention sweep, the reaper, the temp store or the console's grant.
- A "my imports" page, a retry, a notification (decision 9).
- The Studio: it declares no file routes and is not a hosted surface.

## The slices

### S1 — the column, the owner, the console (M)

`V17__transfer_subject.sql` ×3 (+ `ensureSchema`, `VendorMigrationSetTest` unchanged and
green); `subject` + `by(subject)` on `ExportRequest` and `ImportRequest`; the three start sites
and the frozen commit copy; `TransferRow`, `insertTransfer`, `TransferStatus.createdAt`,
`TransferSummary.subject`; `mine` and `pending` on the service; the status JSON's `createdAt`;
`OpsViews.transfers` `by` + the template column; docs (file-transfers.md status list and
Security sentence, ops-console.md, multi-tenancy.md), CHANGELOG.

| Guard | Variant that must fail it |
| --- | --- |
| `TransferOwnerIntegrationTest` (new, PostgreSQL, the scope test's app shape plus sessions): A starts an export through a browser session, B starts one, an anonymous caller starts one through a public route → `mine(app, A)` holds A's only, newest first; `pending(app, route, A)` holds the running one and drops it once fetched; the anonymous row has a null owner | the export start not calling `by(...)` (A's list empty); `pending` ignoring `downloaded_at` (the fetched file stays); `""` recorded for the anonymous caller (a row that belongs to `""`) |
| the same test's reviewed leg: A parks a review, A confirms it → the committed transfer's owner is A | the frozen `ImportRequest` copy dropping the subject (the #1405 shape) — the owner null on a confirmed import |
| `TransferRouteScopeIntegrationTest`, unchanged | any narrowing of `TransferScope.own` by subject would fail its shared-link row |
| `OpsConsoleIntegrationTest` +1: a transfer started under a session shows its subject in the *By* column and in `ops.data.transfers` | the summary not reading the column (a dash for an owned row) |
| `FileTransferIntegrationTest` status assertion +`createdAt` | the status face not carrying the timestamp |
| ensureSchema on H2 (any existing H2-backed transfer test) | V17 missing from the list — the insert fails on the unknown column |

### S2 — the page (M)

`ExportsAppProvider` + `META-INF/services` entry; `tesseraql/apps/exports/` (config,
`.app-index`, `get.yml`, `exports.html` with its one-item nav and the row heading around the
card fragment); `exports.mine` provider (manifest lookup, `JobCards` public, the gone-route
row); `request.basePath` in `RequestBinder` + its doc line; `EXPORTS_SURFACE_BEAN` bound when
the app mounts and a `file-export` route is declared; the popover item + `tql.exports.*` keys
in `en.yml`/`ja.yml`; docs (file-transfers.md "My exports", account.md, hypermedia-ui.md one
sentence, the ledger's "Designed:" block and status bullet, list-export.md pointers),
CHANGELOG.

| Guard | Variant that must fail it |
| --- | --- |
| `ExportsPageIntegrationTest` (new; the helpdesk tickets app of `ListExportIntegrationTest`, sessions as in `WorkflowSurfaceIntegrationTest`): A kicks the async export off from the grid page (htmx, 202) → `GET /_tesseraql/exports` as A holds one `data-hc-job` card whose `hx-get` is `/tickets/export-async/{id}` and whose Cancel posts to `…/cancel`; the terminal card carries no trigger and a Download to `…/{id}/file`; B's page holds no card and the empty state; the page under a base path renders prefixed URLs (`request.basePath`) | the provider building app-relative URLs (a 404 under the base path); the subject mapping missing from `get.yml` (B sees A's card); `JobCards` not reused (a card without `data-hc-job` or with a hand-rolled poll) |
| the same test: a row whose route id is not in the manifest (seeded directly on the row) renders its heading and no `hx-get` | the provider throwing on the unknown route, or linking a subtree that does not exist |
| `AccountSurfaceIntegrationTest` (it asserts the popover today) +1: the popover holds "My exports" with the runtime's own base path when the marker is bound, nothing when it is not | the item rendered origin-absolute, or without the marker |
| `FrameworkSurfaceGuardTest`, unchanged | the route mounting without the app's `auth: browser` default |
| `StackModeIntegrationTest` (the hosted-member boot) +1: `/<member>/_tesseraql/exports` answers the page under the member's prefix, while the account surface stays the origin's | the app added to the skip set by reflex |
| `docs-reference` regen: `tesseraql.apps.exports.enabled` appears under `tesseraql.apps` | the key read without the `getString` literal the reference scans for |

### S3 — the region remembers (S)

`HtmlResponseRenderer` supplies `pending` cards per `file-export` target; `ViewBinding.listModel`
places them (`exportJobs` in the model); `list.html` renders them in the region and the
kick-off button swaps `afterbegin`; docs (hypermedia-ui.md, declarative-views.md one sentence),
CHANGELOG (markup contract).

| Guard | Variant that must fail it |
| --- | --- |
| `ListExportIntegrationTest` +2: A kicks off, reloads the grid page → the region holds the running card; a second kick-off answers a card and the page then holds two; B's grid page holds none; after the file is fetched, A's page holds none | `innerHTML` kept (one card after two kick-offs); the region filled with every state (a downloaded export still shown); the region filled for any subject |
| the renderer's unit test for the export model: a public page (no principal) gets an empty region | the renderer querying with a null subject and listing nobody's transfers |

## Docs and CHANGELOG

`CHANGELOG.md`, Unreleased:

- **S1 `### Added`** — **A transfer records who started it.** `tql_file_transfer` gains `subject`
  (V17): the requesting principal's stable subject on a route's export start, its one-shot
  import and its reviewed commit; null for a polled import, a job step's export and a public
  route's caller. The operations console's transfers page and JSON show it as *By*; the
  transfer status carries `createdAt`. Two owner queries on `FileTransferService`, `mine` and
  `pending`, for the surfaces that follow. Who may read a transfer is unchanged.
- **S1 `### Changed`** — `ExportRequest` and `ImportRequest` carry the subject (`by`), so the
  reviewed commit's frozen copy records the confirmer it already checked.
- **S2 `### Added`** — **My exports.** A bundled page at `/_tesseraql/exports` lists the
  signed-in user's exports of this application as the job card per row — running cards poll
  the route's own status, done cards download from its file leg, reclaimed ones say expired —
  mounted with the application, a hosted member included, and linked from the account menu
  where a `file-export` route is declared. `request.basePath` resolves in route expressions.
- **S3 `### Added`** — **The grid page's job region remembers.** A list page renders the
  caller's running and not-yet-downloaded exports of that route in its job region at render,
  so a snapshot page turn or a return to the page finds the card; a kick-off adds a card
  above the others instead of replacing them.
- **S3 `### Changed`** — **Markup contract.** The export kick-off button on `tql/view/list.html`
  swaps `afterbegin` into the job region; a level-2 override keeps replacing the card until it
  adopts the attribute.

## Error codes

No new code. `mine` and `pending` report a failed read as `TQL-LD-2810`, the transfer
service's own ("Failed to list file transfers"), which the page's route surfaces like every
provider failure; the page's empty state is not an error.
