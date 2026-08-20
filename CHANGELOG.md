# Changelog

All notable changes to TesseraQL are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[Semantic Versioning](https://semver.org/).

## Unreleased

### Added

- **Context conditions, in two layers** (docs/access-governance.md structural decision 8,
  slice 8). `tesseraql.security.network.allow` is a CIDR list naming the networks a session may
  be established from; a sign-in from anywhere else is refused with `TQL-SEC-4149` before a
  session exists, however it was being established — password, OIDC or SAML. The check runs
  after the credential is proven, so a refusal says nothing about whether the password was
  right, and an unset list admits everybody as before. Per-role conditions are the second layer:
  a role may carry `network` (a CIDR block) or `hours` (`MON-FRI 09:00-18:00`) conditions,
  declared on the new conditions page, and a grant this request's context does not admit is
  dropped from the active view — its role and its permissions leave, it is absent from the role
  picker, and its `/_as/` address is refused like any unheld role. Within one kind any condition
  admits, across kinds every kind must, and a condition the runtime cannot read never admits.
  Nothing is revoked: the same person from the office at 10:00 holds the role in full. This
  narrows and never widens, so a spoofed address can only cost capability — the enforceable
  answer to where somebody may sign in is layer A, and the documentation says so rather than
  implying otherwise. Hours are read in `tesseraql.security.conditions.zone`, defaulting to the
  JVM's zone.

- **Delegated administration, one application at a time** (docs/access-governance.md structural
  decision 7, slice 7). `tql.iam.view.<name>` and `tql.iam.write.<name>` hand one application's
  own access to somebody who administers nothing else — seeing and writing as two grants, the
  `tql.ops.view` / `tql.ops.run` pair applied to identity. The per-application page becomes
  their console: this application's roles, assignment and revocation by login, and permission
  codes carrying its name. Containment is enforced in the write, not the page — a stack-wide
  role is never one application's, a code must carry the application's name, and nothing under
  the `tql.` mark is ever a delegated administrator's to hand out (`TQL-IAM-4036`). The
  store-wide administrator writing through one of these pages is confined to the application it
  addresses, so a page cannot be used to reach another's role.

- **A route policy can resolve its atom from the route's own path.** `policy:
  tql.iam.write.{path.name}` checks the atom the address names, which is what lets a
  per-application grant be the thing a route gates on at all; before this a route's policy was
  one fixed id and a delegated administrator was refused before any containment ran. Only
  `{path.<name>}` is interpolable and only under the `tql.` mark — a gate resolves from the
  addressed resource, never from a query string or a body — and the value is read off the
  request's URL rather than off the router's path-parameter headers, because a form field named
  after the path parameter overwrites one of those. An unresolvable template is refused at lint
  and at boot as `TQL-YAML-1409`. A per-application atom is also satisfied by the store-wide
  grant it narrows, recorded once in `Atoms` as data, so a route naming the narrower atom
  admits the store-wide administrator without restating the pair.

- **Self-service access requests, approved by the role's owner** (docs/access-governance.md
  structural decision 6, slice 6). A role becomes requestable by having an **owner** — a person
  or a group; a role with no owner cannot be asked for at all, which is the deny-by-default
  answer to "who approves this" rather than falling back to whoever administers the store.
  People ask from their account page; owners decide from a queue filtered against their own
  principal, so it cannot offer a decision they are not entitled to make. Approving lands the
  grant through the same write an administrator uses — the separation-of-duties check, the
  window, the trail row naming the request — and time-boxes it when a duration was asked for.
  A decided request is final: the decision is recorded before the grant and keyed on `pending`,
  so two approvers acting at once produce one decision, not two grants.

  **Deliberately not a workflow document.** The deferral proposed composing the approval-workflow
  engine with the identity write contracts. A transition's command is app-authored 2-way SQL over
  an app-owned table and the engine has no seam for calling a framework service, so landing a
  grant from one would mean writing `tql_user_roles` directly — bypassing the very checks this
  campaign adds. Requests are framework store rows with a framework write path, and the engine
  stays what it is: how an *application* moves *its* documents.

- **Access review campaigns, decided against a snapshot** (docs/access-governance.md structural
  decision 5, slice 5). Opening a review snapshots who holds what — every role held by any path,
  every direct permission grant — into items a reviewer decides one at a time. The snapshot is
  what makes the campaign answerable later: reading live grants would ask reviewers about a
  moving target and could never say what was certified in a given quarter. Each item shows how
  the grant arrived (an assignment, a rule, a group), because a role nobody individually gave is
  a different question. **Close and apply** executes every `revoke` through the same write an
  administrator uses, so each revocation is validated identically, lands in the grant trail, and
  names the campaign that decided it; an item whose grant had already gone is recorded `stale`
  rather than re-revoked. A closed campaign takes no further decisions — its items are the
  record. The grant history page gains a **Cause** column showing that correlation.

- **Groups are writable, and membership carries a validity window**
  (docs/access-governance.md structural decision 4, slice 4). `tql_groups`,
  `tql_user_groups` and `tql_group_roles` were read by three contracts at sign-in and written
  by none: the identity pack had no create-group, no membership write and no group-role write,
  so a deployment could only populate them by hand. Eleven contracts now cover the gap, and IAM
  Admin gains a groups page and a per-group page editing members and the roles the group
  delivers. `tql_user_groups` gains `source` and `starts_at`/`ends_at`, filtered at resolution
  by the same predicate its sibling assignment tables already use — the deferral said group
  membership windows would be decided here, and they are the same window as everywhere else.
  Joins and leaves are recorded in the grant trail; deleting a group empties its memberships
  first and records a leave for everybody who was in it.

  **Not in this slice, for a measured reason**: pointing the SCIM `/Groups` endpoint at the
  managed store. `ScimGroupService.create` runs its contract SQL through `executeQuery` and
  reads the assigned id from the returned row, so a bundled contract set would need
  `insert … returning`, which MySQL and SQL Server do not have. Making it portable means
  changing that create seam, which every existing deployment's hand-authored contract SQL
  depends on — recorded in the design as its own slice.

- **Eligible roles: take one when you need it, and it expires by itself**
  (docs/access-governance.md structural decision 3, slice 3). Every grant was either held or
  not held, so recording "this person may take this role" meant granting it. An **eligibility**
  now grants nothing and never reaches the principal — an eligible role is absent from the roles,
  the permissions and the grant attribution alike. Taking one creates the windowed assignment the
  store already had, so expiry needs no sweeper: the row stops resolving when the window closes.
  Administrators make somebody eligible from their detail page with a limit and an optional
  reason requirement; the person takes the role from their own account page. An elevation passes
  the same separation-of-duties check as any other grant and is recorded with its reason and
  window. **`SessionStore.replacePrincipal`** makes the elevation live on the taker's very next
  request — deliberately narrow: it re-reads this caller's own principal into this caller's own
  session, and nothing else about a signed-in principal refreshes mid-session.

- **Separation of duties: roles nobody may hold at once, checked where grants are made**
  (docs/access-governance.md structural decision 2, slice 2). Nothing anywhere compared two
  grants, so a person could hold `orders.buyer` and `orders.approver` with nothing noticing. A
  constraint now names two or more mutually exclusive role codes and a severity, and it is checked
  at the only two paths that create an assignment. The administrator's write is **refused** with
  `TQL-IAM-4034`, naming the constraint and the role already held. The rule converge **withholds**
  the role instead: rules converge inside sign-in, and refusing there would lock somebody out of
  the product because two attribute rules disagree. IAM Admin gains a constraints page with the
  existing-violation report, because a constraint added over grants that predate it has violations
  the day it is created — reported, never resolved automatically. Static separation of duties only:
  the dynamic half already holds, because a person acts as one role at a time and the audit records
  which capacity acted.

- **Every grant change is recorded, from both paths that can make one**
  (docs/access-governance.md structural decision 1, slice 1). What a person holds could be
  changed two ways — an administrator's edit in IAM Admin, and the assignment rules' converge at
  sign-in — and neither left a record. The route audit covers the first and structurally cannot
  cover the second, because a sign-in materialization is not an HTTP call. `tql_grant_history` is
  now written where the grant is written: who decided (the administrator's login id, or `rule`
  when no person did), whose access changed, which code, which mechanism, and the validity window
  when one was granted. IAM Admin gains a **Grant history** page filtered by user or application,
  and a per-user card on the detail page. The trail is append-only, and a realm whose contracts do
  not include it keeps its grant writes and says it holds no history.

### Changed

- **The IAM admin applications list no longer carries a policy id**, and narrows its rows by
  the caller's grants instead — every member for `tql.iam.admin.view`, their own for
  `tql.iam.view.<name>`, none for neither. It is the one page in that family with no
  application in its address, so there is no atom for a policy to resolve; this is the answer
  the ops console home already gives for its member switcher. A caller holding nothing sees an
  empty list rather than a 403, and no more of the deployment than their grants already showed.

- **A route referencing a `tql.` policy id is no longer linted as an undefined policy**
  (`TQL-SEC-4030`). An id under the framework's mark is the synthesized atom check, defined by
  construction; warning on it flagged every application that gated a route on a framework
  surface's atom.

### Fixed

- **The address a request presented carried a port, so it was inside no network.** The peer of a
  connection reaches the framework as `host:port` — for IPv6 without brackets, as
  `0:0:0:0:0:0:0:1:52344` — which was invisible while the value was only ever displayed on the
  session-visibility surface and became load-bearing the moment a CIDR block was compared
  against it: a sign-in allow-list naming the loopback network would have refused the loopback.
  The presented address is now reduced to its bare host where it is resolved, which also drops
  an ephemeral source port from what a person sees when reviewing their own sessions.

- **A delegated administrator's refusal reached callers as "Internal Server Error".**
  `TQL-IAM-4036` — the containment refusal added with delegated administration — had no HTTP
  status mapping, so the envelope suppressed its message behind a 500. It is now the 403 it
  always was. This is the same defect the separation-of-duties slice found in the same switch,
  one number earlier: an IAM refusal is an answer to the caller, not a fault.

- **The front door forwards under a declared bound, per member**
  (docs/http-threading.md decision 5). The gateway's threads were never the problem — the relay
  is non-blocking end to end, so a member blocked in JDBC costs it a connection and some memory
  rather than a thread. Its **connections** were. `outboundOptions` passed the client's defaults
  through untouched, which meant three things at once: **five** connections per member
  (`maxPoolSize`), fewer than the member's own worker pool, so the front door rather than the
  member was the ceiling; an **unbounded** wait queue behind them, the very queue removed inside
  a runtime one slice earlier; and **unlimited** multiplexed streams under h2c, so turning on a
  *protocol* flag replaced the limit of five with no limit at all. The effective concurrency of a
  whole stack was a number nobody chose, nobody could see, and that changed by an order of
  magnitude with a setting about protocols.

  `tesseraql.gateway.maxConcurrentPerMember` now declares it, defaulting to the stack's
  `tesseraql.http.workerThreads` — admitting more than a member can run only moves the queue one
  hop earlier, and admitting fewer makes the member's own pool unreachable. Beyond it: 503 with
  `Retry-After` and `TQL-RATE-4294`, **for that member only**, so a member whose database has
  stalled holds its own permits and nothing else while the rest of the stack keeps serving. The
  outbound client is sized to the same number in both protocol modes. The permit rides the drain
  counter's existing exactly-once guard, because Vert.x keeps one `endHandler` per response and a
  second registration would have silently replaced the count the stack's stop waits on.

  **A read-idle timeout is offered and deliberately not defaulted.**
  `tesseraql.gateway.readIdleTimeoutSeconds` is off unless an operator sets it: a hung member and
  one running a legitimately long query are indistinguishable from the front door, since both
  accept the request and stay silent until done. Event streams heartbeat every 25 seconds, but a
  report with a raised `timeoutSeconds` can be silent for minutes and is not misbehaving — so any
  number the framework chose would eventually cancel somebody's report. Containment comes from
  the per-member bound instead; reclamation is the operator's call, because only they know their
  slowest legitimate response.

- **A multi-application host has one Vert.x, not one per application**
  (docs/http-threading.md decision 4). `VertxPlatformHttpServer` looks a Vert.x up in the
  runtime's own Camel registry and builds one when it finds none, and TesseraQL bound none — so
  every hosted application got a worker pool and an event-loop pool of its own. A host's thread
  count was a function of how many applications were installed rather than of anything an
  operator chose: five applications on a twenty-core machine meant 100 worker threads and 200
  event loops, and no configuration reduced it. The host now builds one instance from its own
  `tesseraql-stack.yml` and every runtime rides it.

  **The host owns it.** `VertxPlatformHttpServer` closes only an instance it built itself, which
  is what lets one application be stopped or replaced — a canary activation, a `runtime-replace`
  — without taking the transport out from under its neighbours. The host closes it last, after
  every runtime and after the stack's framework pool.

  **A hosted member no longer sizes what it shares, and is told so.** Its own
  `tesseraql.http.workerThreads`/`eventLoopThreads` reach nothing once the host supplies the
  instance, and read-parsed-then-ignored is the shape this codebase removes wherever it finds
  it — so the runtime warns, naming the key and the file that decides it. A warning rather than
  a refusal: the same declaration is correct for the same application run standalone.
  `maxInFlight` stays per runtime, because per-member bounds are what keep one application from
  consuming the shared pool.

  **An accidental bulkhead is replaced, not dropped.** A worker pool per application meant one
  application saturating its own could not reach its neighbours. `app-isolation-model.md` never
  listed thread pools among what mode ② separates, so no promise changes — but "never promised"
  and "never relied upon" are different things, and the isolation model now says which one this
  was. The bound that replaces it is the per-runtime admission gate above, which refuses with a
  503 rather than letting one application quietly consume the shared pool.

- **The HTTP edge admits or refuses, instead of queueing without a bound**
  (docs/http-threading.md decision 3). Route processing runs on a fixed worker pool, and requests
  arriving while every worker was blocked in JDBC queued in Vert.x's blocked-task queue, which
  has no bound. Everything the runtime served queued with them — static assets, rendered
  templates, and health and readiness, which is what turned a slowdown into an outage: an
  orchestrator polling a runtime that could not answer concluded the process was dead and
  restarted it, discarding the in-flight work that was about to finish. The runtime could not
  report that it was saturated because reporting travelled the queue that saturation filled.

  A handler on the platform router now takes a permit before the request reaches Camel and
  releases it when the response ends, on completion and on failure alike. Beyond
  `tesseraql.http.maxInFlight` (default `workerThreads x 4`) the answer is an immediate **503**
  with `Retry-After` and `TQL-RATE-4293` — a slowdown a caller can retry, not a place in an
  invisible queue. It installs ordered ahead of every route Camel registered, because the router
  does not exist until the HTTP server has started.

  **Health is checked before the permit**, so the gate never refuses the one surface whose whole
  purpose is to be answerable when nothing else is. **Exempt from admission is not exempt from
  the worker pool**, and the design says so rather than claiming otherwise: a health request
  behind a saturated pool still waits for a worker, bounded now by `maxInFlight` instead of by
  however many requests arrived. Answering without a worker means serving the readiness roll-up
  from the event loop off the result its TTL cache already holds — a change to how readiness is
  computed rather than to how requests are admitted, recorded as the remaining half.

  This is a runtime-wide floor, not a replacement for `admission.concurrency.maxInFlight` (per
  route) or `admission.lane` (per route, onto a named execution lane). Neither ever bounded the
  runtime as a whole.

- **A connection pool's size and acquisition timeout are TesseraQL's defaults, not Hikari's**
  (docs/http-threading.md decision 2). `DataSources` mapped every pool knob with `ifPresent` and
  set nothing, so an application that declared none got whatever the driver pool had decided —
  the answer to "how many connections will this open" lived in a dependency's release notes and
  could change when that dependency changed its mind. `maximumPoolSize` now defaults to **10**,
  matching `tesseraql.http.workerThreads` so a worker never waits for a connection that does not
  exist, and `connectionTimeoutMillis` to **30000**. Both are the values applications already
  received; what changes is who decides them. `leakDetectionThresholdMillis` stays off — it is a
  debugging aid whose log volume is an operator's decision. `deployment.md` documents the whole
  per-datasource set for the first time.

  **The configuration index still cannot see them.** `reference-config.md` is generated by
  scanning for `getString("literal")`, and these keys are read through a computed prefix
  (`tesseraql.datasources.<name>.`, and `tenancy.datasources.<tenant>.` for the same builder), so
  seven production-relevant keys are absent from a page whose preamble says it lists all of them.
  Recorded rather than papered over: one documented spelling would be wrong for one of the two
  prefixes, so making dynamic-prefix keys visible is a change to the generator and to the
  `tenancy.*` family with it.

- **The HTTP worker pool is a declared number, not an inherited default**
  (docs/http-threading.md decision 1). Every request runs on it — `camel-platform-http-vertx`
  hands each exchange to `executeBlocking` — so its size is a runtime's ceiling on concurrent
  route execution. TesseraQL bound no `VertxOptions`, so that ceiling was Vert.x's default of
  **20**, a size chosen for a framework where blocking is the exception, against a connection
  pool left at Hikari's default of **10**. Half the workers could therefore only ever wait in
  `getConnection()`, for up to the also-unset 30-second acquisition timeout. Two libraries had
  each answered half of one question and nothing in TesseraQL stated either number.
  `tesseraql.http.workerThreads` (default **10**, matching the connection pool) and
  `tesseraql.http.eventLoopThreads` (Vert.x's `2 x cores` unless set) now say it, and a count
  that is not a positive integer refuses at startup with `TQL-YAML-1112` rather than starting
  with a pool nobody asked for.

- **A batch step's declared `timeoutSeconds:` is the bound it runs under**. The schema accepted
  it, `reference-yaml-surface.md` documented it as a "per-binding SQL statement timeout override",
  and the parser carried the value all the way into the model — where the batch executor then
  ignored it. `JobExecutor` built every step's bounds from the app-wide `tesseraql.sql.timeoutSeconds`
  alone, so a declaration on a job step, a chunk reader or a chunk writer parsed cleanly and did
  nothing. An extract that legitimately takes minutes could only be given room by loosening the
  bound every request in the application ran under, and the failure looked like a slow statement
  being cancelled rather than a setting that was never read. Each binding now resolves its own
  declaration first and falls back to the app-wide default — the precedence a route and a command
  have always applied — and a step's `enrich:` references inherit the bound their owning binding
  resolved, as they do on a route.

  **An `export:` step is not covered yet.** It renders its arm and hands the `BoundSql` to
  `JdbcFileTransferService`, which applies its own app-wide timeout; `InlineExport` carries no
  bound, so honouring the declaration there means changing a core contract the route export path
  shares. That is a wider decision than this fix, and it is recorded rather than half-made.

- **An IAM refusal says what it refused, instead of "Internal Server Error"**
  (docs/access-governance.md slice 2 found this). Only `TQL-IAM-4030` had an HTTP status; every
  other IAM code fell through to 500, so a capability refusal (`4031`), a malformed rule condition
  (`4032`) and a rejected role-admin input (`4033`) all reached the administrator as a server
  fault with the message suppressed. They now answer 403, 400 and 400, and the new
  separation-of-duties conflict answers 409 with the constraint and the conflicting codes in the
  envelope's declared-safe `details`.

- **An application's declared modules can supply its object store and its runtime extensions**
  (docs/module-scope.md structural decision 2, corrected). Module jars load on the declaring
  application's own classloader, and two discovery sites never looked there: `BlobStores` and
  `RuntimeExtensions` read the thread context classloader, which in a hosted runtime is the process
  classpath. So an application that declared `io.tesseraql:tesseraql-s3` in `tesseraql.modules` and
  set `tesseraql.object-storage.provider: s3` was told at start that its provider was missing
  (`TQL-YAML-1108`), and an extension delivered as a module was never discovered at all — while the
  codec side of the same decision had worked all along. Both take the application's module loader
  now, plugin jars and the classpath remain the other two sources, deduplication is still by
  implementation class, and `tesseraql.plugins.allowlist` still gates every source alike.

- **A path parameter is what the URL says, not what the request also carried under that name.**
  The HTTP transport publishes path parameters as message headers — and query parameters and
  form-body fields there too, under their own names — and the binder read the header. So a query
  parameter sharing a path parameter's name arrived *joined* with the path value
  (`/users/u1?id=u2` bound `u1,u2`; so did `?id=u1`, and the route then matched nothing), and on
  a route that also declared the name as an input, a body field of that name *replaced* it
  outright. `path.id` could be an id the caller chose, and so could `params.id` and every
  `sql.params` expression reading either — a route addressed to one row operating on another.

  Path parameters are now read off the request's URL, matched against the route's own template
  (`PathTemplate`, aligned from the end so a base path is ignored); the message header remains
  the answer only where there is no URL to read, such as a `direct:` invocation. A declared
  input sharing a path parameter's name still **types and validates** that path parameter
  (typed path parameters) — it no longer *sources* it, so such a name can no longer double as a
  body field.

### Added

- **A stack runs on several nodes from one shared install root** (docs/hosting.md "A stack on more
  than one node"). A host converged to the install root on filesystem events alone, and a watch
  service reports only what its own host wrote — so on a shared directory a deploy performed by one
  node was invisible to the others, which kept serving the old version with no signal. The host now
  also reconciles on a **sweep**, every `stack.reconcile.interval` (default 15 seconds, `0` for
  events only), and a directory that cannot be watched at all — some network mounts refuse
  registration — degrades to the sweep with a warning instead of refusing to start. A pass is a
  catalogue read and a diff against the running slots, so an idle sweep costs one small file read.
  With that, `tesseraql deploy` and the ops console's deploy page reach every node of a stack that
  shares its install root; the ops console page says so, and says what happens when nodes keep
  independent roots. Choosing which node runs which application, separating batch work onto its own
  nodes, and a fleet-wide view are deliberately not here — they are recorded, with the positions
  already taken, as roadmap Phase 61.

- **One place to put a framework database driver, on every distribution** (docs/module-channel.md
  decision 6, slice 4b). A stack's own pools — the framework datasource, the migration pool —
  resolve their driver through `DriverManager` on the process classpath, which an application's
  module channel cannot reach: those jars load on that application's classloader, and the framework
  pool belongs to no application. A stack whose framework database was SQL Server, Oracle or MySQL
  therefore could not start under `dev` at all, and failed with the JDBC layer's `No suitable
  driver` elsewhere. Now `tesseraql-stack.yml` declares what the stack's classpath needs
  (`framework.datasource.modules`), `tesseraql modules fetch --stack` collects it, and each
  distribution reads one place: `lib/` in the container image, `lib/ext/` (or `TESSERAQL_CLASSPATH`)
  beside a launcher, and `app\ext\` plus an `app.classpath` line on the Windows app image — the
  last asserted on `windows-latest` in both directions. When no driver accepts the framework URL
  the host refuses with `TQL-APP-4220`, naming the declared coordinate and the placement step. The
  launchers now compose an explicit classpath instead of `java -jar`, and their class-data-sharing
  archive is keyed on the whole classpath rather than the fat jar's size — an added extension jar
  used to leave the JVM silently refusing a stale archive, costing start-up time with nothing
  printed.

- **`tesseraql modules fetch` — one bag for a disconnected machine** (docs/module-channel.md
  decision 5, slice 4a). Everything a distribution does not carry is a Maven coordinate, and each
  used to travel its own road: an application's modules through `modules resolve --offline` against
  a hand-seeded repository, the framework database driver through prose, and the embedded
  PostgreSQL binary — resolved on demand since the zonky exclusions — through nothing at all.
  `modules fetch --stack <dir> --into <bag>` collects all of it in one pass: each member's closure
  as `modules.lock` pins it (an application with no lock is refused), the BOM, and the embedded
  PostgreSQL binary for every `--platform` named, with a `bag.json` manifest recording what came
  from where with a checksum each. The bag is built by *resolving into* it rather than copying
  jars, so it carries the poms and metadata an offline resolve checks. The disconnected side reads
  it with the new `--repo <dir>` option — `modules resolve`, `package` and `dev` all take it, and
  with `--offline` nothing leaves the machine. MariaDB Connector/J (LGPL-2.1) joins the BOM's
  managed coordinates as the redistributable alternative to `mysql-connector-j`.

### Changed

- **A `.tqlapp` carries the modules it declares** (docs/module-channel.md decision 3, slice 3).
  Packaging excluded `work/`, where the resolved module cache lives, so an application that
  declared `tesseraql.modules` produced an archive with none of them in it — and the host, having
  no resolver, refused to start it (`TQL-APP-4216`) with a remedy that needed the developer CLI on
  the deployment machine. `tesseraql package` and the `package-app` goal now resolve the closure
  `modules.lock` pins and carry it inside the archive under `.tesseraql/modules/`; an installed
  application loads that set and never consults `work/modules`, so a stale directory on the host
  can neither join nor shadow what the archive was verified with. Packaging resolves rather than
  demanding a prior command, because the lock — not the moment of resolution — is what makes the
  archive reproducible: declaring modules with no lock is refused (`TQL-APP-4218`, naming
  `tesseraql modules resolve`), and a closure that disagrees with the lock is refused
  (`TQL-APP-4219`, the pack-time twin of the host's `TQL-APP-4217`). The Maven goal resolves the
  locked coordinates through Maven's own repository system, which is also the only route it has:
  the plugin has no command that writes a lock. A new lint (`TQL-YAML-1408`) warns when an export
  uses a format whose codec the application neither declares nor carries, the case where a
  wrapper-pom build works locally and the package fails at its first export after deployment.

- **Corporate identity travels with the runtime: OIDC, SAML and SCIM are no longer opt-in jars**
  (docs/module-channel.md decision 2, slice 2). `tesseraql-oidc`, `tesseraql-saml` and
  `tesseraql-scim` join `tesseraql-camel-runtime`'s compile scope, so the developer CLI, the
  deployment distribution and the host image all carry them: 195 runtime artifacts become 198,
  the host's 199 become 202, and the three jars total 168 KB with no third-party artifact
  behind them. Activation is unchanged — each is a `RuntimeExtension` whose `enabled` is false
  without its own configuration key, and `tesseraql.plugins.allowlist` still gates classpath
  providers as well as plugin jars — so presence activates nothing and configuration decides.
  What changes is that enterprise SSO on a Windows Server or container deployment is now
  configuration rather than adding a jar to an image, which the deployment distribution has no
  supported way to do. A `weightless-on-the-runtime` enforcer rule in each of the three modules
  fails the build if one ever declares a dependency outside `io.tesseraql:*` (plus Jackson for
  SCIM), because that weightlessness is what justifies carrying them everywhere.

- **The developer distribution stops carrying a module bag, and S3 stops carrying two unused
  HTTP stacks** (docs/module-channel.md decisions 4 and 8, slice 1). The CLI dist archive
  shipped a `modules/` directory holding the pdf/excel closure — 24 MB that nothing pointed
  at: the launcher never passed `--modules`, and an application's opt-in codecs reach it
  through its own `tesseraql.modules` cache instead. Shipping it could only ever have given
  development a codec the deployment does not have, so it is gone, and the dist CI job fails
  if it returns. `tesseraql-s3` selects the JDK-based `url-connection-client` and said so in
  a comment, while `software.amazon.awssdk:s3` kept pulling its default clients transitively —
  `apache-client`, `netty-nio-client`, and `apache5-client`, the one that actually drags in
  Apache HttpClient 5. Excluding all three takes the module's resolution from 64 artifacts to
  49, and what an S3 application adds beyond the runtime closure from 37 jars and 11.0 MB to
  31 jars and 8.3 MB — 2.7 MB of HTTP stacks it never called. A `no-unused-http-clients`
  enforcer rule bans the clients and the stacks they bring, so a fourth default client cannot
  reintroduce them under a new name.

### Added

- **Windows Server has a deployment artifact and a supervision story**
  (docs/runtime-footprint.md decision 2a, slice 5). Releases now attach
  `tesseraql-host-<version>-windows-x86_64.zip`: the `tesseraql-host` distribution as a
  jpackage app image with a bundled Java runtime, plus `tesseraql-host-service.xml` — a
  WinSW service definition, so the host runs under the Service Control Manager with the
  console streams as the service log. A service stop reaches the same ordered drain SIGTERM
  reaches in the container, and CI asserts it: the release build installs the image as a
  real Windows service against a real PostgreSQL, serves a stack, stops the service, and
  fails unless the drain ran. docs/hosting.md gained the Windows Server section.

- **The deployment distribution: `tesseraql-host`** (docs/runtime-footprint.md decision 1,
  slice 2). A deployment now ships the host, not the workshop: the new module carries the
  runtime and the operator verbs (`host`, `deploy`, `routes`, `token`, `migrate`, `job`,
  `identity-schema`, `verify`, `admission`, `duckdb`) on a classpath of 199 artifacts —
  none of them Studio, the declarative test engine, the embedded-database supervisor, or
  the ~46-jar artifact-resolver stack the developer CLI needs and a deployment never does
  (module caches are resolved before deployment; the CLI-side authoring commands now read
  the on-disk cache when the resolver is absent). The command implementations are the
  developer CLI's own, unchanged; an enforcer rule (`no-workshop-in-the-deployment`) makes
  the boundary a build failure. `deploy/Dockerfile` builds from `tesseraql-host` and its
  entry point is `io.tesseraql.cli.TesseraqlHostCli`; the demo image stays on the full
  developer CLI, because a demo is a development experience. `DriverManagerDataSource`
  moved from `tesseraql-report` to `io.tesseraql.core.jdbc` (pre-1.0 breaking move, no
  alias): it is shared by the operator commands, the Maven plugin and the test tooling,
  and its old home compile-depends on the test engine.

- **The ops console grew the deploy endpoint's browser face** (docs/stack-shells.md, the
  deploy page — the slice-3 follow-up). `/_tesseraql/ops/console/deploy` rides the shell's
  chrome, appears only for a signed-in holder of any `tql.app.deploy` grant, lists the
  members those grants cover with the version each serves right now (read live from the
  host's slots, so a replace never shows stale numbers), and uploads a `.tqlapp` — optionally
  canary-staged with a weight — to `POST /_tesseraql/deploy` itself. The endpoint learned the
  browser's shapes without touching the JSON contract: a multipart body (the `file` part is
  the package; form fields ride as they do on every compiled route), the `_csrf` field beside
  the `X-CSRF-Token` header, and post/redirect/get answers (`HX-Redirect` for the htmx
  submit, a 303 for the no-JS form). The gate is display only: the endpoint still checks the
  atom against the package's declared name on every submit, so the page widens what is
  listed, never what is deployed.

- **The MCP surface is gated, discoverable, and audience-bound** (docs/token-issuance.md
  slice 10, delivering docs/audit-hardening.md slices 6 and 7). `tesseraql.mcp.auth` adds the
  transport gate the handler always had a seam for: `public` by default — nothing changes
  without opting in — and `bearer` demands a token whose audience is the surface's canonical
  resource identifier (derived from the address: the member's address plus
  `/_tesseraql/mcp`), so a token minted for the application beside it still refuses. The 401
  carries `WWW-Authenticate: Bearer resource_metadata="…"` — the discovery the measured
  clients try first — and the stack origin serves RFC 9728 protected-resource metadata at the
  path-inserted well-known per member, naming the stack's authorization server. With this, an
  MCP client can walk discovery → registration → consent → code → token → tools against a
  stack whose only identity source is TesseraQL's own login.

- **The account page shows what you authorised, and revoking it means it**
  (docs/token-issuance.md slice 8). `/_tesseraql/account/connections` lists the signed-in
  user's OAuth connections per client and per resource — the client's registered name rendered
  escaped, the application it reaches, the acting capacity, and how many live refresh chains
  stand behind it. Revoke deletes the consent and revokes every matching refresh chain in one
  act: the next refresh answers `invalid_grant`, and coming back is a re-authorization through
  the consent screen. On a stack with no authorization server the page says so instead of
  breaking.

- **The stack tells clients where its authorization server lives** (docs/token-issuance.md
  slice 7). `GET /.well-known/oauth-authorization-server` at the stack origin serves RFC 8414
  metadata — the issuer is the origin with no path component, so the document sits at the bare
  well-known and the endpoints are listed absolute. `code` responses only, `S256` only, `none`
  and `client_secret_basic` auth methods, and deliberately **no `scopes_supported`** — the
  measured clients proceed without it, and the scope parameter grants nothing here. The
  gateway's origin fence now forwards `/.well-known/*` to the stack surface runtime.

- **`/register`: a client announces itself, and consent is why that is safe**
  (docs/token-issuance.md slice 6). `POST /_tesseraql/oauth/register` serves RFC 7591 dynamic
  registration, open because the MCP clients this exists for cannot present an initial access
  token — and mandatory consent is the boundary that makes open registration tolerable. The
  registry stores what a client sent (display text, never vouched for), the complete redirect
  URIs it will be matched exactly against — the measured Codex shape — and a last-seen stamp
  for finding registrations nothing ever used, since a new ephemeral port is a new
  registration by design. Native loopback clients register public (`none`); a client asking
  for `client_secret_basic` is issued a secret it sees once. The lint `TQL-OAUTH-3004` now
  tells an application declaring `tesseraql.security.oauth.enabled` in its own tree that the
  key belongs in `tesseraql-stack.yml`.

- **`/token`: the code redeems, the refresh rotates, and every mint asks the store again**
  (docs/token-issuance.md slice 5). `POST /_tesseraql/oauth/token` serves the
  authorization-code and refresh grants over the stack's provider: client credentials are
  verified against the stored hash in constant time (a public client presents its id alone),
  PKCE closes the code flow, a reused refresh token retires its whole chain, and every refusal
  speaks OAuth's wire vocabulary. Tokens are minted from the subject's **current** view — the
  identity store is re-resolved at every mint, so grant changes, validity windows and rule
  recomputation propagate at refresh cadence, a disabled account stops refreshing, and a
  revoked acting role ends the connection's capacity as `invalid_grant` instead of riding out
  the chain. Access tokens carry the stack's claim names and the origin as `iss`, so a token
  from the OAuth door validates at members exactly like one from the session exchange.

- **`/authorize`, and consent that names what it grants** (docs/token-issuance.md slice 4).
  The authorization server's front door: `GET /_tesseraql/oauth/authorize` asks one question —
  whether the caller holds a session, however it was obtained — and bounces through the
  existing sign-in when not. First contact lands on the consent screen (an auth-ui page):
  the client's registered name rendered escaped and never vouched for, the resource named,
  and — for a concurrent-role user — the acting-role selector, single holder pre-selected.
  Approval records consent **per client and per resource** with the capacity riding it, and
  answers with the authorization code, the echoed `state`, and RFC 9207's `iss`; a recorded
  consent skips the screen. PKCE is required and `S256`-only; an unknown client or mismatched
  redirect URI is never redirected to; a request without a resolvable RFC 8707 `resource`
  refuses with `invalid_target` rather than guessing an audience. `/token` arrives with the
  next slice.

- **A token for one application, from the same two doors** (docs/token-issuance.md decision 9,
  the member axis). `tesseraql token --url <origin> --app-name <member>` and the console token
  page's application selector mint for one stack member: the token's audience is that member's
  address — it refuses at every other member — and its claims are the member's active view
  under the browser's own entry rules, per token (one held role auto-activates, several stay
  inactive unless `--as` selects one). Nothing named keeps the stack-wide mint verbatim. An
  unaddressed member answers `TQL-OAUTH-3003` (HTTP 400); a role not held for the named member
  stays `TQL-SEC-4148`. A runtime's own address is now always among its accepted audiences,
  beside anything it declared.

- **One issuer per stack** (docs/token-issuance.md decision 9, the unification core). With
  `security.oauth.enabled` in the stack file, the host derives one RS256 validation block —
  the origin as issuer, the surface's JWKS, the stack's claim names — and every hosted runtime
  applies it: nothing is declared per member, a runtime's accepted audiences are its own
  address plus the stack origin, and the session exchange (`/_tesseraql/token`, the console
  token page) signs with the same database-held key, so a token from either door validates at
  every member. A JWT key source declared beside the stack's issuer is refused as a second
  issuer (`TQL-OAUTH-3001`), an issuer without a declared `externalOrigin` is refused
  (`TQL-OAUTH-3002`), and `TQL-SEC-4146` narrows to "no HS256 secret **and** no stack issuer".

- **The stack signs RS256, and every replica serves one JWKS** (docs/token-issuance.md
  slice 3). `security.oauth.enabled` in `tesseraql-stack.yml` installs the authorization
  server's surface on the stack surface runtime; first start generates an RSA-2048 pair into
  `tql_oauth_signing_key` on the `security` component (V6) — exactly-once without a
  coordinator, because the first key's `kid` is a reserved primary key and concurrent starts
  race on it. `GET /_tesseraql/oauth/jwks` publishes the key set; rotation
  (`SigningKeys.rotate()`) inserts the new signer before retiring predecessors, and a retired
  key stays published until every access token it signed has expired — the bound is the
  access-token lifetime (`tesseraql.security.oauth.accessTokenTtl`, default 15m). Access
  tokens now mint RS256 with a `kid` header through `Rs256TokenSigner`, and a minted token
  validates through the same `Jwks` parser member bearer validation uses. Enabling the key on
  a stack member is refused at boot (`TQL-OAUTH-3000`) — the issuer is the stack's, declared
  in the stack file.

- **The authorization server's grant core: CXF's grant layer beneath TesseraQL's own storage**
  (docs/token-issuance.md slice 2). A new `tesseraql-oauth` module drives CXF's real
  authorization-code and refresh grant handlers over a twelve-method data provider backed by
  TesseraQL's own tables — `tql_oauth_client`/`_code`/`_refresh`/`_consent` on the `security`
  migration component (V5), migrated once per stack by the host. Codes and refresh tokens are
  stored only as SHA-256 hashes; codes are single-use as a database property; refresh rotation
  is a guarded single-winner update and a reused token retires its whole chain; PKCE is
  required and `S256`-only; the `scope` parameter is accepted and grants nothing; access
  tokens stay stateless behind an `AccessTokenSigner` seam the signing-key slice fills. The
  JAX-RS runtime CXF's endpoints would drag is excluded — the grant layer runs on the
  `jakarta.ws.rs` API jar, `cxf-core` and the JOSE library alone, proven by the unit suite.
  No endpoint is served yet; `/authorize`, `/token` and `/register` arrive with their slices.

- **A multi-role user acts as one role per tab — or per token — and the trail says which**
  (docs/application-roles.md slice 5, activation). The acting role rides the address:
  `/<member>/_as/<role>/…` is normalized by the stack relay into an internal header the
  member validates against the caller's **own** grants — a forged segment or header narrows,
  never widens (TQL-SEC-4148: role picker for a browser, 403 otherwise). The new
  `tesseraql-auth:activate` step (after the fence, same unhosted no-op guard) swaps the
  exchange's principal for the active view: stack-wide roles plus the one activated role,
  permissions recomputed from their bundles plus direct grants — policies, scopes, menus,
  field policies and ambient binds all read it, while the fence, framework atom checks and
  bearer minting keep the union. Browser entry is automatic (single role auto-activates,
  several redirect to the origin picker `/_tesseraql/roles`); the member chrome gains the
  role switcher (`_acting`); emitted links carry the segment, asset URLs do not;
  `tql_route_audit` gains `acting_role` (V2 migration); and `tesseraql token --as <role>` +
  the ops token page's selector mint the active view with an `acting_role` claim.
  BREAKING (pre-1.0, no migration steps): `RouteAuditSink.RouteAuditEvent` widens by the
  `actingRole` component.

- **Provisioning and SSO land the attributes, and a federated identity links once**
  (docs/application-roles.md slice 4, second half). SCIM stops discarding the enterprise
  extension: `department`/`division`/`costCenter`/`employeeNumber`/`manager` (plus a
  configured `tesseraql.scim.attributes.map` of additional SCIM paths) are captured into
  `tql_user_attributes` on create *and* update when `tesseraql.scim.attributes.enabled`
  is on, keyed by the SCIM resource id. The SAML and OIDC linkers gain declared attribute
  maps (`tesseraql.saml.attributes.map`, `tesseraql.oidc.claims.map`) and stop returning
  early on existing users — the mapped set re-syncs at every login, before the principal
  resolves, so that sign-in's assignment rules already see the fresh values. Federated
  users get an immutable key: `tql_user_identities` links OIDC by `iss`+`sub` and SAML by
  the persistent NameID (or `tesseraql.saml.link.subjectAttribute`), so a login-id change
  at the IdP re-syncs the same account — roles, grants and all — instead of provisioning
  a duplicate; login id, display name and email become mutable, re-synced profile fields.
  BREAKING (pre-1.0, no migration steps): the `ScimUser` record gains the enterprise
  extension and `SamlAttributeMapping`/`OidcConfig` grow their map slots; the linkers now
  write the link table and re-sync profiles where they previously only read; the
  identity-schema seeder mints an opaque admin `user_id` instead of reusing the login id
  (existing stores keep their seeded ids — the seed upserts by login id).

- **Attributes arrive with the user, and rules assign the roles**
  (docs/application-roles.md slice 4, first half). `tql_user_attributes` holds free-form
  named attributes (department, title, …), edited on the IAM user detail page and merged into
  `principal.claims` at sign-in. Assignment rules (`/_tesseraql/admin/rules`) grant a role
  when their conditions match — kinds `eq`/`in`/`neq`/`not-in`/`group`/`subtree` over the
  managed org closure, comparisons never expressions, refused with `TQL-IAM-4032` —
  evaluated at sign-in and materialized into `tql_user_roles` with `source = 'rule'`
  provenance: manual assignments always survive recompute, and "recompute now" (one user
  or all) covers the edit-to-login window. The SCIM/SSO attribute capture and the
  federated identity keys follow as the slice's second half.

- **An application declares its duty roles, and the store converges to them**
  (docs/application-roles.md slice 3). `tesseraql.security.roles` lists the roles an
  application ships — code (carrying the application's own name as its first segment),
  display name, and a bundle of the application's own permission codes — validated at lint
  and boot (`TQL-YAML-1407`) and reconciled into the identity store at every boot on a
  managed realm: bundles converge to the declaration, every application role carries the
  implicit `tql.app.use.<name>` atom as a visible row, and a role the declaration dropped
  is stamped *orphaned* (assignments kept, badged on the roles page, revived by
  re-declaring). `tql_roles` gains a `source` column (`admin`/`declared`/`orphaned`).

- **Roles gain the application axis, direct grants, and validity windows**
  (docs/application-roles.md slice 2). The managed identity schema grows
  `tql_roles.application` (null = stack-wide), `tql_user_permissions` (per-user direct
  grants), and `starts_at`/`ends_at` windows plus a `source` provenance column on
  assignments — resolution filters to the open window at sign-in, so time-limited
  authority is an end date and a future-dated appointment is a future start. IAM Admin gains the roles page
  (`/_tesseraql/admin/roles`) and per-user grant editors; the `Principal` carries
  `roleGrants` (role → application → bundle) and `directPermissions` for the activation
  slices. Role-management writes answer to the realm's until-now-unchecked
  `roleManagement` capability (`TQL-IAM-4031`); refused inputs answer `TQL-IAM-4033`.
  BREAKING (pre-1.0, no migration steps): existing managed stores must add the new
  columns/table by hand or recreate; the schema script only creates absent tables.

- **IAM Admin answers "who may do what in this application"** (docs/application-roles.md
  slice 1). The new applications pages (`/_tesseraql/admin/applications`, one page per stack
  member) list, per application: the holders of its `tql.app.use` grant with wildcard holders
  under their own rows, the ops view/run, deploy and Studio atom holders, and the
  application's own permission codes with each holder and the role that delivered it —
  read-only, derived entirely from the atom grammar and the existing identity store. The two
  backing contracts (`find-permission-holders`, `list-permissions-by-prefix`) are optional:
  a `sql` realm that does not provide them sees the pages degrade with a notice, never fail.

- **The stack's authenticated deploy surface: `POST /_tesseraql/deploy` and `deploy --url`**
  (docs/stack-shells.md slice 3; docs/runtime-replace.md open question 5's arrival). The
  surface runtime serves an endpoint that receives a `.tqlapp` as its request body — bearer
  from `tesseraql token` or a browser session with its CSRF token — checks the caller's
  `tql.app.deploy.<name>` grant against the **package's declared name**, runs the same
  preflight `tesseraql deploy` runs, and writes the same intent on the host's install root; a
  refused deploy answers as the response and writes nothing, and the running host's
  reconciler converges as ever. `tesseraql deploy <package> --url <origin>` is the CLI's
  remote mode (`--stack` xor `--url`, mirroring `token`'s dual shape; bearer via
  `TESSERAQL_TOKEN` or `--token-file`), so a pipeline deploys only the applications it
  manages with a scoped short-lived token and no install-root access.

- **The stack file's `security:` subtree configures the stack surface runtime**
  (docs/stack-shells.md slice 3). `security.jwt.*` and `security.token.enabled` in
  `tesseraql-stack.yml` graft onto the surface runtime's configuration, turning on the
  origin's bearer validation, token page and `/_tesseraql/token` exchange — which is what
  makes `tesseraql token --url <origin>` the stack's token acquisition path. Members keep
  their own declared JWT configuration. Declare `rolesClaim`/`permissionsClaim` so the
  caller's grants ride the minted tokens.

### Changed

- **Distributions no longer bundle embedded PostgreSQL platform binaries**
  (docs/runtime-footprint.md problem 1, slice 1). zonky's `embedded-postgres` pulled three
  platform binary bundles — 62 MB, and PostgreSQL 14 where the project configures 17 —
  transitively at runtime scope into every distribution: the deployment image, the fat jar,
  the dist archives, and the jpackage app images. The CLI's own resolver never read them (it
  resolves the configured version on demand, per platform), so they were dead weight; they
  are now excluded, an enforcer rule (`no-bundled-database-binaries`) keeps any platform
  bundle out of the CLI's compile and runtime scope for good, and the embedded-db
  integration test asserts the server that starts is the configured major.

- **A placed version directory is immutable** (docs/runtime-footprint.md decision 4,
  slice 4). Installing a version that is already on disk is a true no-op when the package is
  byte-identical (the idempotent re-install, as before) and is refused with `TQL-APP-4090`
  when the content differs — previously the directory was silently deleted and replaced,
  which loses files a running host holds open (Windows refuses the delete mid-walk and
  leaves a half-deleted version; Linux tolerates it silently). Moving a name forward means a
  new version; nothing in the install path deletes a placed tree anymore.

- **One Studio per stack, in development only** (docs/studio-shell.md slice 3). The workshop
  moves to the shells model: `/_tesseraql/studio` at the stack's origin is the switcher —
  members filtered by the caller's `tql.studio.edit` atoms, deny-by-default — and each
  application's workshop lives under `/_tesseraql/studio/<name>/ui`, every page delegated
  over loopback to that member's workshop API with the caller's own credentials, which the
  member re-checks. The workshop exists only where the source is: `dev` over source trees
  mounts it; a hosted production stack — and a `dev` pointed at an install root — mounts
  nothing Studio-shaped, with no configuration to change that. Changes reach a host through
  `deploy`, never an editor. Pre-1.0 breaking changes ride along: hosted members no longer
  serve `/<name>/_tesseraql/studio/**` (the per-member mount is gone, and with it the
  read-only Studio production members used to carry — docs/studio-shell.md records the cost),
  the unhosted boot's Studio moves to the same member-shaped addresses
  (`/_tesseraql/studio/<name>/ui`), and docs share links gain the member segment. New codes:
  `TQL-STUDIO-4043` (unknown or out-of-scope workshop member, 404-shaped) and
  `TQL-STUDIO-5030` (a member's runtime did not answer the delegated call, 503).

- **Who may edit in Studio is the `tql.studio.edit.<name>` atom** (docs/studio-shell.md
  slice 2). The per-application grant — checked against the caller's permissions, family
  wildcard honoured, deny-by-default — replaces the retired `tesseraql.studio.readOnly`
  master switch and `tesseraql.studio.editRoles` role allow-list, the model's last framework
  surface reading role names; `TQL-STUDIO-4031` retires with them (the refusal is the
  standard 403). The capability switches (`testRunner.enabled`, `scaffold.enabled`,
  `dataBrowser.*`, `confirmApply`) stay configuration and no longer require a writable
  master switch. `identity-schema`'s bootstrap baseline gains `tql.studio.edit.*`, so the
  development loop is unchanged out of the box. Pre-1.0 breaking change: deployments that
  set the retired keys delete them and grant atoms instead.

- **Studio's runtime machinery moved to `tesseraql-studio-runtime`** (docs/studio-shell.md
  slice 1). The Studio providers, JSON API, sandboxed test runner, scaffold and data services,
  and the Copilot transports now live in a `RuntimeExtension` module the runtime discovers
  from the classpath, exactly like SCIM/SAML/OIDC — and `tesseraql-camel-runtime` no longer
  depends on `tesseraql-studio` or on the declarative test framework, so a deployment
  assembling its classpath from the runtime carries no test engine, no GreenMail and no
  JUnit 4 (docs/runtime-footprint.md problem 2; an enforcer rule now guards the boundary).
  Pre-1.0 breaking change for hand-assembled classpaths: Studio now needs the
  `tesseraql-studio-runtime` jar beside `tesseraql-studio` — the developer CLI ships both,
  and behavior there is unchanged.

- **The bootstrap baseline gains `tql.app.deploy.*`** — `identity-schema`'s default
  `--admin-permissions` now also carries the deploy wildcard, so a fresh stack's first
  administrator holds every door the atoms guard, the new deploy pen included.

- **Deploy refusals now have HTTP shapes** for the endpoint: an incompatible preflight is
  `409` (`TQL-UPGRADE-4090`), a name the catalogue does not hold is `404`
  (`TQL-UPGRADE-4091`), and an invalid or integrity-failed package is `400`
  (`TQL-APP-4041`, previously the domain's 404 default).

- **Using an application in a hosted stack is a grant: the `tql.app.use.<name>` fence**
  (docs/stack-shells.md structural decisions 1 and 3). On a hosted stack member, an
  authenticated principal — browser session and service caller (JWT/API key/mTLS) alike —
  without `tql.app.use.<member>` (or the `tql.app.use.*` wildcard) is refused 403 before any
  route runs; routes declaring `auth: public` are untouched, and the unhosted boot
  (`TesseraqlRuntime` started directly) is unchanged. The portal's tiles filter by the same
  atom beside tenant entitlement, deny by default, so what a user sees and what they can
  reach are one answer. Adopting stacks must seed `tql.app.use` grants (or a baseline role
  bundling the wildcard) before their users sign in; the `identity-schema` bootstrap's
  `--admin-permissions` now defaults to the wildcard baseline (`tql.app.use.*`,
  `tql.ops.view.*`, `tql.ops.run.*`, `tql.iam.admin.view`, `tql.iam.admin.write`) instead
  of empty.

- **One sign-in door and one admin door per stack: hosted members stop mounting `auth-ui`,
  `account` and `iam-admin`** (docs/stack-shells.md structural decision 3). IAM Admin mounts
  once into the stack surface runtime at the origin's `/_tesseraql/admin/…`; its authority
  moves under the mark as the store-wide atoms `tql.iam.admin.view` / `tql.iam.admin.write`
  (the `iam.admin.*` policy ids in a deployment's `tesseraql.security.policies` stop being
  read — pre-1.0, no migration steps). A hosted member's 401 bounce goes origin-absolute —
  `/_tesseraql/login?redirect=<the prefixed page>` — and returns to the member page after
  sign-in; member pages link the account chip, inbox and IAM Admin at the origin, and the
  pin toggle and theme persistence post there too. The unhosted boot keeps all five mounts
  and its base-relative bounce.

- **The login round-trip's parameter is `redirect`, renamed from `next`** (docs/stack-shells.md
  structural decision 3). The 401 bounce, the login page's hidden field and the login POST all
  carry the original target as `redirect`; the reset/invite forms' `next` (a new password
  field) is unrelated and unchanged, as are OIDC's cookie relay and SAML's RelayState.

- **A `tql.*` policy id is the framework's atom check, synthesized — and cannot be declared**
  (docs/stack-shells.md structural decision 1, TQL-YAML-1406 widened). A route's
  `security.policy` naming an id under the framework's mark (`policy: tql.iam.admin.view`)
  checks that granted atom directly, family wildcard honoured, with no
  `tesseraql.security.policies` entry behind it; a configuration declaring its own policy id
  under `tql.` is refused at lint and boot so the synthesized meaning cannot be shadowed.

- **One authorization grammar for the framework's surfaces: marked atoms
  `tql.<family>.<verb>.<name|*>`, and the ops entry permissions retire into them**
  (docs/stack-shells.md structural decision 1, closing stack-architecture open question 4).
  `ops.batch.view`, `ops.batch.run` and the `ops.app.<name>` scope string are gone; seeing an
  application's operational data is `tql.ops.view.<name>` and acting on it — run/cancel jobs,
  redeliver outbox and dead-lettered events, refresh a catalog — is `tql.ops.run.<name>`,
  granted separately so *view broadly, act narrowly* is finally expressible. The wildcard is
  a terminal `*` (`tql.ops.view.*`). Framework surfaces check atoms directly and never
  deployment-declared policies (or roles): the `ops.batch.*` blocks in a deployment's
  `tesseraql.security.policies` stop being read, and the identity pack's sample rows seed
  `tql.ops.view.*` instead of `ops.app.*`. Pre-1.0, no migration steps: grants named in the
  old vocabulary simply no longer match anything.

- **An application's name can no longer contain dots, and `tql` is reserved**
  (TQL-YAML-1405, widened; docs/stack-shells.md). An atom parses by splitting on dots —
  `tql.<family>.<verb>.<name>` — so a dotted name would be ambiguous against a
  name-plus-verb, and `tql` is the framework's mark, reserved exactly as `/_tesseraql/`
  fences the URL space. Non-ASCII names stay legal; `ops` and `studio` stay legal names.

- **The operations console is the stack's, at the origin scope** (docs/stack-shells.md
  structural decision 2; stack-architecture Decision 14, reversing app-isolation-model
  Decision 4). `ops-console` stops mounting into hosted members — a topology rule, not a
  preference; a member declaring it enabled still gets no local copy — and mounts into the
  stack surface runtime at `/_tesseraql/ops/console`. Its sidebar is an application switcher
  (the caller's `tql.ops.view.<name>` atoms applied to the member list, deny by default,
  empty without grants), a staged canary shows as a second entry (`orders (canary)`,
  `?slot=canary`) whose pages answer from the canary runtime's own ring, and the fan-out
  overview renders one card per member under a short timeout — an unreachable member's card
  says so while the page renders (TQL-BATCH-5030 only when a selected member's own page
  cannot be answered). Every page and action delegates over loopback HTTP to the selected
  member's runtime with the caller's own session (the store is shared), and the member
  re-runs its own grant checks: authorization stays at the member, the shell adds reach, not
  authority. Members grow a browser-face delegation API under `/_tesseraql/ops/data/…`; the
  bearer JSON API is unchanged in address and shape. The unhosted boot (tests, embedding)
  keeps the console locally as a stack of one — same shell, one switcher entry. A member
  page's chrome links the console origin-absolute — the one origin-scope URL a member page
  carries.

### Added

- **`TQL-YAML-1406` — an application's permission codes carry its own name as their first
  segment** (docs/stack-shells.md structural decision 1). Every permission code an
  application's policies reference must begin with the application's name
  (`orders.approve`, not `approve`), and never with the framework's `tql.` mark — enforced
  at lint and at boot, so two applications cannot silently share one grant and no
  application can squat on the framework's vocabulary. Policy *ids* stay free (they never
  reach the identity store), role rules stay free (roles are the deployment's vocabulary),
  and grants are untouched — a deployment may hand any code, framework atoms included, to a
  role or a service client. The scaffolder's starter policies emit `<name>.read`/`<name>.write`.

### Removed

- **Independent hosting — `tesseraql host --mode isolated` — is gone, and with it host-header
  routing** (docs/stack-architecture.md Decision 12). It gave each application its own hostname and
  no shared session. A suite is defined by sharing an origin and a sign-in, and a mode that undid
  both was a second deployment shape to reason about, document and test; removing it is what lets
  development and production have one topology between them. An application that must not share a
  session with its neighbours gets its own suite.

  This removes something reachable and documented, not something unused — the design said otherwise
  until it was measured. Pre-1.0 there is no migration path to write, only the honest note that the
  mode existed and does not now.

  What goes with it: the `Mode` enum and `--mode`, `tesseraql.app.hosts` and the `hosts` field on a
  catalogue entry, the `Host`-header lookup on every request, and `TQL-APP-5003` (an isolated
  application declaring no hostname). Routing is one rule: `/apps/<appId>/`.

### Added

- **`tesseraql deploy` — an operator deploys one application with one command, and the stack
  never restarts** (docs/stack-architecture.md Decision 29, docs/runtime-replace.md). The verb
  is the deploy protocol's pen: a positional `.tqlapp` package replaces the member it names
  (`--canary --weight <n>` stages a ramp instead), and `weight`, `promote`, `rollback` and
  `status` subcommands drive the rest of the lifecycle `AppUpgrader` already carries.
  `--sha256` verifies the package before anything is written; `--wait` tails
  `.upgrade/<name>.status.json` until the running host reports the outcome, so a CI pipeline
  gets a synchronous exit code out of an asynchronous host — and without it, or with no host
  running at all, the state is written and the next host start converges to it. `--stack` is
  explicit like `host`'s, and must be an install root: a workspace of source trees has no
  version ledger and is refused (**TQL-UPGRADE-4092**) — it deploys by restarting the stack.

- **A running host converges to the install root's state — deploying is writing the files**
  (docs/stack-architecture.md Decision 29, docs/runtime-replace.md). `catalog.json` and
  `.upgrade/<name>.json` were already the protocol boot reads (a restart mid-canary has always
  "worked" because boot is a reconciliation); a `StackReconciler` now watches them and
  converges the running stack to the same function of the same files — a moved catalogue
  replaces, a catalogue moved onto the staged candidate's version promotes (nothing starts), a
  staged candidate appears at its written weight, a weight edit reaches the live roll, a
  cleared stage discards. Event-driven on one serialized thread, idempotent by construction,
  and failure does not loop: a refused candidate stays refused, recorded in
  `.upgrade/<name>.status.json` — the file the host alone writes (the CLI writes intent, the
  host writes outcome, neither touches the other's). Catalogue and upgrade-state writes are
  atomic moves now, so a concurrent read can be stale but never torn. Membership stays
  start-time: a name added or removed is the stack changing shape, logged as an owed stack
  deploy. The reconciler exists only where a catalogue does — a workspace of source trees
  keeps restart-to-deploy, and `dev`'s `--watch` is a different loop.

- **The host can replace one application's runtime while the stack serves**
  (docs/stack-architecture.md Decision 29, docs/runtime-replace.md). `MultiAppHost` gained the
  operation set the deploy machinery drives: `replace` (admission checks re-running the boot
  guards for the candidate, a ready probe, swap-then-drain), and the canary lifecycle live —
  `stageCanary`, `setCanaryWeight` (the weight used to be a start-time snapshot, so moving a
  canary's traffic share required restarting the whole stack), `promoteCanary` (nothing starts:
  the candidate runtime becomes the stable slot), `discardCanary`. A failed replace is a no-op:
  everything before the swap can only abandon the candidate, and the serving runtime is
  untouched. The gateway's relay resolves each member's port, catalogue entry and ingress-strip
  set per request, and closes the swap race by retrying once — only when no connection to the
  origin was ever established, because replaying a request the origin may have acted on is a
  worse defect than the 502 it would save. Nothing drives the operations in production yet; the
  reconciler and the `deploy` verb arrive in their own slices.

- **A retiring runtime asks its job runs to stop instead of only waiting for them**
  (docs/runtime-replace.md). At drain start — a replace or the runtime's own shutdown — every
  execution the process owns gets the cooperative stop request the batch platform already ships:
  a run between steps stops before the next one, a chunk step stops at its next committed
  checkpoint with real counts and an exact resume point, a run in its final step completes. The
  recorded reason names the deploy (or the shutdown), not a phantom operator action. The force
  timeout stays, unchanged, as the last resort for a run that ignores the flag.

### Fixed

- **The swap race's second leg: a 503 from a retiring runtime's suspended consumer is retried
  against the fresh lookup** (docs/runtime-replace.md, the swap-race correction). The relay's
  retry fired only when the connection was never established — but a retiring runtime's socket
  keeps accepting while its Camel consumer suspends, and the suspend gate answers 503 before
  any route runs, so a request in the swap window got a 503 the deploy minted (caught by the
  headline replace test on CI). The relay now retries a 503 once when the slot has moved away
  from the port that answered — an application's own 503, a lane at capacity or a readiness
  roll-up, comes from the port the slot still names and passes through — and only for bodyless
  requests, because a consumed body stream cannot be replayed.

- **Stopping a stack now drains it; it used to hard-kill it** (docs/runtime-replace.md, the
  stack's own stop). `tesseraql host` registered no shutdown hook — the deployment image's
  `CMD`, so every container stop cut in-flight work — and even a closed gateway shut its front
  before the runtimes drained. `host` now registers the hook `dev` always had, and the
  gateway's close is an ordered drain: readiness flips to 503 while liveness stays 200, every
  accepted request is served to completion under a bound derived from the members' own declared
  `tesseraql.shutdown.timeout`s (their maximum — no new knob), and only then does the front
  close and the runtimes drain under their own bounds. The platform's grace period
  (`terminationGracePeriodSeconds` and kin) must exceed that derived bound.

### Added

- **The origin root always redirects — to the portal by default, to `root.redirect`'s
  application by configuration** (docs/stack-architecture.md Decision 24, docs/root-portal.md).
  `/` answers 307 (temporary deliberately: a permanent redirect outlives the configuration edit
  that retires it) to `/_tesseraql/portal`, or to `/<name>` when `tesseraql-stack.yml` declares
  `root.redirect: <name>`. The first URL anyone types into a fresh stack now lands somewhere
  useful instead of a 404. Naming an application the stack does not hold refuses the start
  (`TQL-APP-4215`), validated against the full membership before `--app-name` narrowing — the
  file describes the stack, the flag filters a run.

- **The stack's portal: one screen listing the applications this principal may reach**
  (docs/stack-architecture.md Decision 24, docs/root-portal.md). `GET /_tesseraql/portal` on the
  stack surface runtime renders the members as links at their derived `/<name>` addresses,
  filtered with the relay's own tenant-entitlement semantics — a principal declaring no tenant
  is not checked, and per-principal application grants are deliberately not invented here (they
  belong to the ops-permission question slice 7 owns). Anonymous browser users meet the standard
  bounce to the stack's sign-in with a `next` that brings them back. The backing
  `portal.apps.list` provider registers only on the surface runtime — the host hands the member
  list to that runtime alone, so no member can see its siblings.

- **The stack hosts its own runtime at the origin scope — sign-in, the account surface, and the
  portal's home** (docs/stack-architecture.md Decision 24, docs/root-portal.md). `host` and `dev`
  start one framework-owned runtime beside the members, whose main application is the bundled
  `portal` application riding the stack's framework coordinate; the gateway forwards origin-scope
  `/_tesseraql/*` and `/assets/*` to it, so `/_tesseraql/login` now answers at the origin — the
  address a stack's sign-in belongs at — where it used to 404. The health pair stays the
  gateway's own answer, members keep their prefixes, and the per-application development
  surfaces (Studio, ops console, IAM Admin) deliberately do not mount at the origin yet. The
  application name `assets` is now refused (`TQL-YAML-1405`): the framework serves its asset
  bundle at `<scope>/assets` at every scope, and a member named after it would shadow the
  stack's own stylesheets.

- **The host migrates the framework's `security` schema once, and hosted runtimes validate
  instead of migrating** (docs/stack-architecture.md Decision 16, `TQL-APP-4214`). `security`
  is stack-wide, so N runtimes taking Flyway's lock on one history in turn was safe rather than
  correct. The host migrates before any runtime starts — on the stack's pool when
  `tesseraql-stack.yml` supplies one, otherwise through a migration-only pool on the coordinate
  the applications agree on — and a hosted runtime that finds the schema at any other version
  refuses to start. That refusal is the wrong-framework-datasource guard: pointed at a database
  the host never migrated, a runtime fails loudly at boot instead of producing a stack where
  signing in silently does not carry. It is also what will refuse a canary expecting a newer
  framework schema than the host migrated. Standalone starts (`serve`, embedders) keep
  migrating both components themselves.

- **A stack declares its own settings in `tesseraql-stack.yml`, beside its applications**
  (docs/stack-architecture.md Decision 22). Always `<stack dir>/tesseraql-stack.yml`, loaded
  through the same configuration machinery applications use (`${ENV_VAR:default}` and
  `${secret.…}` resolve). What belongs there is what fails silently when it diverges:
  `framework.datasource` — the host builds **one pool** and every application's framework state
  rides it, so one sign-in carries by construction — and `externalOrigin`; the gateway's
  `--port`/`--http2`/`--trusted-proxies` stay flags because a wrong value there fails loudly.
  Two guards, both start-time refusals: with no stack-supplied framework datasource and more
  than one application, disagreeing per-application framework coordinates refuse the start
  naming each application (`TQL-APP-4211` — absence is a check, not a silence; the comparison is
  exact strings, so a `localhost`/`127.0.0.1` pair refuses too, loudly and one edit from fixed);
  an application *explicitly* declaring `tesseraql.framework.datasource` while the stack
  supplies the connection is refused (`TQL-APP-4212`) rather than silently repointed.

- **`tesseraql new` writes `tesseraql-stack.yml` beside the application it creates, and its
  flag is `--stack`** (was `--dir` — it always named the parent, which is by definition a
  directory holding applications). The generated file is all guidance comments, which is
  enough: it is also the stack's **marker**, and discovery's one-level step up now keys on it —
  the parent of an application home qualifies as the stack when it carries
  `tesseraql-stack.yml` or `catalog.json`, replacing the repository-boundary fence. A marker is
  affirmative intent the way cargo's `[workspace]` is; shape alone could not distinguish a
  stack from whatever directory happens to hold the checkout. A hand-made stack directory that
  predates the marker refuses discovery from inside an application until the file is touched —
  the refusal prints the fix — and the marker is read in the parent only, never from inside an
  application home.

### Added

- **`tesseraql host` runs modules, per application** (docs/stack-architecture.md Decision 28,
  docs/module-scope.md). Each hosted runtime loads its own `work/modules` on its own
  classloader: expression functions, file codecs, and blob-store providers are visible exactly
  to the application that declared them, and two applications can carry the same module — or
  the same function name — with different versions or semantics. Production hosts boot offline
  from what resolution left on disk; the new refusals **TQL-APP-4216** (declared modules,
  nothing resolved) and **TQL-APP-4217** (`work/modules` disagrees with `modules.lock`) replace
  what used to be running an application silently without its declared modules.
  `tesseraql modules resolve` gained `--stack <dir>` to resolve every member in one command.

- **A pool binds the driver its application's modules define** (docs/module-scope.md).
  `DataSources` used to set only the JDBC URL, so Hikari fell back to `DriverManager` —
  JVM-global and first-wins per URL, which arbitrated between two applications declaring the
  same driver at different versions. A driver defined by the application's own module loader
  that accepts the pool's URL is now bound to the pool directly (per-tenant pools included),
  carrying the pool's datasource properties; base-classpath drivers and the stack's framework
  pool keep the existing path unchanged.

### Changed

- **The MCP dev-tool server answers every tool from the named application's own modules**
  (docs/module-scope.md). `tesseraql mcp` used to compose every member's modules onto one
  classloader, so `lint` accepted a function only a neighbour declared and `test` ran with the
  union's semantics; each application now gets its own loader and function set — lint, tests,
  Studio drafts, and the schema/ops connections included, with module-defined JDBC drivers
  bound per application instead of through the process-global registry.

- **`dev` no longer composes every member's modules onto one classloader.** The union loader
  made every runtime see every neighbour's extension jars and let the last-loaded application's
  functions answer for all of them; each member runtime now builds its own loader over its own
  `work/modules`, and `--modules <dir>` remains what it always was — a development override
  composed onto every member runtime, an override rather than a declaration. The PDF engine
  lookup now resolves inside the `tesseraql-pdf` module itself, so PDF export works without the
  removed thread-context classloader.

- **An expression evaluates with the custom functions it was parsed under**
  (docs/stack-architecture.md Decision 28, docs/module-scope.md). The function set is a value:
  the parser resolves each custom call against the set it is handed and the parsed tree captures
  the resolved function, so a later install or reset can no longer change — or break — what an
  already-parsed expression means. The "no longer installed" evaluation failure is gone with the
  global-swap design that produced it. Single-application commands and the Maven goals are
  unchanged: they install the process default the parser falls back to.

- **CLI options come in sets, and a command takes the whole set or none of it**
  (docs/cli-surface.md Decisions 5 and 7). `--env` now exists on every command that loads a
  manifest — `lint`, the command whose missing profile flag was closest to a defect, included —
  and `--modules` on every command that parses routes (`routes` and `mcp` gained it). The
  connection set gained `--datasource <name>`, selecting which declared datasource backs the
  connection; that fixed two latent bugs where the flag keyed work while the connection stayed
  `main`: `schema --datasource reporting` introspected `main` labelled `reporting`, and
  `migrate --datasource reporting` ran the `reporting` migration set against the `main`
  database. A shape guard now keeps the set names (`--env`, `--modules`, `--jdbc-url`,
  `--username`, `--password`, `--datasource`) out of individual commands, with one named
  exemption: `token --password` is a sign-in password, not a database credential, and keeps its
  name.

- **The single-application commands refuse a directory of applications, naming each**
  (docs/cli-surface.md Decision 2). `package`, `scaffold crud`/`decision`/`eject-view`,
  `release-diff` — `--baseline` included, because a folder of applications diffed silently
  reports every route as added — and `verify` resolve `--app` through the same shape check the
  running commands use. A folder of applications or an empty directory is refused with the
  commands that would have worked printed, instead of the command operating on the directory as
  if it were one application: a refusal that names the alternatives costs a second, one that
  says "expected a single application" costs a directory listing and a guess.

- **`tesseraql mcp` serves one server for the stack** (docs/stack-architecture.md Decision 19).
  `--app` is gone: `--stack` names the directory holding the applications and is discovered one
  level up when omitted, exactly as `dev` resolves it, and `--app-name` narrows without changing
  anything else. Every development tool and the `studio_copilot` prompt carry an `application`
  argument naming which stack member the call operates on — required when the server spans
  several, defaulted when it holds one — because an agent that guesses which application it is
  editing is worse than one that has to be told. `--read-only` is a property of the server,
  never of one application. The HTTP transport's bearer check now requires the stack's members
  to agree on `tesseraql.security.jwt` — the server has one gate, and one that verified each
  request against whichever member it happened to pick would accept a token another member
  rejects — refusing with the fix named when they disagree. The VS Code extension's *Register
  MCP Server* command writes `args: ["mcp"]` accordingly.

- **`tesseraql serve` is `tesseraql dev`, and it runs the stack** (docs/cli-surface.md
  Decision 4). `serve` was the gateway-less single-application shape — the second deployment
  topology Decision 12 removed; development now runs exactly what production runs: every
  application the stack holds, behind one gateway, one origin, one sign-in.
  `cd <app> && tesseraql dev` works with nothing named (the stack is discovered one level up
  by its marker); `--stack` names it explicitly and `--app-name` narrows without moving the
  member's address. `--port` is the gateway's front door (default 8080).
  `--watch`/`--modules`/`--env`/`--log-*` carry over; `--watch` now watches every hosted
  application. **`--embedded-db` supplies the framework datasource** — one server, one shared
  database started by the CLI, so it is derived from no application and one sign-in carries —
  while each application's `main` pool points at it carrying the application's **own declared
  query string**, so `currentSchema` isolation stays in the application's URL (Decision 4b).
  The explicit-declaration refusal `TQL-APP-4212` deliberately does not fire for
  `--embedded-db`: "override everything" must not be the one place an override is refused.
  `dev` also defaults the stack's `externalOrigin` to `http://localhost:<port>` when the stack
  file declares none — the development gateway knows its own address; a production host never
  guesses it. The VS Code extension's terminal verb and command follow (`tesseraql.dev`).

- **A hosted application's declared `server.port` is honoured as its internal port**
  (docs/cli-surface.md Decision 4a). The host used to bind every runtime to an ephemeral port
  unconditionally; now `server.port` keeps its one meaning — the port this application binds —
  behind the gateway's `--port` front door. `0` and absence both stay ephemeral, the canary
  slot always takes an ephemeral port (the candidate runs beside the stable version holding
  the declared one), and two applications declaring the same port fail loudly at bind.

- **An application's address is its name — `/orders`, not `/apps/orders` — and it is derived,
  always** (docs/stack-architecture.md Decision 25). The `/apps/` wrapper defended nothing the
  name grammar does not already defend (`TQL-YAML-1405`: no leading `_`, so `/_tesseraql/*` is
  unreachable by any name; no leading `.`, so the dotted root names are; no `/`), and
  `/orders/invoice/123` is the address a person would have guessed. **`basePath` left
  `catalog.json`**: what remained of a declarable address was the vanity rename, and a renamed
  address breaks every neighbour's absolute links — so a catalogue still declaring one is refused
  loudly rather than quietly re-addressed, and installing a new version of an application cannot
  change where it answers, because nothing can. The deployment's root choice is the root
  redirect (Decision 24, a later slice), not an address override. `InstalledApp.basePath()` is
  now the one producer of an address: `/<name>`.

- **An application's identity is its `name`, and `id` is gone as a synonym** (docs/cli-surface.md
  defect 1: two names for what looks like one thing). The two were the same string by
  construction — the catalogue's `id` was read from `tesseraql.app.name` — so `InstalledApp.id`
  is now `name`, `MultiAppGateway`/`MultiAppHost.appIds()` are `appNames()`, and **`catalog.json`
  spells the field `"name"`** (pre-1.0 format change: a catalogue written with `"id"` is refused
  with a message naming the rename — rewrite the key). The `release-evidence` goal's parameter is
  `tesseraql.appName` (was `tesseraql.appId`) and now defaults to the application's own declared
  `tesseraql.app.name` instead of a Maven coordinate no other surface checks against; the
  evidence JSON records `app.name`. Naming note: `migrate --app-name` was deleted earlier by the
  migration-history-key fix — that flag hand-corrected a derived history key, a different meaning
  from both this rename and `host --app-name`.

- **Installing over an installed name is refused, and the catalogue's `register` no longer
  silently replaces** (docs/stack-architecture.md Decision 23, `TQL-APP-4213`). The name is the
  stack's contract — what the deployment addresses, entitles and grants against — and the second
  install winning was how a stack lost an application without anyone deleting it. Re-installing
  the identical version stays idempotent; moving a name between versions is the upgrade
  lifecycle's job (`AppUpgrader`), which replaces explicitly after its preflight.

- **`tesseraql.app.name` must be a safe URL path segment** (docs/stack-architecture.md
  Decision 25, `TQL-YAML-1405`, refused at lint and at boot). The name becomes the application's
  address, so it must be one segment: no `/`, no leading `_` (the framework's own fence,
  `/_tesseraql/`), no leading `.`. The rule is segment safety, not an ASCII pattern — non-ASCII
  names stay legal, which is why the migration-history guard measures them in UTF-8 bytes.

- **The deployment unit is a *stack*, and `tesseraql host` takes `--stack` — `--suite` and
  `--app` are gone from it** (docs/stack-architecture.md, the flag reversal; docs/cli-surface.md
  Decisions 1–3 rewritten). "Suite" already means a declarative test file in this product —
  `glossary.md` defines it that way — and a set of applications deployed as one unit is a stack
  everywhere else; one word cannot carry both. `--stack <dir>` names a directory that **holds**
  applications (an install root, or a folder of application homes), and an application home is
  refused with the narrowing that would have worked: `--stack <parent> --app-name <name>`.

  `--app` is off the running commands because it made one application answer at **two
  addresses** — the origin root when served alone, `/apps/<name>` as a stack member — so
  developing with one flag and deploying with the other changed every URL the application
  emits, which is exactly the divergence Decision 12 exists to remove. `--app-name` narrows a
  stack to one member **without moving it**: a filter, never a second deployment shape. The
  `--app` flag keeps its unchanged meaning on the commands that operate *on* one application
  (`lint`, `migrate`, `package`, …). Internally `SuiteRelay` is now `StackRelay`, and
  `HostContext.suite()` is `HostContext.stack()`.

- **`tesseraql host --install-root` is now `--suite`, and `--app` serves one application**
  (docs/cli-surface.md). The old name described an implementation detail — a directory with a
  catalogue in it — where the new one describes what the operator is running. `--suite` accepts
  either shape: an install root with `catalog.json`, or **a folder of application homes with no
  catalogue at all**, which is how a suite runs from source trees without being packaged first.
  They host identically; entries are synthesised from each application's own configuration, keyed
  by the `tesseraql.app.name` that is now required.

  Pointing a flag at the wrong shape is refused rather than guessed: `--app` on a folder of three
  prints the three commands that would have worked, and `--suite` on a single application asks
  whether `--app` was meant.

### Fixed

- **Migrating from the CLI or the build wrote a history the runtime then ignored.** The Flyway
  history table is `tql_schema_history_<name>`, and three entry points derived *name* three
  different ways: the runtime from `tesseraql.app.name`, `tesseraql migrate` from the **application
  directory name**, and the `tesseraql:migrate` Maven goal from **`${project.artifactId}`**. Across
  the bundled examples the directory name and the application name never agree — `helpdesk-app`
  against `helpdesk` — so `tesseraql migrate` recorded into one table and the runtime, finding its
  own empty, re-ran every migration on the next start.

  All three now read `tesseraql.app.name` from the application, so they converge by construction
  rather than by the operator remembering a flag. `tesseraql.migrations.historyName` overrides it
  where the derived name does not fit.

- **`tesseraql.app.name` is required; it no longer defaults to the literal `app`.** The name reads
  as a label and is an **identity**: it scopes outbox claims and cluster job claim keys, it is the
  owner recorded against every job execution and so what `ops.app.<name>` grants are checked
  against, it names the MCP server, and under a suite it is the application's address
  (`/apps/<name>/`). Defaulting it made the value *required to deploy and optional to run* —
  `AppInstaller` refuses a package without one — and nothing collided only because that requirement
  kept unnamed applications to one at a time. An application declaring none is now refused
  (`TQL-YAML-1404`) instead of sharing an identity with every other unnamed one.

  The linter reports it too, so a missing name is a lint error before it is a failed boot; both
  carry the same sentence. `tesseraql.otel.serviceName` is unaffected — a display label with its own
  override, where falling back to a shared value costs nothing.

  The five bundled framework surfaces now declare their names as well. They are identified by their
  mount rather than by their configuration, so nothing about them changes at runtime; declaring it
  keeps them honest about themselves and lets the rule require the key of every application without
  an exemption.

- **An overlong history table name was silently truncated by the database.** Nothing checked the
  length anywhere. `tql_schema_history_` is 19 characters against PostgreSQL's 63-**byte** limit, a
  Japanese name costs three bytes a character, and a named datasource appends `__<name>` on top —
  and a character count does not catch it: a 49-character name is 109 bytes, which PostgreSQL stored
  as 61 with no error. Two applications truncating to the same name shared one history, each reading
  the other's applied versions. The name is now refused with `TQL-APP-4208`, which names
  `tesseraql.migrations.historyName` as the fix.

### Removed

- **`tesseraql migrate --app-name` and the `tesseraql:migrate` goal's `tesseraql.appName`.** Both
  existed to hand-correct the disagreement above. With the value read from the application they can
  only reintroduce it, by writing history under a key the runtime will never read.

### Added

- **A path to a bearer token that a person can follow** (docs/authentication.md). The exchange
  endpoint was correct and unreachable: it requires the session's CSRF token, and that value left
  the server only inside a page, as `<meta name="csrf-token">`. So a command-line client could
  authenticate and then had nowhere to go, and a human's only route was reading a cookie and a meta
  tag out of browser developer tools. Three additions close it, none of which changes what a token
  is or what it carries:
  - a JSON login now answers with `csrfToken` beside `ok` and `loginId`. This grants no new
    capability — the same value already reaches any authenticated browser through the meta tag, and
    a hostile page still cannot read a cross-origin response body;
  - **`tesseraql token --url <base-url> --login <id>`** signs in and exchanges in one step, printing
    only the token on stdout so it pipes. Password from `--password`, `TESSERAQL_PASSWORD`, or a
    prompt; `--tenant` and `--otp` where the realm needs them. Nothing is cached. Claim and lifetime
    options are refused rather than ignored, because the application decides both;
  - **`/_tesseraql/ops/console/token`** issues a token and offers it for copying, and says which
    configuration key to set when the application does not issue. It needs no operations grant: a
    token carries the caller's own authority and nobody else's.

  The page and the endpoint mint through one implementation, so their claims cannot drift, and the
  page reaches it bound to the ambient principal — the one the request binder seeds from the
  authenticated exchange — so no route can ask it for somebody else's token.

- **`tesseraql host --http2`** (docs/hosting.md). Serves and forwards cleartext HTTP/2. One switch
  moves both hops — the client's connection to the gateway and the gateway's connection to each
  application — because enabling it at one end alone breaks request framing. An application that
  does not offer h2c answers the upgrade over HTTP/1.1 and is reached exactly as before, and an
  HTTP/1.1 client still reaches an h2c gateway: the upgrade is offered, not required. Off by
  default, because the front this replaced spoke HTTP/1.1 only.
  Getting there needed one fix that is worth naming, because it presents as something else
  entirely: over HTTP/2 a `GET` still ends its stream with a data event, so the relay saw a body of
  unknown length and produced an outbound request with neither a declared length nor chunked
  framing. Vert.x refused the write on the event loop while the request itself succeeded, so the
  only symptom was a stack trace per request — every page load and every event stream, in the log,
  forever. An unknown length on a method that cannot carry a body is zero.

- **`tesseraql host --trusted-proxies`** (docs/hosting.md). Names the addresses whose forwarded
  headers come from the deployment's edge rather than from a caller; an application's
  `mtls.forwardedHeader` is then stripped from every request arriving from anywhere else. The
  comparison is against the peer of the connection, never a header — a caller can write
  `X-Forwarded-For` and cannot write the socket it connected from. CIDR blocks and bare addresses,
  IPv4 and IPv6. Empty by default, which strips nothing: reading "no edge named" as "strip from
  everyone" is the unconditional strip that made mTLS forwarded-header authentication unusable
  behind a gateway, and it would be the default again. This is defence in depth on top of the trust
  contract in docs/authentication.md, not a replacement for it.

- **`response.json.headers:` and `response.json.headersWhen:`** (docs/response-shaping.md). The
  block existed on `response.html` only; on a JSON route it deserialized away at runtime, so a
  declared header simply did not arrive. (The linter did report it — `TQL-YAML-1043` walks every
  nested shape and knew the key was not accepted — so an author who ran the linter was told and one
  who did not was not.) Values interpolate `{expression}` placeholders against the execution context
  exactly as the HTML block does, and the guard-and-interpolate logic is now one shared
  implementation rather than a second copy.
  The guards earn their place on JSON through one case in particular: a JSON response can already
  vary its status per request through `statusWhen:`, so the headers *defined in terms of that
  status* — `Location` on a 201, `Retry-After` on a 429 or 503, `WWW-Authenticate` on a 401 — have
  to vary with it. Anything else conditional belongs in the body a client parses anyway.

- **A prompt can be a recipe, so it can read data** (docs/prompt-as-recipe.md, slice 1). An
  `mcp/` prompt document that declares `recipe: prompt-text` is a route like its three siblings:
  it compiles to `direct:mcp.prompt.<id>` through the head every recipe gets, binds its `input:`,
  enforces its own `security:`, runs its `sources:`, and renders the message from a new `text:`
  response arm — so "draft a welcome for customer 4711" can look 4711 up instead of needing a
  tool that pretends to be a prompt. `prompts/get` is a read, so a command step is refused
  (`TQL-CAMEL-3116`), as is a document with nothing to render (`TQL-CAMEL-3117`).
- **`response.text:`, for when the rendered text is the answer.** It is `response.file:` without
  `filename:` and `contentType:`, which a message has nowhere to put: the same Thymeleaf TEXT
  rendering against the same kind of model, served as the body rather than as a download.
- **A prompt reaches its route through the sender every other MCP primitive uses.** `read` and
  `invoke` in the MCP endpoint were two copies of one send that agreed on everything except what
  they sent and how they wrapped the answer; they are now one `call`, wrapped three ways — a tool
  as a tool result, a resource as its body, a prompt as one `user` message. A served prompt
  therefore carries the caller's `Authorization` to its route, which is what makes a prompt's
  `security:` mean anything.

- **A prompt argument declares only what a prompt can act on** (docs/prompt-as-recipe.md,
  slice 3). An argument is a full route `input:` field, and most of that field is live on a
  prompt — the binder coerces and validates by `type:`, the bounds, `pattern:`, `enum:`,
  `format:`, `codes:` and `requiredWhen:`; `description:` is what `prompts/list` advertises;
  `classification:`/`mask:` keep the value out of the route audit trail. Three keys have nothing
  to act on it, and are now refused instead of accepted in silence (`TQL-MCP-1015`): `widget:`,
  because a prompt renders a message rather than a form, and `policy:`/`writable:`, because the
  mass-assignment guard asks whether the request may supply a field on a surface where the
  request is the only thing that can — so it can only refuse the caller. A prompt is gated by
  its own `security.policy:`. A key a shared `domain:` supplies is not refused: the author did
  not write it here, and a domain has to stay usable from every surface that references it.
- **Lint says the prompt read-only rule at build time** (`TQL-MCP-1016`). The compiler already
  refused a prompt with command steps at startup; the same refusal now lands where the other
  recipes' shape rules do, before compilation, and covers the case the compiler missed — a
  source declared in `mode: update`, which is a write however it is spelled.
- **An `mcp-prompt` coverage kind.** A prompt is a route with SQL behind it now, so the report
  counts it beside `mcp`, `mcp-resource` and `mcp-ui`: every prompt that reads is declared, and
  covered when a declarative suite exercises its SQL. A prompt that renders from its arguments
  alone executes nothing a case could exercise, so it is not declared — an item nothing can
  cover is a gate nobody can pass, not a gap worth showing.

### Fixed

- **mTLS forwarded-header authentication could not work behind the multi-app gateway.** The gateway
  stripped the header each application declares in `tesseraql.security.mtls.forwardedHeader`, to stop
  a caller supplying the assertion the application is configured to believe. It stripped
  unconditionally — there is no trusted-proxy concept in the tree — so the value the *edge* had just
  set was destroyed with it, and the application saw no certificate at all. `MtlsIntegrationTest`
  never goes through a gateway, so nothing caught it. The trust contract stays where
  docs/authentication.md already puts it and where it can actually be discharged: the edge overwrites
  the header on every inbound request, and the runtime is not reachable except through the edge.
  Under docs/stack-architecture.md decision 12 this stops being "unavailable in one hosting mode" and
  becomes the whole feature, which is why it is fixed here rather than deferred.
- **The multi-app gateway dropped the body of every chunked response** (docs/stack-architecture.md
  decision 13, slice 1). `com.sun.net.httpserver` reads a response length of `0` as "chunked,
  length unknown" and `-1` as "no response body"; the relay computed `-1` for an application that
  declared no `Content-Length` and passed it through verbatim. Streaming exports and event streams
  answered 200, with the right headers, and nothing after them. Found by the measurement the design
  asked for before implementation, which had predicted only the second defect beside it: the copy
  loop never flushed, so a corrected length still delivered every event at once when the stream
  closed. A third sat with them — the outbound client negotiated h2c with the application and the
  response headers were copied into an HTTP/1.1 answer unchanged, putting the HTTP/2 `:status`
  pseudo-header on the wire as an HTTP/1.1 field name.
- **A suite-hosted application could not be sent more than 10 MB, or send back more than 64 MB.**
  The gateway buffered every request body into a `byte[]` and refused past 10 MB, and aborted a
  response mid-body past 64 MB — after the status was already sent, so the truncation was
  undetectable to the client, which is the precise failure the export pipeline exists to avoid. The
  gateway fronts applications the operator installed, behind whatever ingress the deployment already
  runs; body limits belong there and to each application's own declarations.

- **`deploy/Dockerfile` could not be built.** The builder stage ran `package`, so the following
  `dependency:copy-dependencies` could not resolve the reactor's own modules and the build failed
  on the `io.tesseraql:*` coordinates. `Dockerfile.demo` had already hit this and moved to
  `install`; the image that `deployment.md` documents as the default shipping pattern had not,
  and nothing in CI built it. It is built and exercised by CI now.
- **The deployment images depended on what the building machine had lying around.** There was no
  `.dockerignore`, so local `target/` trees entered the build context and
  `COPY target/tesseraql-cli-*.jar` matched both the thin jar and a stale shaded one, putting two
  copies of the framework on one classpath.

### Removed

- **BREAKING: the Spring runtime adapter** (docs/jvm-baseline.md, decision 1).
  `tesseraql-camel-spring-runtime` was two classes — a `@Configuration` that started the runtime
  from a Spring `Environment` and an Actuator `HealthIndicator` — with no documentation page and
  no caller, left over from a shape the framework has since decided against: one runtime serves
  one application, and every documented way to ship one has TesseraQL owning the process. The
  same health roll-up stays reachable at `/_tesseraql/health/live` and `/_tesseraql/health/ready`,
  which is what the container `HEALTHCHECK` already probes. Spring Framework and Spring Boot are
  no longer version-managed by the parent POM.
- **The `tesseraql.app.home` configuration key**, which existed only so the Spring adapter could
  locate the app home. Nothing in any `src/main` tree read it once the adapter was gone, so the
  scaffold stops emitting it rather than leaving the emitted-but-dead key that
  docs/config-consumers.md exists to prevent. `${TESSERAQL_APP_HOME}` remains available as a
  placeholder inside configuration values — the manifest loader supplies it — so the scaffolded
  `tesseraql.app.work` default is unchanged.

### Changed

- **A response header that cannot be serialized reports one code.** An HTML response reported
  `TQL-TPL-2001` for it — the template-render code, which happened to be the constant in scope —
  and a JSON response had no such failure to report because it carried no headers. Both now report
  `TQL-CAMEL-3001`, the response-render code, which is what the failure is: nothing about the
  template resolved wrongly.

- **The app-wide default response headers reach JSON responses** (docs/route-defaults.md). They
  merged into HTML responses only, on the reading that `security.responseHeaders` is
  browser-document machinery. That is true of three of the four — `Content-Security-Policy`,
  `X-Frame-Options` and `Referrer-Policy` govern a document and are inert on a response no browser
  renders as one — and backwards for the fourth: `X-Content-Type-Options: nosniff` exists precisely
  to stop a browser treating a non-document as a document, so the response most at risk of being
  sniffed was the one kind it never reached. The whole block merges rather than a classified subset,
  because classifying would mean reading header names to guess which are document-scoped, and an
  operator adding a header of their own could not predict the answer. `TQL-SEC-4133`/`4134` (a route
  restating or weakening a default) now inspect JSON routes too, which they had to once the defaults
  reached them. Two documentation claims are corrected with it: `route-defaults.md` said the
  defaults merged into "every HTML, file, and stream response", and file and stream responses carry
  no `headers:` map at all.

- **The gateway relays through `vertx-http-proxy` rather than a copy loop of its own**
  (docs/stack-architecture.md decision 13). Three transparency defects in roughly forty lines, in
  the component every request in every deployment passes through, is the argument: framing,
  flushing and protocol translation are what a proxy library exists to have already solved. Vert.x
  is already a compile dependency through `camel-platform-http-vertx`, so this adds one jar at a
  version the runtime already resolves, and both are now pinned from one `vertx.version` property
  so they cannot drift apart. Two consequences worth stating: the hop-by-hop half of the gateway's
  header list is the library's now — asserted by an origin the test owns reporting that `te`,
  `trailer`, `connection`, `keep-alive` and `upgrade` never arrive, rather than assumed — and the
  front runs on an event loop instead of a virtual thread per request — nothing on that path may block, in exchange for a long-lived stream
  costing no thread at all rather than a parked one. The front stays HTTP/1.1, which is what
  `com.sun.net.httpserver` spoke, so no client's protocol changes.
- **Log lines carry their correlation on the exchange, not on the thread** (docs/jvm-baseline.md,
  decision 2, slice 5). Camel 4.19 deprecated the MDC logic the runtime used to carry `traceId`
  and `spanId` across async boundaries; the `camel-mdc` component replaces it, and it propagates
  through the exchange — which is why a step handed to an execution lane still logs under the
  request that started it, the thread having changed while the exchange did not. `traceId` and
  `spanId` keep their names, so existing log queries are unaffected, and every line gains Camel's
  own identifiers: `camel.routeId`, `camel.exchangeId`, `camel.messageId`, `camel.contextId` and
  `camel.threadId`.
- **The launchers and images ship JVM settings instead of documenting them**
  (docs/jvm-baseline.md, decision 4). Measured on JDK 25, a class-data-sharing archive takes 25%
  off `serve`'s time to ready and 20% off resident memory, and 20% off a short CLI command;
  `-XX:+UseCompactObjectHeaders` takes a further 7–9% off live heap. Both are applied by
  `bin/tesseraql`, the two deployment images and the jpackage app images, ahead of
  `TESSERAQL_JAVA_OPTS` so an operator can still override any of them — there is nothing new for
  a reader to configure. The launchers keep the archive in the user cache directory and skip it
  when that cannot be written; the images train it at build time against the baked app; the
  jpackage app images write theirs on first run, which costs ~25 MB of artifact for the base
  archive the jlink runtime does not ship and takes 25% off every run after the first.
- **JDK 25 no longer prints Netty's `sun.misc.Unsafe` warning at every start.** Three `WARNING`
  lines came from library code the framework does not own;
  `--sun-misc-unsafe-memory-access=allow` removes them and keeps Netty working when a future JDK
  denies that access by default.
- **BREAKING: Java 25 is the baseline** (docs/jvm-baseline.md, decision 3). The build targets
  `--release 25`, the enforcer requires `[25,)`, and CI runs one JVM instead of two. Every
  channel where TesseraQL supplies the JVM moves with it — the Dev Container, both deployment
  images, and the jpackage app images. The two places that ask a JDK of someone else now ask for
  25: the fat-jar launchers, and the Maven surface (`tesseraql-maven-plugin` on a consumer's CI).
  The reason is not Java 25 language features, which this codebase has no use for; it is that a
  framework which owns its process should have one JVM story rather than a compatibility matrix.
  1.x will be declared on this baseline.
- **Apache Camel 4.18.0 → 4.22.0** (docs/jvm-baseline.md, decision 2). 4.18 leaves support in
  February 2027 and claims Java 17 and 21; 4.22 runs to August 2027 and adds Java 25 — which the
  Java 25 CI job was already exercising against a Camel that did not claim to support it. Two
  improvements arrive with it: `CamelObjectInputStream` now installs a JEP 290 deserialization
  filter by default, and on JDK 25 Camel configures post-quantum hybrid named groups
  (`X25519MLKEM768`) on every `SSLContextParameters`. The MDC bridging the runtime uses to carry
  `traceId` / `spanId` across async boundaries is deprecated in favour of the `camel-mdc`
  component; it still works, and migrating it is its own slice because it changes what every log
  line carries.
- **The `camel-test` version override is gone.** It existed because `camel-bom` 4.18 managed the
  retired artifact at a `-SNAPSHOT` version, which Maven Central refuses in a published POM (the
  v0.7.0 publish failure). Camel 4.19 removed the entry from the BOM, so the workaround has
  nothing left to correct.
- **BREAKING: a lint code and a runtime code that share a number now have to mean the same
  thing.** The uniqueness guard counted the two idioms separately, on the reasoning that a lint
  and the runtime error it anticipates share a number on purpose — true for eighteen pairs, and
  the blind spot that let `TQL-YAML-1103` mean two things. Each shared number is now listed with
  the one meaning both report, and the four that could not be written as one meaning moved: an
  invalid `publish:` declaration is `TQL-FIELD-2010` (the message-key lint keeps `2005`), a local
  poll source with no `allowedPaths` root is `TQL-SEC-4093` (the SAML metadata host denial keeps
  `4086`), and the `config/menu.yml` and `config/flags.yml` document errors are `TQL-YAML-1110`
  and `TQL-YAML-1111` — they read configuration, so they belong in the configuration band rather
  than beside the document lints they collided with.
- **BREAKING: a code answers one question.** Four `TQL-YAML` numbers each answered two unrelated
  ones, because a rule written in June borrowed a number allocated two days earlier — nothing was
  documented under it yet, so it looked free, and the uniqueness guard did not exist. The older
  meaning keeps the number and the newer one moves: a job's trigger declaration is
  `TQL-YAML-1054` (was `1005`, which stays with an export option that cannot apply where it is
  declared); a poll job with no `import:` block is `TQL-YAML-1055` (was `1006`, which stays with
  an unusable export template); a webhook route with no `steps:` pipeline is `TQL-YAML-1056` (was
  `1008`, which stays with a translation gap); and an invalid `tesseraql.http.outbound`
  declaration is `TQL-YAML-1109` (was `1103`, which stays with a declared locale that has no
  catalog). Two more meanings moved to say what they are: an export source declaring
  `onError: empty` is `TQL-YAML-1057` (was `1006`), and a `webhook:` block on a non-webhook
  recipe is `TQL-YAML-1010`, the code `notify:` and `publish:` already raise for exactly that
  mistake. The export block's own codes now split by what the author does about them —
  `TQL-YAML-1041` when a piece is missing, `1005` when a declared key cannot apply, `1006` when
  the template is unusable — so `startCell:` without a template no longer reports as `1041` on a
  job step and `1005` on a route.
- **Every sortable grid reads one header contract.** The rule an hc-datagrid header renders from —
  `aria-sort` of `ascending`/`descending`/`none`, and a link that flips the active column while
  starting any other column ascending — was written out three times: the studio's route catalog
  and schema tables, its audit trail, and a declared `view:` list. They are now one `SortState`,
  which also settles the two questions the copies answered differently. A `sort=` naming a column
  the grid cannot sort by falls back to the grid's default everywhere (a stale link no longer
  leaves a view's headers all inactive), and a stated `dir=` is honored even when the request
  names no column — the audit trail used to ignore it and open newest-first anyway, so the link
  the page had just rendered did nothing. The studio's data browser keeps its own sort: it is a
  filter form choosing a SQL `ORDER BY`, not a header a reader clicks.
- **`kind: prompt` is a route document, full stop** (docs/prompt-as-recipe.md, slice 2). A prompt
  declares `recipe: prompt-text`; a document without one no longer loads, and says so the way
  every other family says it — `Missing required field 'recipe'`. The second parse path is gone
  with it: `PromptDefinition` and its `Argument`, `SimpleYamlParser.parsePrompt`, the loader's
  raw-tree reading, and the root-level `template:` key it read. A prompt renders its message from
  `response.text:` like the recipe it now is, and `PromptFile` carries the route definition the
  way `ToolFile` does. What follows for free is everything that attaches to a route: an argument
  is an `InputField`, so its `type:` is coerced and validated rather than documented and ignored;
  the unknown-key walk checks a prompt against the route model at every depth; and `security:`
  and `sources:` are its keys.
- **An input field declares `description:`, and both MCP surfaces carry it.** An MCP prompt
  argument is name/description/required and a tool's `inputSchema` is JSON Schema, whose
  `description` is the hint a model follows when it chooses a value — and neither had one to
  carry, because `input:` had nowhere to write it. It is one key on `InputField`, so a prompt's
  arguments, a tool's input schema and a route's emitted OpenAPI all advertise it, a field domain
  can declare it once for every field that references the domain, and the argument `type:` that
  was registered as a known-dead component is retired along with the record that carried it.
- **Two apps may not claim one prompt name.** Every app's MCP surface is served from one
  `/_tesseraql/mcp` endpoint, so the startup conflict check covers prompt names and
  `mcp.prompt.<id>` route ids alongside tool names and resource uris. A tool and a prompt may
  still share a name — they are separate namespaces, as the within-app lint already said.
- **A lint severity is an enum and a lint code is a constant.** The linter's ~360 findings
  spelled their severity as the string `"error"` or `"warning"` and their code as a string
  literal, so a typo in either was a finding nobody could filter and a number two rule families
  could quietly share. A severity is now `LintFinding.Severity`, and every code is a constant on
  the family that raises it — or in `LintCodes` when several families raise it, because one home
  is what stops a number from drifting into two meanings. Nothing outside the linter changes
  shape: `severity` is still serialized as the same string the CLI's `lint --format json`, the
  MCP dev-tools, the ops and Studio pages and the VS Code extension have always read. The
  uniqueness guard that could only see declared constants now sees lint codes too, and refuses a
  code raised as a bare literal.
- **`TQL-FIELD-2004` answers one question again.** It reported both "what is this step's work?"
  — no work at all, two bindings, a `chunk:` beside a binding — and a command step declaring
  `enrich:`, so the published reference merged two rules into one row and a build failure named
  a number that documented something else. The step-shape question keeps the number it is
  documented under; a command step declaring `enrich:` is now `TQL-FIELD-2009`, documented with
  the other command-time codes in [transactional-writes.md](docs/transactional-writes.md).
- **`confirmApply` gates every apply, not only the editor's.** The JSON `studio.apply` API was
  exempt from the review-before-apply gate, which made the policy a suggestion for exactly the
  callers most likely to promote drafts unattended. The API now requires `confirm=true` (or
  `force=true`, which also acknowledges a conflict) when the gate is on — the same contract the
  editor's compare panel enforces.
- **A job step's blocks must form one executable unit.** The lint said "a step may declare one
  of each" while the executor dispatches exactly one unit per step — so `sql:` beside `notify:`
  built and then failed on its first firing, and `sql:` beside `push:`, an `http:` arm beside
  `export:`, or a second output block were dropped in silence. All are now `TQL-FIELD-2008` at
  build time; the plain `sql:` arm feeding `export:` its extraction stays the one designed
  pair.
- **The ops console and the ops API list the same recent-event window.** The console's outbox
  and queue-event pages showed the most recent 100 rows while the JSON API returned 200 — a
  drift, not a decision. The two faces now share one handler core, and both list 200.

- **A block whose shape is fixed says so, and refuses a key it does not have.** `export:`,
  `import:`, `outbox:`, `errors:`, and a pipeline step's `notify:`, `chunk:` and `push:` were
  `additionalProperties: true` with no properties at all — a schema that describes nothing and
  validates nothing — and the unknown-key lint stopped at a document's own keys. So an
  `export.sql:` was dropped in silence, and the export wrote an empty file. All of them now carry
  their real keys, closed to the rest, and the lint checks them the way it checks the document,
  a pipeline step's own keys included: an unknown key is `TQL-YAML-1043`, and one the unified
  source model moved is `TQL-YAML-1044` naming where it went (`export.sql:` → `sources:`,
  `import.sql:` → `steps:`).
- **One shape, one home, for the shapes a step and a route share.** A notification, a push
  target, a chunk loop, an enrichment and a file-transfer column are shared definitions now,
  referenced from the four places that used to describe them separately or not at all. A
  step's blocks and a route's `notify:` entries are the same declarations, and the published
  reference documents each once.
- **The YAML surface reference documents a pipeline step.** It rendered as `array of any`,
  because a step is composed with `allOf` — the binding's arms plus the step's own keys — and
  the generator followed `$ref` and `items` but not `allOf`. The most important shape on a job
  document was the one the page did not show. A `$ref`-only property also takes its description
  from the target rather than rendering an em dash.
- **A batch step's rows answer to one key — the same label a route's rows carry.** The batch
  executor was the last JDBC reader that never asked the shared label normalizer: on Oracle a
  `mode: query` step published UPPERCASE keys into the step context, the chunk reader
  compensated by doubling every row with lowercase-alias keys, and the checkpoint key probed
  three casings. All three batch readers (`query`, `query-spool`, `chunk:`) now normalize
  labels the one way routes and commands do, and the compensations are gone — so
  `steps.<id>.rows` keys, `row.*` binds and `chunk.key` name the same label on every dialect.
  On Oracle, the case of the keys a later step sees changes (an unquoted label comes back
  lowercase, as it does everywhere else); on lowercase-normalizing dialects rows are unchanged,
  except that a quoted mixed-case alias no longer drags a lowercase duplicate along with it.
- **A SQL `query-spool` extract keeps its types through the spool.** The batch spool wrote
  JSONL while the export spool's own javadoc rejects JSON as lossy exactly where it matters —
  a decimal's scale, a temporal's type — and the chunk reader shrugged the mismatch off as a
  documented caveat: writer binds had to cast in SQL what the round trip had flattened. A SQL
  extract now drains into the export pipeline's tagged-binary row spool, so a chunk writer
  binds what the extract read; a value the encoding cannot carry fails with the column named
  instead of degrading to text. HTTP-sourced rows keep JSONL, because that data was JSON to
  begin with and JSON is faithful there.
- **An unknown key is answered the same way at every depth of every document.** Three
  mechanisms answered "what happens to a typo": a view document refused one at every nesting
  level, `domains/` and `catalogs/` refused one at the top while rule sets, decisions and
  calendars checked nothing at all, and routes, jobs, scopes, workflows, consumers and mcp
  documents got a warning for their own keys plus the blocks somebody had registered in a map —
  so `securty:` beside a route was reported and `polcy:` inside its `security:` was not. Which
  of the three a family got was an accident of the campaign that last touched it. The walk now
  recurses into every shape the model declares — a record, the entries of a sequence of
  records, the values of a map of records — so `security.polcy:`, `sources.main.sql.mod:` and
  `response.json.bdy:` raise `TQL-YAML-1043` the way a top-level typo always did, and a key the
  unified source model moved still raises `TQL-YAML-1044` naming where it went. `kind: prompt`
  documents load through a model of their own instead of being read out of the raw tree
  unchecked, and rule sets, decisions and calendars are walked too. The strict surfaces keep
  their semantics — a view or domain typo still refuses the load — but every accepted-key set,
  strict or lenient, is now read from the model rather than restated beside it, so a nested
  block is covered the day its record lands instead of the day someone remembers to register
  it. Documents with keys the loader was quietly dropping will report them.

### Fixed

- **A file the linter cannot read is a finding, not a clean report.** The lint rules each read
  their files quietly and treated a read failure as empty content, so a file that became
  unreadable mid-run passed every rule that inspects SQL — the tenant-predicate, write-scope,
  and ambient-bind checks simply saw nothing to object to. All reads now go through one
  per-run context that reads and parses each file once instead of once per rule, and a read
  failure surfaces once as `TQL-YAML-1053` (warning), naming the file and saying that every
  content lint on it was skipped.
- **A source is mounted once per route again.** The unified source model merged the disjoint
  `queries:` and `http:` maps into one `sources:` map, and two compiler loops that used to
  iterate different maps quietly began iterating the same one. A command's `http:` source ran a
  second time *after* the commit — a partner flake at that point returned an error for a write
  that had already happened, and the response read the second fetch, not the value the command
  bound — and a non-transactional MCP tool executed its entire read pipeline twice per
  invocation. A repeated SQL read is idempotent, which is why no behavioral test noticed;
  the compiled Camel model is now asserted directly: every declared source mounts exactly once,
  and `http:` sources mount before the transaction they feed.
- **A `publish:` with no pipeline is refused, not dropped.** `publish:` is a transactional-outbox
  write exactly like `notify:`, but it was missing from the compiler's is-this-a-command test —
  a command-json route declaring `publish:` with no transactional step compiled down the read
  path and the publish was silently dropped. The messaging cookbook itself taught that shape.
  `publish:` now forces the command pipeline (whose processor refuses the missing `steps:` out
  loud), lint `TQL-YAML-1052` says the same at authoring time, and the cookbook's examples
  declare their writes as `steps:`.
- **The lint and the compiler agree on what a consumer and a webhook are.** A `queue-consume` or
  `webhook` document whose only pipeline was `sources.main` passed lint — the check still spoke
  the deleted "a `sql:` or `steps:` pipeline" vocabulary — and then failed at startup, where the
  command processor refuses an empty `steps:`. Both lints now require `steps:`, and a consumer
  declaring `sources:` at all is refused (`TQL-YAML-1051`, with a compile-time backstop) instead
  of the sources compiling to nothing.
- **An abandoned spool reader no longer strands its stream — or its on-disk copy.** Three
  consumers walked away from spool readers mid-stream: the `first` peek of every result
  envelope, a group's early stop on ordered rows, and the split export's per-document
  narrowing, which did both once per group. A reader was only released by walking it to the
  end, and on `tesseraql.temp.store: db` — whose reads stage through a scratch file deleted on
  close — every abandoned reader stranded a full on-disk copy of the spool, O(groups) copies
  per grouped export. `first` is now captured while the spool drains (no reader is opened at
  all), a group releases its reader the moment the rows move past it, and the database store
  registers each staging copy with a cleaner so even an abandoned reader is eventually
  reclaimed.
- **One error code, one rule.** Nothing stopped two campaigns from allocating the same
  `TQL-*` number to different problems, and nine collisions had shipped — `TQL-LD-2857` meant
  both "two `splitBy:` keys name the same file" and "an export's enrichment failed",
  `TQL-CAMEL-3114` both "a spooled sibling cannot be enriched from" and "`into:` is not a
  result set", `TQL-BATCH-5002` both a failed step and an unreadable chunk input, and the
  generated reference merged each pair into one indistinguishable row. The nine newer rules
  are renumbered (`TQL-LD-2859`, `TQL-CAMEL-3115`, `TQL-BATCH-5003`, `TQL-FIELD-2007`,
  `TQL-STUDIO-4242`, `TQL-STUDIO-4243`, `TQL-REPORT-2008`, `TQL-WORKFLOW-3224`,
  `TQL-SQL-2603`), and a build guard now refuses a code declared at two sites unless it is on
  the reviewed list of deliberate shares (a unique violation is one meaning wherever it is
  mapped).
- **An `inputPolicy:` violation is reported once, not twice.** `lintRoute` called the
  input-policy lint twice in a row, so every `TQL-FIELD-2006` finding appeared twice in the
  CLI, the editor and Studio. The lint suite now also asserts app-wide that no finding is ever
  reported twice.
- **mcp documents get the unknown-key lint every other surface has.** Tools, resources and UI
  resources reuse the route record — which ignores unknown properties — plus keys the loader
  reads from the raw tree, and no unknown-key check ever reached them: a typo'd `securty:` on
  a tool dropped the auth declaration in silence while the same typo on a route was flagged.
  All three now run the same `TQL-YAML-1043`/`1044` check, with the loader-read keys
  (`description`, `uri`, `mimeType`, `ui`) accepted.
- **The documentation caught up with the unified source model.** Eight published pages still
  taught the retired shapes — an extraction inside `export:`, a `queries:` map, a map-shaped
  `steps:`, a top-level `sql:` — and none of it was caught by a build: `export:` ignores unknown
  keys, and a pipeline step declaring only an output block passes lint. Following those pages
  produced an export that silently wrote nothing.
- **A view's `source:` is described the way the loader reads it.** The shipped view schema still
  said "the route's `sql` result, or one of its `queries:`", and the generated YAML surface
  reference published that verbatim — the same class of drift as 0.14.0's `binding` `$def`. The
  two `TQL-VIEW-3308` messages named the retired vocabulary too.
- **A retired lint stopped being republished as a live one.** The error index scans the sources
  for literal codes, so `TQL-YAML-1045` — named only in a comment recording its retirement —
  appeared in the published index as a code with no meaning.

## 0.14.0 - 2026-08-13

The unified source model: how a document acquires rows had five spellings, and now it has
one. Every named read is an entry in `sources:` whose arm says by what means — `sql`,
`contract`, `service` or `http` — every read publishes the same envelope under its own name,
and the reserved name `main` is what the defaults resolve to. Routes and jobs speak one
context vocabulary. **Includes many pre-1.0 breaking changes** — read Changed before
upgrading. Twelve latent defects surfaced along the way and are listed under Fixed; the ones
worth knowing about are a job `query` step that discarded its rows, a `query-spool` reference
nothing could read, and a shipped JSON Schema that described a shape the parser no longer
accepted — which the generated YAML surface reference had been publishing to readers.

### Changed

- **BREAKING: one vocabulary for acquiring rows** (docs/unified-sources.md). How a document
  acquired data was spelled five ways — a privileged top-level `sql:`, a `queries:` map, a
  parallel `http:` map, a `sql:`/`http:` pair inside every `enrich:` entry, and an extraction
  that lived at route level on one export recipe and inside `export:` on the other. They are now
  one `sources:` map whose entries each name their own mechanism arm (`sql` | `contract` |
  `service` | `http`). The map no longer encodes the mechanism, so an HTTP source is a source
  like any other: named, enrichable, composable. Two lints narrow to what is left of them:
  `TQL-YAML-1022` keeps the recipe rule for an `http:` source and loses the shadowing check a
  separate map needed, and `TQL-CAMEL-3101` stops policing which of two homes an extraction used
  and now says only that `after:` needs `file-export`.
- **BREAKING: the top-level `sql:` key is deleted, on routes and on jobs.** It was a role
  wearing a mechanism's name — a route whose `sql:` declared `service:` contained no SQL at all.
  The primary is now the reserved source name `main`, which is a naming convention rather than a
  slot: every default resolves to it, and a document that has no use for one simply does not
  declare it. A command's write has one spelling too — `steps:` on a route, `pipeline:` on a job
  — so `batch-tasklet` dies with the key that was its only difference from `batch-pipeline`.
- **BREAKING: the envelope is universal.** Every read source publishes `rows` / `rowCount` /
  `first` under its own name, and the primary result is `main.rows` everywhere: a response body,
  a template, a view `source:`, an export template, a test expectation. Previously the primary
  bound as bare `rows` in one place and as `sql` in another, which meant an expression's meaning
  depended on where it was written.
- **BREAKING: `steps:` is an array of id-carrying steps.** The surface's rule is that a
  namespace is a map and an ordered sequence is an array whose items carry `id:` — `pipeline:`,
  `states:`, `transitions:` and `match:` already followed it, and route `steps:` was the one map
  whose *authoring order* was semantic. Now the order is the syntax.
- **BREAKING: a pipeline step is a binding with an `id`, plus its output blocks.** Its keys fall
  on three axes — the binding arm (`sql:`, `http:`), the output blocks (`export:`, `push:`,
  `notify:`) and `chunk:` — and a step declares at least one, rather than exactly one of six.
  A step that extracts rows and writes them to a file is one step with two keys; the extraction
  is the step's own arm, never a statement hidden inside `export:`. Output blocks do not read;
  `response:` never did.
- **BREAKING: an enrichment nests under the source it transforms, and `into:` is deleted.** The
  chunk step always had this shape; the route-level map with a back-reference was the exception,
  and the back-reference was the only reason an `http:` source could not be enriched — it lived
  in the wrong map, not by decision. `TQL-YAML-1045` retires with the key.
- **BREAKING: one context vocabulary across routes and jobs.** Declared inputs bind as
  `params.*` on a job as on a route (`job.*` is gone; the ambient `batch.*` stays), and job step
  results bind as `steps.<id>.*`, retiring the singular `step.`. An expression now means the
  same thing in a route, a job, an export template and a test.
- **A repeated YAML key is an error.** Every authored map is a namespace, and silently keeping
  the second `main:` is the shape of bug this codebase keeps finding: the document says one
  thing and the runtime holds another. It matters more now that reads share one `sources:` map,
  where a collision a lint used to catch across two maps is a duplicate key inside one.

- **BREAKING: a job's `query` step publishes its rows** (docs/unified-sources.md decision 18).
  It used to drain the `ResultSet` into a count and discard it, which made "fetch a control
  value, bind it into later steps" inexpressible for a reason no reader of the document could
  see: the step existed, its result did not. It now publishes `steps.<id>.rows` / `.rowCount` /
  `.first` like any other read, bounded by `materialize.maxRows` or the app's default — counting
  was memory protection, and the bound keeps that protection while the rows become usable. A
  `query-spool` step publishes `rowCount` and `spool` and no `rows`: the point of spooling is
  that the rows were never held, so publishing the count under the name that means a list
  everywhere else was the envelope contradicting itself.
- **`batch-tasklet` is gone** — see the top-level `sql:` entry; a job's work is its `pipeline:`,
  whatever the step count.

### Added

- **A chunk step loads what an earlier step spooled** (docs/unified-sources.md decision 19). The
  reader takes `spool: steps.<id>.spool` instead of `sql:`, and a batch **read** step may name
  its own `datasource:` — which together make a copy between two databases expressible for the
  first time: extract on one connector, load on the job's, neither side holding the result.
  There is still no distributed transaction; the copy is eventual, explicit and restartable, and
  the spool is the snapshot a rerun re-reads, which a SQL-reading chunk cannot offer. A write may
  not move connectors (`TQL-YAML-1037`): that would be a second transaction the job does not own.
  Spooled values round-trip through JSON, so a writer binding a date or a decimal casts in SQL —
  the rule `chunk.after` already carries, documented and linted the same way.

- **BREAKING: a workflow's `basePath:` is top level, and `command:` names a file.** `http:
  { basePath: … }` named a mechanism rather than the thing it held, and with the route-level
  `http:` map gone, keeping the word for an unrelated meaning would squat on it. `command:
  submit.sql` was the surface's only bare-string statement reference — its binds sat a level out
  from the statement they bound — and becomes `command: { file: submit.sql, params: … }`, the one
  spelling every role-typed SQL reference shares (docs/unified-sources.md decisions 14 and 16).

- **BREAKING: `response.json.nest` is retired; `enrich:` gains a `source:` arm**
  (docs/unified-sources.md decision 6). `nest` joined two already-fetched results, `enrich`
  fetched a reference by key: the same join (`on:`), the same composition (`as:` | `merge:`),
  one runtime (`KeyedReference`) — and two vocabularies. The reference arms are now
  `sql | http | source`, where `source: <name>` composes a sibling's rows without a fetch. The
  gain is not tidiness: `nest:` could serve only a JSON body, because `into:` named a body key
  and JSON is the only surface that has one. Written under the source it composes into, the same
  join reaches an HTML list's `columns:` and an export's. `TQL-YAML-1019` retires with the block.

- **BREAKING: `import:` says how to parse, and the per-row write is a step**
  (docs/unified-sources.md decision 7b). An `import.sql:` looked like a query and was a write,
  and it was the mirror image of the export block carrying its extraction — one confusion,
  stated twice. It moves to the document's one `steps:` (route) or `pipeline:` (poll job) entry,
  so every write on the surface is a step and every parsing declaration is only that.

- **BREAKING: `httpCall:` is `http:`, on a job step and on a test case** (docs/unified-sources.md
  decision 12). The step key was the binding union's `http` arm wearing a pre-union name, so a job
  step and a route source spoke two vocabularies for one mechanism. A suite case names its target
  after the document key it points at — as `sql:`, `contract:` and `notify:` already do — so the
  case key moved with it. After this, every place an outbound call is declared or targeted spells
  it `http:` — including the coverage kind, which gates on `coverage.thresholds.http`.

- **An `http:` step publishes the envelope every read publishes** (docs/unified-sources.md
  decision 10). `select:` names the part of the response that becomes rows, exactly as it does
  on a route's `http:` source, and the step's `rows` / `rowCount` / `first` sit beside the
  call's `status` / `body` / `headers`. Before, a job step published the response only, so
  binding one row of it meant walking the raw body — the arm meant two different things
  depending on which document declared it. `onError: empty` degrades a failed call the same way
  it does on a route, logged and metered rather than silently.

- **An `http:` acquisition can spool, so a chunk loads what an API returned**
  (docs/unified-sources.md decision 19a). Spooling is not a SQL feature — it is what a large
  result does on its way to a consumer that reads it once — so `mode: query-spool` means the
  same thing on a call, and the same `reader: { spool: … }` loads it. This closes a gap the
  campaign would otherwise have left: fetching a large result from an API and writing it into
  the database had no expressible shape, since a statement bound to a response holds every row
  and the only alternative was a file round trip through `push:` and a poll trigger. The
  gateway still buffers the response body, so the spool bounds what the rest of the job holds,
  not the call. `update` on an `http:` arm is refused at build time: a call reads.

- **A reading step folds references into its own rows** (docs/unified-sources.md decision 5).
  A job step's `enrich:` was read by nothing: the key was dropped at parse time, so the
  declaration was accepted, never run, and never reported — the failure mode where the document
  says one thing and the runtime does another. A step that reads (`mode: query`, or an `http:`
  call) now enriches like a route source and a chunk reader do; a step that holds no rows says
  so at build time instead of dropping the block, and so does a command step, whose writes
  publish `affectedRows` and keys rather than rows.

### Fixed

- **An enrichment's `source:` could not name a job result.** It was read as a root context key,
  and a job publishes under `steps.<id>` — so the sibling arm was unaddressable on the whole job
  side rather than unsupported there, and the error blamed the wrong thing. It resolves as a
  context path now, which is what a route's `source: rates` already was, one segment long. A
  sibling that spooled still refuses (`TQL-CAMEL-3114`), and now says what to do instead: load
  the spool into a table with a `chunk:` step and reference that.
- **The shipped `binding` schema described a shape the parser no longer reads.** After the arms
  landed, the definition still offered a bare `file:` and a string `contract:` — the flat
  record, not the nested authoring form — and the published YAML surface reference is generated
  from it, so a reader authoring from the reference wrote documents that silently did nothing.
  The definition now documents the arms, and a test compares it against the creator's
  parameters so the next added key cannot drift the same way.
- **Linting an app whose chunk reader took a spool crashed the linter.** The reader's SQL file
  was resolved before asking whether there was one, so `reader: { spool: … }` — the shape the
  cross-connector copy introduced — threw a `NullPointerException` out of `lint`. A spooled
  reader has no SQL to inspect: its order is the order the spool was written in.

- **`query-spool` stopped promising a result nothing could reach.** The mode published a spool
  reference that no pipeline vocabulary read: the only consumers of `tempStore.openInput` were
  route-side export paths, so "the spool is available to later steps" was true of the reference
  and false of the rows.
- **The unknown-key lint stopped warning about keys the loader reads.** It compared authored
  keys against record component names without following their `@JsonProperty` overrides, so
  every app declaring `export:`, `import:` or `notify:` was told the key would be "silently
  ignored" — while the runtime read it correctly.
- **A scaffolded update bound its primary key twice.** An assigned (non-generated) key is a form
  column as well as the row's identity, and the generated `params:` listed it from both, which
  the parser resolved last-one-wins. Strict duplicate detection surfaced it.
- **Declarative pagination followed the primary result's name, not the mechanism's.** The page
  clause was appended only to a query publishing under the literal key `sql`, which the rename
  would have silently turned off.

### Added

- **A `chunk:` step enriches each window before its writer sees it** (docs/lookups.md, slice
  14), so a writer may bind a column the reader's query never selected. The reader is read a
  window at a time — the enrichment's `batchSize`, or one row when nothing enriches, so a step
  without an enrichment reads exactly as it did. A reference failure fails the **window** and
  the step with it: it is not one row's fault, so it never reaches `tql_job_skips`, which is the
  record of rows the *writer* rejected. `into:` is refused, because a chunk enriches the
  reader's rows and that is the only result it has.
- **An export folds a keyed reference into the rows it writes** (docs/lookups.md, slice 13b).
  `enrich:` on a `query-export` or `file-export` route was refused; it now applies a window at a
  time — `batchSize` rows read, the reference fetched once for their distinct keys, the enriched
  window written. A million-row extract makes one reference query per window and never holds
  more rows than it already did. Wrapping the row *iterator* rather than any one branch is what
  makes the streaming, buffering and `splitBy:` paths one case: none of them learns that an
  enrichment happened. `RowEnricher` in core is the seam, and `EnrichProcessor` is split so a
  route and an export run one implementation of the key collection, the batching, the degrade
  rule and the many-to-one refusal.
- **An export's code names answer in the export's locale**, not in the requesting browser's
  (docs/lookups.md, decision 12). Otherwise one document carried names in the reader's language
  and its numbers and dates in the export's — a mismatch nobody declared and nobody could
  explain from the document. An export in an app whose catalogs carry per-language names must
  declare `locale:` (or `tesseraql.files.locale`): `TQL-FIELD-4622` refuses the undeclared case
  at build, because an export has no request to negotiate a locale from and "the report came out
  in English because the server's locale was" is this feature's characteristic failure.
- **A catalog invalidation reaches the runtimes that did not serve the command**
  (docs/lookups.md, decision 14). `invalidates:` now also raises a per-source-table version in
  `tql_catalog_version`, and every runtime re-reads that table at most once every five seconds —
  one small query for all catalogs at once, so a per-request staleness check never becomes a
  per-request query. The stamp is written **after the commit**, not inside the transaction: the
  guarantee is still the hold's expiry and the validation path's re-read, so a crash between the
  two leaves the other runtimes on the old names for the length of the hold, exactly as a master
  written by another system does. A database user that cannot create the table disables the
  stamp, never the catalogs.
- **The operations API reports what each code catalog holds, and refreshes one on request**
  (docs/lookups.md, decision 14). `GET /_tesseraql/ops/catalogs` lists every declared catalog
  with its source tables, how many codes the load carried, the languages it carried, when it
  loaded, and the message of its last failed refresh — a catalog serving a previous load while
  its refresh keeps failing carries both facts, because either alone reads as healthy. It
  reports the hold and never takes one, so "never loaded" stays visible instead of being caused
  by looking. `POST /_tesseraql/ops/catalogs/{name}/refresh` re-reads one whatever the hold
  says, gated by `ops.batch.run` like every other ops write; an undeclared name is a 404, not a
  silent no-op.
- **A catalog may be declared by a SQL file, and its names may come from the message catalog**
  (docs/lookups.md, decisions 12-13). `file: currency.sql` covers the shape `table:` and equality
  filters cannot express — codes in one table, their names per language in another — and then
  `tables:` lists what the SQL reads, so a maintenance command's `invalidates:` reaches it
  without anything parsing SQL. `label: { message: "code.priority.{key}" }` takes the names from
  the message catalog instead, which puts them in the translation workflow the Studio message
  editor already serves and adds no per-language table: the load says which codes exist, and one
  row becomes one entry per locale the app supports. `file:` and `table:` are exclusive, `file:`
  refuses `where:`/`order:` (the SQL owns both) and requires `tables:` (`TQL-FIELD-4621`), and
  the SQL must resolve under `catalogs/`.
- **`invalidates:` drops the code catalogs a command's write made stale** (docs/lookups.md,
  decision 13). The declaration names **source tables**, not catalog names, because a
  maintenance screen for a shared code master writes a row whose kind is request data — which of
  the twenty catalogs sharing that table is affected is not known until the row is written. It
  fires exactly where `emit:` fires, after the command processor, so a rollback invalidates
  nothing. Over-invalidating is free by construction: a catalog is chosen for being small enough
  to hold whole. `TQL-FIELD-4620` refuses it on a recipe with no commit and warns when it names
  a table no catalog reads — a typo in a verbatim identifier looks exactly like a correct
  declaration, and the symptom is a screen showing yesterday's names. The scaffolder emits it
  itself for a table a catalog reads, and only for those.
- **A code catalog may carry its names in more than one language.** `language: language` on a
  catalog makes language a *dimension* of the catalog rather than part of its key, so the call
  is the same in every language — `${codes.payment_method.of(row.payment_method)}` — and only the labels
  differ. The language is the surface's resolved locale (on a route: the request's), a code with
  no translation falls back to the **default language** rather than to the raw code, and the key
  set never narrows — validation asks whether a code exists, which is not a question about
  language, so a missing translation can never turn into a failed transaction. What a load is
  short of is reported once per load, not once per request. `TQL-FIELD-4619` warns when a
  `language:` catalog lives in an app whose `tesseraql.i18n.locales` holds a single tag, where
  no request could ever ask for the other languages.
- **A coded column renders its name on every surface.** A `domain:` whose legal values are a
  catalog's codes now resolves the name wherever the column is rendered — a list's `columns:`,
  a detail's `fields:`, a detail's history child, a dashboard's table panel, and the template
  the page was ejected into, which calls the catalog rather than carrying today's names as
  literals. One declaration, one answer: no screen can say "Bank transfer" while another says
  `TRANSFER`.
- **A catalog-backed field renders as a `<select>`** offering the catalog's active codes in its
  declared order, and an ejected form keeps reading them rather than freezing today's codes into
  markup — ejection freezes the layout, not the behaviour.
- **A field domain may be backed by a code catalog** — `codes: payment_method` on a domain, and the
  input binder accepts only that catalog's active codes. The violation is the `enum` field
  error, because a catalog is a dynamic enum; a value the held copy does not carry is re-read
  from the source before it is refused, so a code added a minute ago is never rejected for the
  length of the hold.
- **Code catalogs** (docs/lookups.md): a `catalogs/` document declares a small, nearly static
  table of codes and the names they stand for, and every template resolves it from memory —
  `${codes.payment_method.of(row.payment_method)}` — with no query per request and no
  per-route declaration.
  `where:` pins one kind's slice of a shared code master, so the twenty kinds a general master
  holds are twenty single-keyed catalogs over one table. Labels resolve over every row while
  `active:` marks what a form still offers, because a retired code must render on last year's
  orders and must not be offered on today's. A load swaps atomically and a failed refresh keeps
  the previous one, so a blinking database does not blank a screen.

- **`enrich:` folds a keyed reference into a result set's rows** (docs/lookups.md). The rows of
  `into:` (the route's `sql:` result, or one of its `queries:`) carry a key; the reference behind
  it is fetched by the *distinct* keys, in batches, and each match is merged onto the row it
  belongs to. A hundred-row page over sixty distinct partners costs one round trip, not sixty, and
  because it composes the result set rather than the response body, an HTML list's `columns:` sees
  the merged column. `batchSize:` defaults from the dialect and the key's arity — Oracle refuses an
  `IN` list past 1000 expressions, SQL Server a statement past 2100 parameters — and `maxKeys:`
  bounds the fan-out (`TQL-SQL-2114`) rather than letting it grow unwatched.
- **A `nest:` entry may `merge:` columns** instead of attaching a list under `as:`, and `on:` takes
  one entry per key column rather than exactly one. A many-to-one reference stops arriving as a
  one-element array.
- **An `http:` source may declare `method:` and `body:`.** A reference API is as often
  `POST …/search` with a list of keys as it is a `GET`.
- **A command may declare `http:` sources**, run before its transaction opens
  (docs/lookups.md, decision 19), so a write that needs a value only the partner has — the name
  behind a code, as of this transaction — no longer has to trust the caller to supply it or
  settle for filling it in eventually. A failed fetch fails the command before a row is written,
  and `readOnly: true` is required (`TQL-YAML-1050`) because a rollback cannot un-make a call.
- **An enrichment's reference may be an HTTP call** instead of a query, in either of two modes:
  `perRow` (the default — one request per distinct key, with `{key.<column>}` placeholders taking
  that key's values, percent-encoded per path segment) or `batch` (one request per `batchSize`
  keys, the set bound as `keys`). `onError: empty` degrades the whole enrichment rather than
  leaving some rows enriched and some not.

### Changed

- **The enrichment algorithm has one home.** `KeyedReference` in `tesseraql-yaml` now owns the
  key collection, the batching, the degrade rule and the many-to-one refusal for all three
  enriching surfaces — a route's result set, an export's row window, and a chunk step's window.
  They sit in modules that cannot see each other, so the alternative was three copies drifting
  apart. `KeyedUrls` and the `http:` body-to-rows shaping move with it, since an enrichment and
  an `http:` source have always had to agree on both.
- **BREAKING: `CatalogStore` asks two questions instead of one.** `catalogs()` becomes
  `catalogs(String localeTag)` — the rendering view, with labels in the surface's language — and
  the new `catalog(String name)` returns the load itself for the validation path. They were one
  method while a catalog had one language; keeping them one would have made "may this code be
  written" depend on which names happened to be translated.
- **BREAKING: `CodeCatalog.of(...)` falls back to the code itself** when the catalog carries no
  name for it, instead of returning `null`. A missing name is a gap in the master data, not a
  reason to blank a cell; returning `null` put that judgement in every template, and a template
  that forgot it rendered an empty column. Existence is still `has(...)`, the question
  validation asks.
- **BREAKING: a form field's `options` are `{value, label}` pairs** in the `v` model, for every
  field rather than only catalog-backed ones. An `enum` value is its own label, so one shape
  serves both sources; a template reading `${o}` reads `${o.value}` / `${o.label}` now. The
  bundled field fragment moves with it.
- **BREAKING: a webhook notification is delivered through the outbound gateway**
  (docs/lookups.md, decision 20). `WebhookNotifier` built its own `HttpClient`, so the one path
  where the framework itself calls out was the one path with no allow-list, no named credential,
  a hard-coded connect timeout and no share in the per-host circuit breaker. A webhook channel's
  host must now be in `tesseraql.http.outbound.allowedHosts`, and a channel may name a
  `credential:` and its own timeouts. The signing, payload, retry and dead-letter policy are
  unchanged — the HMAC is still computed over the exact bytes sent, which is why the body and the
  signature stay with the notifier rather than moving into the gateway.
- **BREAKING: `HttpSourceGateway` is `OutboundGateway`** (registry bean
  `tesseraqlHttpSourceGateway` → `tesseraqlOutboundGateway`). One seam for every outbound call —
  a job step, a query route's source, a command's pre-transaction fetch, an enrichment's
  reference, and a webhook delivery — rather than a name that described only the first consumer.
- **BREAKING: `http:` sources are no longer GET-only and body-less** (docs/lookups.md, decision
  16). That restriction stood for "a read route performs no write", which it neither achieved —
  nothing stops a partner's `GET` from mutating — nor came free: it refused JSON-RPC, GraphQL, and
  every batch-lookup endpoint. What holds the line is unchanged: `http:` is unavailable on command
  routes, so no outbound call is made inside the framework's own write transaction. A non-GET
  method is written out rather than inferred from `body:`, and a `body:` beside a method that
  carries none is now a build error (`TQL-YAML-1049`) instead of a body dropped in flight.
  `HttpSourceSpec` no longer restates `HttpCallSpec`'s nine fields — a source *is* a call plus
  `select:` and `onError:` — so a field added to a call reaches every call site.

- **BREAKING: an export's template reads `${sql.rows}`, not `${rows}`**
  (docs/export-pipeline.md, decision 14). A route publishes its default result under `sql` and a
  named one under its name, both carrying `rows` and `rowCount`; an export made its extraction a
  bare `rows`, so one template read `${rows}` and `${header.rows}` side by side and had to know
  why. Every result now also carries `first`, because a named result is spooled and read in
  sequence: `${header.first.customer}` replaces `${header.rows[0].customer}`. Report and print
  templates move with it; the framework's own bundled PDF grid has.
- **Each document of a `splitBy:` export reads its own named results.** A named query whose rows
  carry the split column is narrowed to that document and one that does not is shared, so five
  hundred invoices split by customer stop printing the same customer — the case that previously
  forced the denormalization named queries exist to end. One query runs for the whole export
  rather than one per document.
- **An export's named queries are spooled and capped** like its extraction. A workbook whose
  second sheet is a large named query cost a heap; it costs a spool, and `maxRows:` counts it —
  a ceiling that bounds the extraction and lets a named query run unbounded bounds nothing.
- **BREAKING: `FileCodec.write` takes an `ExportModel`** (docs/export-pipeline.md) in place of a
  bare `Iterator<Map<String, Object>>`. A codec received the rows and nothing else, and three
  unrelated-looking problems followed from that one signature: a header-and-lines document had to
  denormalize its header onto every line, a report template could not group without materializing,
  and a large export was a memory question. The model carries the rows plus the export's other
  declared sources, and it offers the row source twice — a single-pass iterator to a codec whose
  `streams(FileWriteSpec)` says it writes rows through, and a re-readable view over a `TempStore`
  spool to one that holds them. Asking for the other one fails (`TQL-LD-2856`), so a codec's
  declaration and its behaviour cannot drift apart. Third-party codecs must migrate; the bundled
  CSV, Excel and PDF codecs have.

### Changed

- **One JSON Schema per document kind** (docs/unified-sources.md): `tesseraql-v1.schema.json`
  is replaced by `tesseraql-route-v1`, `tesseraql-job-v1` and `tesseraql-view-v1`, sharing their
  value shapes through `tesseraql-defs-v1`. One file serving three kinds meant every document
  was offered every other kind's keys — a route completing `trigger:`, a job completing
  `response:` — and a view document, whose keys the file never carried, was flagged wholesale.
  Each kind now claims its own `kind:` and `recipe:` values, and the guard asserts each schema
  matches its model *exactly* rather than merely covering it, which is what makes a key in the
  wrong file fail. The exactness check earned itself immediately: it caught `export:` declared
  as a job root key, where a job's export has only ever been a pipeline step's. `workflow/`,
  `scope/` and `attachments/` documents move off the route schema onto an envelope schema that
  validates the discriminators and says plainly that their trees are not described yet — better
  than being measured against a schema built for something else. The `domains/` schema stops
  carrying a copy of the input-field definition and refers to the shared one, so the test that
  kept the copy honest is gone along with the copy. Editors pick up the new associations from
  the scaffolded `.vscode/settings.json`; a `*.view.yml` is matched by name, so it can never
  again be validated as a route.

### Fixed

- **The shipped JSON Schema described view documents nobody could write.** It declared a
  top-level `view: list | form | detail | dashboard` property the view loader never reads,
  while the `recipe:` key the loader *does* read carried an enum admitting no view recipe — so
  an editor flagged every valid `*.view.yml` and the published reference documented a key the
  loader rejects. The phantom property is gone, the recipe enum carries the four view recipes,
  and the per-view `template:` override (customization ladder L2) is documented where it was
  missing entirely. The schema tests only ever reflected over the route and job models, which
  is why neither direction of the drift was visible; a new guard reads the view loader's own
  key and recipe vocabulary, and was proven able to fail before being trusted.
- **A masked domain named on a detail view's child table, or on a dashboard's table panel,
  rendered raw.** The HTML output policies were collected from a view's own `columns:` and
  `fields:` only, so a `domain:` carrying `classification:`/`mask:` on a child's or a panel's
  column reached the applier under no key at all. Every column that can name a domain now
  contributes its policy.

### Added

- **`tesseraql.http.basePath`** (docs/base-path.md): serve an application under a path
  prefix — behind a reverse proxy at `/myapp`, or as one of several applications a
  `tesseraql host --mode suite` gateway fronts. Routes mount under the prefix and emitted
  URLs carry it, so the application answers at the addresses it advertises. Unset, which is
  every deployment that has not asked for one, renders byte-identical output. The surfaces
  outside Camel's REST configuration carry it too: static assets, the SSE streams, every
  redirect the framework emits, the file-transfer `Location` and status URLs, and the
  generated OpenAPI document's `servers` entry. The bundled applications — Studio, the
  operations console, IAM Admin, account, and the sign-in pages — emit their URLs through it
  as well, so a suite-hosted application no longer renders a sign-in form that posts into a
  void. `TQL-TPL-2004` warns when an application that configured a prefix writes a
  root-absolute `href`/`src`/`action` in its own templates — a warning, not an error, because
  a page may legitimately link outside its own mount point. The session cookie's `Path` comes
  from whatever starts the runtime: a suite gateway issues it at `/`, so one sign-in reaches
  every application, and a standalone application scopes it to its own prefix so its session
  is not offered to whatever else shares the origin.

### Added

- **`tesseraql host`** (docs/app-isolation-model.md): serves every application installed
  under an install root, each in its own runtime — its own Camel context, datasource set,
  Studio and traces — behind one port. `--mode suite` addresses them as
  `/apps/<id>/` on one origin, so a session spans the suite; `--mode isolated` addresses
  each by its own hostname, so sessions do not cross. The machinery existed and had no
  entry point.

### Documentation

- **[hosting.md](docs/hosting.md)**: running several applications on one machine — the two
  modes of `tesseraql host`, what the install root holds, and what runtime isolation does and
  does not promise. The mechanism had no user page at all.
- The **admission profile** is no longer named after a marketplace that does not exist, and
  says plainly that it is a gate you opt into for distribution rather than a house style.

### Removed

- **A user application can no longer be mounted into another runtime**
  (docs/app-isolation-model.md decision 1): `tesseraql.apps.<name>.path`, `.package` and
  `.url` stop mounting apps, and the URL-fetching app source is deleted with them. One runtime
  serves one application plus the framework's own surfaces; several applications are hosted by
  `tesseraql host`, which gives each its own runtime, URL space, Studio and traces. Mounting
  gave none of those: a flat URL space that two independently authored apps could collide in,
  a Studio blind to everything it mounted, and one trace buffer for all of them.
  `tesseraql.apps.<name>.enabled` stays — it turns off individual system apps. Shipping
  configurations B and C leave `deployment.md`, and `app-mcp.md` stops describing a catalog
  spanning mounted applications.

### Changed

- **The multi-app gateway serves one addressing per mode.** Host-based and
  `/apps/<id>/` routing both answered at once, so an operator who separated applications
  by hostname — the reason being that a session must not cross — still had every one of
  them on a single shared origin, where it does. Isolated hosting now refuses to start an
  application that declares no hostname (`TQL-APP-5003`) rather than cataloguing it,
  starting it, and leaving it unreachable.

### Changed

- **The operations console reports on its own runtime's applications**
  (docs/app-isolation-model.md decision 4). The ops tables live in a business database
  that several runtimes may share, and the console scoped its rows by the caller's
  `ops.app.<name>` grants alone — so a grant was enough to list another runtime's jobs,
  executions, transfers and audit entries, rows that console has no relationship with. A
  wildcard `ops.app.*` meant "every app in the database". Scope is now the intersection:
  what this runtime serves, narrowed by what the caller was granted. A single-application
  deployment is unaffected.

### Changed

- **The route audit log moves to the business datasource**
  (docs/app-isolation-model.md). `tesseraql.audit.routes.enabled` wrote through
  `tesseraql.framework.datasource`, but that key exists to keep a long-running business
  query from starving *login* of a connection, and the audit store writes once per
  audited request — business request rate — so it was loading the pool it was meant to
  protect. It also left the ops console reading two databases: seven of its eight pages
  come from the business datasource. A deployment that configured a separate framework
  datasource keeps its existing `tql_route_audit` rows readable where they are; the table
  bootstraps on the business datasource and logs there from now on. `framework-datasource.md`
  is amended: bucket 3 is ambient **authentication-path** state.

### Added

- **Shared views** (docs/declarative-views.md): one view document may now serve several
  routes — the app-wide view registry makes the reference a name, slot fragments
  resolve against the view document's own directory, and ejecting a shared view is
  refused (`TQL-VIEW-3316`) until the route points at its own copy. View coverage now
  keys by view document (one item per document, covered when any referencing route is
  exercised), so unreferenced view files become visible.

### Added

- **Views embed views** (docs/view-composition.md wave 2b/2c): a dashboard panel takes
  `{ type: view, view: <id> }` and a detail child takes `{ view: <id> }` (the entry's
  `source:` overriding the embedded document's own), rendering the embedded document
  through its own pattern fragment — data still comes from the host route's declared
  sources, and embedding depth is 1 (`TQL-VIEW-3318`). A `template:` route binds
  declarative parts with `response.html.views: [ids]`, each rendering into
  `views['<id>']` — and **ejecting a composite view emits exactly that shape**, so L3
  stops being terminal: the layout pins, the parts stay declarative. Route `model:`
  entries now render alongside `v` on view-backed routes (`v`/`views` are reserved,
  `TQL-VIEW-3319`).

- **Write-side field policy** (docs/view-composition.md wave 4): an `input:` field
  takes `policy:` — a security policy the principal must satisfy to supply it —
  enforced at the request binder (a failing principal's value follows the route's
  readOnly behavior; an ignored value is treated as not supplied and never binds) and
  mirrored by the derived form, which omits the field for that principal. Per-role
  forms stop requiring N command routes. Operational like `required`/`writable`:
  never accepted inside a domain; OpenAPI unchanged.
- **HTML output masking** (docs/view-composition.md wave 3b): a view column's or
  detail field's explicit `domain:` reference now applies the domain's
  `classification`/`mask` to the rendered HTML through the same `FieldPolicyApplier`
  the JSON renderer uses — closing the masked-in-JSON/raw-in-HTML asymmetry. Embedded
  views' policies apply through the host render.
- **Domains carry presentation** (docs/view-composition.md wave 3a): a field domain
  takes `widget:` — "an SKU is a code input", declared once — with precedence per-view
  `fields:` override > domain widget > type-derived default; an enum domain already
  doubles as a form's `<select>` options through the same merge. Read-side `columns:`
  and detail `fields:` entries take an explicit `domain:` reference
  (`TQL-FIELD-4601` when unknown). Presentation hints never reach OpenAPI.

### Changed

- **HTML responses negotiate their shell** (pre-1.0 behavior change,
  docs/view-composition.md wave 2a): with the new `response.html.shell: auto` default,
  an htmx partial request (`HX-Request`, minus boosted navigation and history restore)
  receives the bare `#page-content` region and direct navigation the shell-wrapped
  page, from one URL, with `Vary: HX-Request`. `shell: always` restores unconditional
  wrapping; `shell: never` declares an htmx-only region endpoint (`TQL-VIEW-3317` for
  anything else). Hand-written fragment templates that existed only to avoid returning
  a full page to htmx can now be deleted.
- **`response.html.view` references a view id, not a file path** (pre-1.0 break,
  docs/view-composition.md wave 1): every `*.view.yml` under `web/` and `templates/`
  joins a load-time registry keyed by the document's `id` (explicit, or defaulted from
  the file name), unique app-wide (`TQL-VIEW-3315`). Replace `view: list.view.yml`
  with the document's id (`view: items`); `scaffold crud` emits id references. Slot
  and per-view `template:` references now resolve against the view document's
  directory (then `templates/`), never the referencing route's.

- **View documents are strict** (pre-1.0 break, docs/view-composition.md wave 0): an
  unknown key anywhere in a `kind: view` document — top level, `fields:`, `columns:`,
  `children:`, `panels:`, `series:` entries — is now a build error
  (`TQL-VIEW-3314`) instead of being silently dropped. Audit view documents for
  stray keys; the common one is `label:` on a stat/sparkline panel, which was never
  read — the panel title key is `title:`.
- **View pattern fragment anchors moved** (pre-1.0 break to the public fragment
  contract): `tql/view/form.html` now anchors `view(v)` on the outer card (title,
  header slot, and not-found state included), and `tql/view/dashboard.html` carries
  its chart `<script>` pair inside the fragment — so an embedded fragment brings its
  chrome and its charts along. L2 overrides of these two files should re-anchor the
  same way (`TQL-VIEW-3307` flags a missing signature).

### Fixed

- A detail child's or dashboard panel's `source:` may now name one of the route's
  `http:` sources, as documented — `TQL-VIEW-3308` previously rejected everything
  but `queries:` keys.

## 0.13.0 - 2026-08-08

The contract-cleanup release: one wire vocabulary, one name per concept — the last
planned wave of pre-1.0 breaking renames — plus HTML email, the visual page builder,
and first-class Unicode identifiers end-to-end (Japanese table, column, field, and
route names work verbatim from DDL to JSON). **Includes many pre-1.0 breaking
changes** — read Changed before upgrading; the rename maps live in the entries
themselves.

### Added

- **Unicode identifiers, end to end** (docs/identifiers.md): a table, column, alias,
  or field name is Unicode letters/digits/underscores — `受注`, `顧客名`, `受注番号`
  are names like any other, and the column name *is* the field name, the SQL bind,
  the URL path parameter, the suite parameter, and the JSON key. Every validator and
  extractor shares one contract (`SqlIdentifiers`); browsers' percent-encoded
  requests match their routes (non-ASCII sequences decode before routing — ASCII
  ones like `%2F` deliberately do not); path parameters the HTTP router cannot
  represent (`{受注番号}`, `{order_id}`) travel as positional stand-ins and map back
  before anything user-visible; Studio's docs search finds Japanese by substring
  (`管理` finds `受注管理`). The `受注管理` gallery app
  (`examples/juchu-kanri-app`) exercises the whole surface, and the docs page
  records the per-dialect identifier byte-length limits.
- **HTML email** (docs/notifications.md): mail channels render bundled
  `tql/email/*` Hypermedia Components fragments (hc-email 0.1.14) — layout, text,
  button, footer and friends — shadowable per app at L2; and Studio gains a mail
  composer over the editor-kit with a strict round-trip grammar, so a composed
  template stays hand-editable.
- **Visual page builder** (docs/declarative-views.md): a drag-and-drop canvas over
  *ejected* templates — the builder edits real Thymeleaf files through a byte-safe
  section split with a manifest inspector, and the eject ramp (`studio.ejectView`,
  shared `ViewEjects`) hands a declarative view to the builder in one step.
- **Pages overview and mail-wiring lints**: `ui/pages` shows the app's whole page
  ladder (declarative view → ejected template → built page) with Pages and Mail in
  the Studio sidebar and command palette; mail channels lint at build time — a
  missing template fails (`TQL-BATCH-5304`), unknown `tql/email` fragments and
  unresolvable model roots warn (`TQL-TPL-2002`/`2003`, shadow-aware).

### Changed

- **Pre-1.0 breaking scaffolder-output change — one verbatim name from DDL to
  template** (docs/unicode-identifiers.md): a scaffolded field name *is* its column
  name (`dueDate` → `due_date`), route-id prefixes are the table name verbatim,
  shared rule names are `<table>_<column>_is_free`/`_exists` with matching
  `validate:` keys, HTML ids keep underscores (`field-unit_price`), and a decision
  scaffold keeps the decision name verbatim in its file stem and `<name>_rules`
  table. The camel↔snake bridges (`Names.camel`, `ViewFields.snake`, Studio's
  reverse mapping) are deleted — a form prefill reads exactly the field name.
  Unicode (e.g. Japanese) table and column names flow verbatim end-to-end, and
  scaffolded paths are NFC-normalized so macOS and Linux name the same files.
  Hand-authored apps keep whatever field names they declare.
- **Pre-1.0 breaking load-time change — an unresolved `domain:` or `use:` reference
  always fails the load** (the contract sweep's decision-closure wave): an app with
  no `domains/` or `rules/` tree at all used to silently drop every reference —
  the constraints a typo names simply never applied. The empty-tree skip is gone;
  the same unknown-reference error now fires whether or not the tree exists.
- **Pre-1.0 breaking HTTP-wire changes** (docs/vocabulary-cleanup.md slice 3):
  every framework JSON timestamp is an ISO-8601 UTC string — `/ops/slow-sql`,
  `/ops/pinning`, and `/ops/traces*` drop their raw `…EpochMs` longs
  (`startedAt`/`at`), and `/ops/audit`'s `occurredAt` stops being
  `Timestamp.toString()`; every JSON writer sends
  `application/json; charset=utf-8`; the attachment-upload and SCIM 201s carry
  `Location` (RFC 7644 §3.3), the file-transfer 202 points `Location` at the
  status resource (body keeps `statusUrl`), and
  `POST /_tesseraql/ops/batch/jobs/{id}/run` answers **202 + Location**
  instead of a bare 200; every rendered 429/503 carries `Retry-After`; the
  htmx redirect negotiation is one shared helper (login/IamAdmin redirects now
  answer htmx with `204 + HX-Redirect`); and `GET /_tesseraql/logout` is
  removed — sign-out is a CSRF-carrying `POST /_tesseraql/logout`, and the
  shell's sign-out menu item is a form.
- **Pre-1.0 breaking value-vocabulary changes** (docs/vocabulary-cleanup.md
  slice 2): multi-word enum values go kebab-case (`auth: api-key`,
  `runOn: business-day | first-business-day-of-month |
  last-business-day-of-month`, `shift: next-business-day |
  previous-business-day`); a decision row's `out:` → `outputs:`, the subtree
  match kind unifies on `subtree` (was `orgSubtree` at declaration), and a
  table source's key column is `keyColumn:` (was `id:`); a shared rule's
  `binds:` is a typed map (`binds: {sku: string}`) checked against the
  referencing route's input types at load; `version: tesseraql/v1` is required
  by every document family — views start validating it and `tests/*.yml` gains
  it; a view's discriminator is `recipe: list | form | detail | dashboard`
  (was `view:`), and a dashboard panel's chart shape is `chart:` (was `kind:`,
  colliding with the panel-role vocabulary).
- **Pre-1.0 breaking YAML renames — one word per concept**
  (docs/vocabulary-cleanup.md slice 1): a job's declared parameters are `input:`
  (the same contract routes declare; `params:` keeps its bind-wiring meanings);
  the route admission block is `admission:` (was root `policy:`, which collided
  with `security.policy`); the pagination block is `pagination:` (was `page:`);
  `csrf:` is one `auto | required | off` enum on routes and defaults rules alike
  (was a route boolean and a defaults `auto|"true"|"false"` string);
  `http-call:` → `httpCall:` (steps and test cases); a binding's
  `expect.rows` → `expect.rowCount`; a chunk step's `onError:` is `fail | skip`
  with a sibling `skipLimit:` (was an object; skip without a limit defaults to
  100); poll `source:` and push `target:` are both `transport:`; an
  export/import column heading is `label:` (was `header:`, the view column's
  word); a workflow's reminder block is `reminders:` (was `notify:`, colliding
  with the route notification map); `onBreach.reassign:` takes the `assign:`
  shape (`{file, params}`).

- **Pre-1.0 breaking default change — sessions are `jdbc` by default**
  (docs/contract-bugfixes.md track G): `tesseraql.sessions.store` now defaults to
  `jdbc` (shared `tql_session`, survives restarts, multi-node correct);
  `memory` is the explicit per-node opt-in for embedders and tests.
- **Pre-1.0 breaking default change — `overlap: skip` is the job default**
  (docs/contract-bugfixes.md track H): a firing that finds the previous execution
  still `RUNNING` records a `SKIPPED` execution instead of stacking a concurrent
  run; a job that is safe to overlap declares `overlap: concurrent`.
- **Pre-1.0 breaking behavior change — the live-stream global cap refuses**
  (docs/contract-bugfixes.md track I): at `tesseraql.live.maxTotal` (default 256) a
  new `/_tesseraql/events` subscription answers `TQL-RATE-5030` (503 +
  `Retry-After`) instead of silently ending another user's oldest stream; the
  per-subject cap (default 4, `tesseraql.live.maxPerSubject`) keeps evicting the
  subject's own oldest stream. Both caps are now configurable.

- **Pre-1.0 breaking HTTP-contract change — the operations API's refusals carry their
  status** (docs/contract-bugfixes.md track A): `/_tesseraql/ops/batch` not-found and
  out-of-scope refusals (`TQL-BATCH-4040`) now answer **404** instead of 200 with an
  error body, and the ops transfer download's not-ready refusal (`TQL-LD-2823`) rides
  the standard error path (409, unchanged status). A client checking `response.ok` no
  longer reads a missing execution as success.
- **Pre-1.0 breaking HTTP-contract change — unenumerated `TQL-SEC-*` codes are 500,
  not 401** (docs/contract-bugfixes.md track B): configuration errors, federation
  failures, egress refusals, and crypto errors in the SEC domain answered 401
  ("Unauthorized") through the domain default; they now answer 500. Genuine
  caller-fault codes keep their statuses (4011/4012/4013 → 401, 4031/4032 → 403,
  4014 → 409).
- **Pre-1.0 breaking behavior change — a remote poll/push `path:` means what it says**
  (docs/contract-bugfixes.md track C): a leading slash on an SFTP/FTPS `path:` is now
  absolute on the server, as the reference always documented; without one the path
  resolves against the credential's login home. Previously every path was silently
  login-home-relative and absolute paths needed an undocumented `//` escape (which
  keeps its absolute meaning). A deployment that relied on the home-relative reading
  of a leading-slash path must drop the slash.
- **Pre-1.0 breaking JSON-contract change — the transfer status counts rows as
  `rowCount`** (docs/contract-bugfixes.md track D): the file-transfer status
  endpoint's `rows` key — the one payload where `rows` was an integer instead of a
  list of records — renamed to `rowCount`, matching the query metadata vocabulary;
  the OpenAPI `TransferStatus` component follows.
- **Pre-1.0 breaking JSON-contract change — structured error details**
  (docs/transition-engine.md Track F): a `TqlException`'s structured details no longer
  merge flat into the rendered `error` object; they render as the `error.details`
  namespace (`{"error": {"code", "message", "details": {...}}}`), so a detail may use
  any key — `code` and `message` included — without colliding with the envelope's own.
  Accordingly the SQL guard refusal keys renamed from `guard`/`guardMessage` to the
  natural `details.code`/`details.message`, and `fields`, `conflict`, `dispatch`,
  `attempted`, and `maxBytes` moved under `details`. Unchanged: `error.code`/
  `error.message`, the htmx field-errors HTML fragment contract, the suite outcome
  rows' `code`/`guard` columns, and the dispatch `attempted[]` entry shape. An htmx
  error alert now renders a guard's declared refusal message as its body, so a form
  shows *why* the transition refused.

### Fixed

- **Two same-database apps can no longer share one migration history**: the per-app
  Flyway history table's sanitizer mapped every non-ASCII character to `_`, so two
  Japanese-named apps silently wrote the same `tql_schema_history____` — Unicode
  letters now survive, keeping the tables distinct.
- **Non-ASCII names no longer disappear silently**: a `{受注番号}` path parameter is
  extracted and bound (it used to resolve `null` with no diagnostic) and appears in
  `openapi.json`; the write-scope guard (`TQL-SEC-4100`) sees a Japanese
  scope-governed table (it used to skip it — a silent loss of a security lint); a
  `{顧客名}` message placeholder interpolates instead of reaching the user as raw
  braces; ejected view links render their `${row[...]}` expressions instead of
  literal `{…}`; and a Japanese `th:each` alias no longer draws false
  "unresolvable root" template warnings.
- **PostgreSQL NOTIFY/LISTEN channel names are quoted**: a Unicode channel name —
  which the sanitizer always allowed through — was a runtime syntax error on the
  only path that already reached raw SQL.
- **Studio accepts Japanese migration descriptions** (the slug no longer collapses
  to empty and refuses with "needs a description"), and generated reference pages
  keep Unicode heading anchors instead of colliding every Japanese heading onto one
  empty fragment.

## 0.12.0 - 2026-08-07

The UI/UX release: a full-surface refresh of Studio and every other framework-provided
screen (Operations Console, IAM Admin, the account surface, the auth pages), the
Hypermedia Components 0.1.13 upgrade, and app-wide slate/compact defaults. **Includes a
pre-1.0 rendering-contract change** — see Changed.

### Added

- **Studio UX refresh** (docs/hypermedia-ui.md): a command palette (`⌘K` — navigate,
  open-in-editor, create-route-here), the explorer as a keyboard-navigable tree with
  hover row actions, live wizard previews with a progress stepper, a two-column source
  editor (sticky action bar, `Ctrl/Cmd+S` saves), health/security stat tiles with
  URL-routed filters, month-grid business calendars with click-to-toggle holidays,
  card anatomy and one semantic color vocabulary across all Studio pages, and a flash
  confirmation after every mutation.
- **App-wide UI defaults** `tesseraql.ui.neutral` (default `slate`) and
  `tesseraql.ui.density` (default `compact`) (docs/hypermedia-ui.md): every generated
  app and console renders on the slate neutral ramp at compact density out of the box;
  both are overridable per app, and the standalone auth pages follow the same ramp so
  the sign-in screen matches everything behind it.
- **Console parity with Studio** (docs/console-ux-refresh.md, internal): flash
  confirmations for every IAM Admin and account mutation (including TOTP enrollment
  and bulk disable with a count), a server-side contains-search on the IAM users list
  (login/display name/email), route/actor/status filters on the ops audit trail,
  stat-tile roll-ups on the ops overview, `INVITED` badged as info (not error), and
  Enable/Disable offered by the user's current status.
- **Data browser numeric columns** align: column types come from the result set's JDBC
  metadata (works across DuckDB attached catalogs), rendered with the kit's
  `data-numeric` end-alignment — as do durations, row counts and lane counters across
  the Operations Console.
- **Hypermedia Components 0.1.13**: the kit now submits confirmed plain forms, guards
  tree row actions, ships the declarative `data-hc-show-switch`/`data-hc-show-when`
  contract, and end-aligns `data-numeric` table columns — the framework's interim
  stand-ins for all four are retired.

### Changed

- **Pre-1.0 rendering-contract change**: the generated view templates
  (`tql/view/list|form|detail|dashboard`), the `tesseraql new` starter page, and the
  example gallery now render titles in the kit's card anatomy
  (`hc-card__header`/`hc-card__body`), and the global bare-`h2` scale is gone from
  `tesseraql.css` — a bare heading outside a card is a page title on the UA scale. An
  app that shipped custom templates against the old flat-card markup re-renders with
  UA-scale headings until it adopts the anatomy (no user apps are known to exist yet;
  L2 template overrides are unaffected).
- The Batch Jobs console page is now two cards: the status table auto-refreshes alone,
  and Run forms live in a separate non-refreshing card, so the 15 s refresh can never
  discard a parameter mid-typing. The audit page applies the same shape around its new
  filter form.

### Fixed

- **Changing your password now explains the sign-out**: the login page renders
  `reason=password-changed` — previously the account surface sent it but the page
  never read it, so the user was signed out on every device (by design) with no
  explanation.
- **The Batch Jobs auto-refresh no longer discards Run-form input** (see Changed for
  the shape).
- **SQL Server runtime bootstrap no longer fails on re-run**: the batch schema's
  column-add migrations (business date, chunk skip counts, execution params, cancel
  flag) were missing the `if col_length(...) is null` guard the SQL Server dialect
  scripts use for idempotency, so the second `ensureSchema` pass in one runtime start
  aborted with a duplicate-column error (SQL Server's 2705 is not in the tolerated
  already-exists set the other dialects rely on). Found by the gated
  `SqlServerPortabilityIntegrationTest` during release verification.
- **Every database-touching CLI command finds the running `serve --embedded-db`**
  (docs/getting-started.md): `scaffold crud`, `migrate`, `test`, `job`, `schema`, and
  `coverage` now share `identity-schema`'s datasource resolution — an explicit
  `--jdbc-url`, then the app's configured main datasource when it answers, then the
  running embedded database's `work/embedded-db.jdbc` marker. Previously only
  `identity-schema` fell back to the marker; `scaffold crud` even carried its own copy
  of the config fallback. The `mcp` dev-tools' database tools (`schema_introspect`,
  `scaffold_crud`, `test`, `ops_status`) apply the same precedence, so an agent works
  against the embedded database another terminal is serving without passing `jdbcUrl`.
- **An unreachable database is a clear message, not a stack trace**: a CLI command
  that cannot reach the database (SQLState class 08 or a socket-level cause) now
  prints what failed and the three ways out — start the database, point
  `tesseraql.datasources.main.jdbcUrl` / `--jdbc-url` at it, or run
  `serve --embedded-db` — and exits 1. Other failures keep their full diagnostics.

## 0.11.0 - 2026-08-02

### Added

- **Push server-identity lint nudges** (docs/connectors.md): an `sftp` push target
  without `tesseraql.connectors.push.knownHostsFile` warns (`TQL-SEC-4084`), an
  `ftps` push target without `tesseraql.connectors.push.trustStore` is an error
  (`TQL-SEC-4085`) — the poll side's nudges, mirrored, since the runtime enforces the
  same guarantees on both directions. Docs also record two authoring facts: PDF print
  templates carry tables and text, not the client-drawn dashboard charts
  (docs/printable-documents.md), and a **filtered dashboard** is existing vocabulary
  composed — a route input, bound panel queries, and a GET form in the header slot
  (docs/declarative-views.md).

- **Batch runs count on the Prometheus exposition** (docs/jobs.md,
  docs/deployment.md): `tesseraql_job_runs_total` labelled `job`/`app`/`status` —
  COMPLETED, FAILED, STOPPED, and SKIPPED each under their own status, so "did
  tonight's close run" is one query — plus a `tesseraql_job_duration_seconds`
  histogram per job, riding the same `/_tesseraql/metrics` scrape the route counters
  use.

- **The analytics loop has a front door** (docs/analytics.md): one guide page walks
  files → scheduled ETL → lake snapshots and time travel → dashboards → the delivered
  report, with pointers into each feature's page. The inventory gallery now proves
  the whole loop: the daily report **pushes to a local partner drop**, and the
  dashboard adds a **best-price trend from the lake's history** plus the **snapshot
  list** — the time-travel index. The five-minute demo gains the close-to-delivery
  leg.

- **Transfer retention** (docs/file-transfers.md): `tesseraql.transfers.retentionDays`
  reclaims produced files older than that many days on a periodic sweep
  (`sweepInterval`, default 1h) — the spooled bytes are deleted, the transfer row
  stays as history (flagged *expired* on the ops transfers page), and the download
  answers 409 from then on. Nothing expires by default, the lake-snapshot stance:
  retention policy belongs to the app.

- **SFTP/FTPS/local delivery: the `push:` pipeline step** (docs/jobs.md,
  docs/connectors.md): a job can deliver a produced transfer — typically an export
  step's file — to a partner drop. The outbound mirror of `poll:`, under its own
  deny-by-default policy block `tesseraql.connectors.push` (host allow-list, local
  `allowedPaths` roots, `knownHostsFile`, FTPS `trustStore`, named credentials through
  the SecretResolver SPI); the remote endpoint mechanics are one implementation shared
  with the poll consumers, so the two directions cannot drift apart. Deliveries stage
  under a temp name and rename, so a partner poller never reads a partial file; an
  off-list host is `TQL-SEC-4141`, a failed delivery `TQL-BATCH-5315`, malformed steps
  `TQL-YAML-1042`, and a bare `*` in the push allow-list fails admission
  (`TQL-ADM-4703`).

- **Mail attachments: `attach:` on a notify declaration**
  (docs/notifications.md): a notification through a mail channel can carry a produced
  file — `attach: step.report.transferId` names the export step's transfer, the id
  rides the outbox envelope, and the bytes are read from the transfer store at
  delivery time, so events stay small and the at-least-once/retry/dead-letter policy
  is untouched. The mail goes multipart (rendered body + the file under its transfer
  filename), capped by the channel's `maxAttachmentBytes` (default 10 MiB). Mail
  channels only: `attach:` on a webhook or inbox channel is a build error
  (`TQL-FIELD-2004`) rather than a silently dropped file.

- **Editor catch-up for the analytics surfaces** (ext 0.3.7,
  docs/vscode-extension.md): snippets for the kit-chart dashboard vocabulary —
  `tql-view-dashboard` and `tql-chart-panel` carry the `series:` list, the seven
  kinds, and the passthrough attributes — and for the export step
  (`tql-export-step`, paired with the follow-up `notify:` that carries the
  published `step.<id>.transferId`). No symbols-contract change: chart kinds and
  export formats are enum vocabulary, not declared symbols the editor could
  navigate to.

- **A job can produce a file: the `export:` pipeline step** (docs/jobs.md): the route
  recipes' export vocabulary — `format`, `filename`, `columns`, templates — as a fifth
  step body, run inline on the job's datasource through the same codec → spool →
  transfer machinery HTTP `file-export` uses. The extraction SQL renders like a `sql:`
  step's (dialect variants, ambient `batch.*` binds, file placeholders), `filename:`
  interpolates `{batch.businessDate}`-style context paths, and the step publishes
  `transferId`/`rows`/`filename` for follow-up `notify:`/`http-call:` steps. Retrieval:
  the ops console transfers page links every completed export, backed by
  `GET /_tesseraql/ops/console/transfers/{id}/file` (browser session) and
  `GET /_tesseraql/ops/batch/transfers/{id}/file` (bearer) under `ops.batch.view`,
  app-scoped like the listing. Malformed steps are `TQL-YAML-1041`; `after.timing:
  download` stays route vocabulary.

- **Dashboard charts are the kit's charts** (docs/declarative-views.md,
  docs/hypermedia-ui.md): the `chart` panel adopts Hypermedia Components'
  `data-hc-chart` recipe — the server renders the panel's rows as a real table (the
  data source, the no-JavaScript fallback, and the screen-reader representation in
  one) and the kit's `installChart` draws the Observable Plot SVG in the browser. The
  panel vocabulary grows to the kit's: `kind:` now spans `bar`, `line`, `area`,
  `combo`, `bar-stacked`, `bar-grouped`, and `scatter`; multi-series arrives as
  `series:` (per-series `label:` and, under `combo`, `mark:`), with `y:` kept as the
  one-series shorthand; `xType:`, `height:`, `legend:`, and `yLabel:` pass through as
  the kit's data attributes. Violations are `TQL-VIEW-3313`. Observable Plot is
  vendored as a self-hosted webjar and loads — with the `charts.js` bootstrap — only
  on pages that render a chart panel; the CSP stays `default-src 'self'`.

- **The data browser browses every declared datasource**
  (docs/analytics-experience.md track 1): Studio's data browser gains a datasource
  selector — server databases and `duckdb` engines alike, under the existing
  `tesseraql.studio.dataBrowser.enabled` opt-in. On a `duckdb` datasource the browser
  lists tables and views across every catalog visible on the connection — attached
  datasources and lake tables included — displayed and addressed as
  `catalog.schema.table`; filters, sort, pagination, and CSV export work unchanged.
  The datasource is validated by membership like the table name, read-only stays
  best-effort defense in depth, and row editing remains a `main`-only affordance.

- **The console tells the whole trigger story** (docs/jobs.md): the operations
  console's jobs page now shows the calendar qualifiers (`cron …, calendar jp-banking
  (day 5)`), the operational policies (`overlap: skip`, `sla by 06:00`, `sla > 2h`) as
  badges, and — the piece nothing else could show — **Calendar next**: the next date
  the business-day calendar lets a firing count, shifted nominal dates included
  (`2026-08-03 (for 2026-07-31)`). A calendar-filtered firing leaves no execution row
  by design, so this column is where "why didn't it run today" gets its answer. The
  one-line trigger story now lives in `TriggerSpec.describe` — the CLI, the symbols
  contract, and the console share it, so the three surfaces can never drift.

- **Studio edits the job policies** (docs/jobs.md): a job-policies form — trigger
  (cron/fixedDelay, calendar + runOn/dayOfMonth/shift, or an `after:` chain picked from
  the declared jobs) plus `overlap:` and `sla:` as structured fields, saved through the
  draft flow with the linter's rules enforced before the draft exists
  (`TQL-STUDIO-4239`). Poll-triggered jobs keep the text editor by design.

- **Studio draws the calendar** (docs/jobs.md): a calendars surface with a month grid
  that makes the daily-consider model visible — business days, weekends, holidays, and,
  when previewing a `dayOfMonth:` rule, the nominal date and the one day the firing
  actually counts. Weekend and fixed `dates:` edit as a form through the draft flow
  (day names and ISO dates validated first, `TQL-STUDIO-4238`); table-backed holiday
  rows stay with the data browser, and their preview reads the main datasource.

### Changed

- **BREAKING: the dashboard chart markup changed** — a `chart` panel now emits the
  kit's `<figure data-hc-chart="…">` with the source table inside, instead of a
  server-rendered inline SVG; `ChartSvg` is deleted. Templates or tests matching the
  old `hc-chart__plot` SVG match the source table now, and the chart itself renders
  client-side (no JavaScript → the table).

- **`GET /_tesseraql/ops/batch/jobs` returns objects** — `{id, app, trigger, overlap,
  sla}` instead of the bare job-id strings, so the API tells at least as much as
  `tesseraql job list`. Consumers reading the old string array take the `id` field.

## 0.10.0 - 2026-08-01

### Added

- **Editor catch-up for the batch platform** (ext 0.3.6, docs/vscode-extension.md):
  `tesseraql symbols` now prints `calendars` and `jobs` (each job with its one-line
  trigger story), and the VS Code extension completes and navigates `calendar:`
  values (a typo fails open at fire time — the editor is where it gets caught) and
  `after:` chain targets (completions show how each job starts), adds a Calendars
  explorer section, and ships the batch snippets — `tql-chunk-step` with the keyset
  reader contract, `tql-calendar`, `tql-job-schedule`. Pre-0.10 CLIs degrade to
  empty arrays, the established rule.

- **The cooperative stop — a stop button that tells the truth**
  (docs/batch-platform.md, a lifted deferral): `tesseraql job cancel <executionId>` and
  `POST /_tesseraql/ops/batch/executions/{id}/cancel` (policy `ops.batch.run`) set a flag
  the running executor polls at two boundaries — between pipeline steps (remaining steps
  never start) and at every chunk commit, where the stop lands exactly on a committed
  checkpoint: the step ends `STOPPED` with its real counts and a rerun for the same
  business date resumes precisely there. The semantics are stated, not implied: effect at
  the next boundary, statements bounded by their SQL timeout, no preemptive kill.
  Cancelling a finished execution answers 409 with `TQL-BATCH-4042`.

- **The shifted nominal day — "the 5th, or the next business day when that is a holiday" — without scheduler state**
  (docs/batch-platform.md, a lifted deferral): a schedule may declare `dayOfMonth: 5`
  with `shift: nextBusinessDay` (default) or `previousBusinessDay`. The shifted target
  is a pure function of the business-day calendar — the firing counts only on it, across
  month boundaries in either direction, with over-length days rounding to the month's
  last day — so the daily-consider model needs no missed-date memory. The run's business
  date is the **nominal** date: the 5th's close, executed on the 7th, records the 5th,
  in the scheduler, the ops API, and `tesseraql job run` alike. Lint `TQL-BATCH-4202`
  refuses a nominal day without a calendar, out of range, combined with `runOn:`, or a
  `shift:` that names no direction.

- **Overlap policy and the SLA that pages someone**
  (docs/batch-platform.md slice 5, closing the batch-platform campaign): a job may declare
  `overlap: skip` — a firing that finds the previous execution still `RUNNING` is recorded
  as a `SKIPPED` execution naming it (auditable, no steps, `tesseraql job run` exits 3),
  while `concurrent` stays the default — and `sla: { completeBy: "06:00",
  runningLongerThan: 2h }`, checked by a periodic managed sweep that raises `ops.jobSla`
  through the configured alerts channel, once per missed business date and once per
  too-long execution (cluster-deduplicated via the claim table). Alert-only by design:
  a false sense of "timeout means stopped" is worse than an honest page. Malformed
  declarations refuse at build time (`TQL-BATCH-4210`).

- **The external-scheduler execution contract — `tesseraql job list/run/rerun`**
  (docs/batch-platform.md slice 4): schedulers drive batch by executing a command and
  branching on the exit code, and now that command exists. `job run` executes in-process
  (no server), prints the execution id and per-step summary, and exits 0 on COMPLETED,
  1 on FAILED, **3 when the business-day calendar filtered the date out** — distinct,
  so "holiday" can be success-with-note (`--ignore-calendar` forces). Every execution
  now records its parameters, so `job rerun` re-runs the same fact — the source's
  parameters and business date, with chunk checkpoints resuming where the failure
  stopped — and `--from-failed-step` records the source's completed steps as `SKIPPED`.
  Light chaining lands with it: `trigger: { after: <jobId> }` fires a job when the named
  job completes successfully in the same app, carrying the business date, in the CLI and
  the serving runtime alike (lint `TQL-BATCH-4209` refuses unknown targets and cycles;
  the job-net DAG stays with external schedulers by design).

- **The chunk step — restartable, skip-aware, committed in slices**
  (docs/batch-platform.md slice 3): a pipeline step's fourth body. `chunk:` streams a
  keyset-ordered reader on one connection and runs its writer once per row on a second,
  committing every `commitEvery` rows; each committed chunk checkpoints its last handled
  key in the managed `tql_job_checkpoint` table so a rerun for the same business date
  resumes where the failure stopped (the reader binds it as `chunk.after`), and a
  completed step clears it. A writer failure on one row rolls back to a per-row
  savepoint, lands in the managed `tql_job_skips` table, and processing continues until
  `skipLimit` (default 0) is exceeded. Processed/skipped counts reach the step
  execution, the operations API, and the console; lints guard the restart contract
  (`TQL-BATCH-4206`–`4208`). The gallery's `user.anonymizeInactive` is the runnable
  reference.

- **Business-day calendars — the cron considers, the calendar counts**
  (docs/batch-platform.md slice 2): named calendars declared once under `calendars/`
  (a weekend definition plus holidays as a fixed `dates:` list or a table-backed
  `source:` read on the job's datasource at fire time), referenced from any schedule
  via `calendar:` and `runOn: businessDay | firstBusinessDayOfMonth |
  lastBusinessDayOfMonth`. Under the daily-consider model a filtered-out firing is
  skipped silently after the cluster claim — considered, not a run. Manual runs
  bypass the filter and resolution failures fail open with a warning, so the build is
  where typos get loud: lint checks every reference (`TQL-BATCH-4201`–`4203`), and a
  calendar that cannot be evaluated refuses to load (`TQL-BATCH-4204`/`4205`). The
  inventory gallery's market-data summary is now calendar-gated.

- **The business date — a batch run knows what it is for**
  (docs/batch-platform.md slice 1): every job execution now carries a business date —
  defaulted from the firing's local date, overridden by the reserved `businessDate`
  parameter on a manual run (`TQL-BATCH-4041` refuses a malformed one before anything
  executes), recorded on `tql_job_execution` and in the operations API. Step SQL reads
  it as the ambient `batch.businessDate` bind (with `batch.executionId` alongside),
  seeded the way `audit.*` is seeded into commands — the audit-grade difference
  between "ran on the 1st" and "ran the 31st's close on the 1st".

- **The header+lines pattern ships runnable** (docs/transactional-writes.md, the
  procurement retrospective's cookbook item): `POST /api/requisitions` in the
  procurement gallery now creates the header and its line items in one transaction —
  a generated-key header step, a `%for` detail insert bound to the request's `lines`
  array, and a `validate:` rule (`params.lines.size > 0`) refusing an empty order
  before anything writes. Suite-covered (the multi-row insert and the refusal as
  cases), so the documented pattern is proven, not illustrative.

- **`given:` fixture steps — mid-flow workflow states become assertable**
  (docs/testing.md): a `transition:`/`dispatch:` suite case may declare `given:` —
  transitions fired before the target, in the same always-rolled-back transaction,
  through the same documented pipeline, so stamps, decisions, and state advances are
  real. A step that refuses fails the case naming the step and its code — never a
  half-seeded state — and each step may carry its own `principal:` (the requester
  submits, the manager approves), defaulting to the case's. The procurement suite
  gains the winner-path case the initial-state limitation previously excluded: the
  requester's submit stamps the lane, the manager's `submit_decision` dispatch fires
  `approve`.

- **Workflows join the editor's declared-symbol contract**: `tesseraql symbols` now
  emits a `workflows` array — each declared workflow with its source, line, and its
  transition and dispatch ids — and the VS Code extension (0.3.5) adds a *Workflows*
  explorer section (`workflow/` YAML and SQL), completion and go-to-definition for
  `workflow:` values (the suite `transition:`/`dispatch:` targets), and snippets for
  the workflow shape and both suite targets (`tql-workflow`, `tql-test-transition`,
  `tql-test-dispatch`). A pre-0.10 CLI omits the array and the extension degrades to
  empty, the established contract-evolution rule.

- **The `current_state` literal lint — the other half of the typo class**
  (docs/transition-engine.md slice 4): `TQL-WORKFLOW-3115` (warning) flags a
  `current_state` string literal in SQL referencing the managed
  `tql_workflow_instance` table that names no declared workflow state — the exact
  sibling of `TQL-WORKFLOW-3114`, one column over, and far more frequent in real SQL.
  When the file pins exactly one declared `doc_type`, the check narrows to that
  workflow's states, so a real state of the wrong workflow still warns.

- **The `dispatch:` suite target — the one-button selector asserted as data**
  (docs/transition-engine.md slice 3): a declarative case runs the dispatch's
  member-selection loop inside its always-rolled-back transaction — the dispatch-level
  `decide:` once after the document binds, then each member through the documented
  transition pipeline, refused attempts rolling back to savepoints and falling
  through. The outcome row names the winner (`transition`, `dispatch`, `from`/`to`);
  none held is a `code: TQL-WORKFLOW-3202` row with `attempted` comma-joining the
  members tried. The procurement demo's `propose` pair drops its duplicated member
  `decide:` blocks in favor of the dispatch-level one, and the suite gains a dispatch
  refusal-aggregation case.

- **Engine-level dispatch — typed fall-through, a dispatch-level `decide:`, and a 422
  that explains itself** (docs/transition-engine.md slice 2): the dispatch route is now
  a governed route carrying the members' shared security spec, and its selector invokes
  each member's own command processor in-process — a `TQL-WORKFLOW-3201`/`3202` refusal
  falls through **as the typed exception it is**, never matched against a rendered HTTP
  body, and the `direct:<workflow>.<transition>.attempt` shadow routes are deleted. The
  none-held `422` now names each attempt's refusal (`attempted[].code`, and the SQL
  guard file's declared code as `attempted[].guard`); the success payload names the
  winner (`"transition": "<member>"`). A dispatch may declare `decide:` — evaluated
  once, before the member loop, after the document binds; decide-less members inherit
  the results as `decision.*` (a member alias colliding with a dispatch alias lints as
  `TQL-WORKFLOW-3112`, and the consumption lints see inherited aliases).

- **`TransitionExecutor` — the transition pipeline becomes an engine object**
  (docs/transition-engine.md slice 1): the documented pipeline — document load,
  `decide:`, state legality, guard (both forms), task authority, conditional advance,
  `stamp:`, the zero-row command contract — now lives once, in
  `io.tesseraql.yaml.workflow.TransitionExecutor`; the synthesized transition routes
  and the declarative suites' `transition:` target both delegate to it, so transition
  semantics change in exactly one place. Behavior-frozen: codes, payload keys, and
  suite outcomes are unchanged. `ColumnWorkflowStore` moved beside it
  (`io.tesseraql.yaml.workflow`), and rest-dsl `inlineRoutes` is now pinned explicitly
  at both `restConfiguration()` sites — topology is a choice, not a Camel default.

- **The `doc_type` literal lint — the typo dies at build time**
  (docs/workflow-expressiveness.md slice 4): `TQL-WORKFLOW-3114` (warning) flags a
  `doc_type` string literal in application SQL referencing the managed
  `tql_workflow_instance` table that names no declared workflow `document.type` —
  today that typo survives to runtime as an always-empty join. Route SQL, workflow
  guard files, rules, and scope fragments are scanned alike; SQL that never mentions
  the managed table (an application's own `doc_type` column) is out of scope.

- **One-action dispatch — the client calls one endpoint, the engine picks the lane**
  (docs/workflow-expressiveness.md slice 3): a workflow's `dispatch:` declares a named
  action over guarded member transitions (`oneOf:`); `POST {basePath}/{key}/{dispatchId}`
  tries the members in declaration order — each attempt runs the member's own full
  pipeline (security, decide, guard, advance, scoped command) in its own transaction
  via an internal `direct:<workflow>.<transition>.attempt` shadow route — and adopts
  the first outcome that is not a wrong-state (`TQL-WORKFLOW-3201`) or guard (`3202`)
  refusal. No member holding answers `422` naming every attempted member. Lints:
  `TQL-WORKFLOW-3112` (error — at least two members, members exist, one shared `from`
  state, one shared effective security spec, no dispatch/transition id collision) and
  `TQL-WORKFLOW-3113` (warning — an unguarded member that is not last makes its
  followers unreachable). Hot reload rebuilds dispatch and shadow routes with the
  workflow. The procurement demo's three lane pairs convert: requisition
  `submit_decision` (approve/advance), order `submit_decision` (issue/approve_issue)
  and `propose` (propose_accept/propose_review) — the client-side lane knowledge goes.

- **Decision stamps — the engine persists what the decision decided**
  (docs/workflow-expressiveness.md slice 2): a transition's `stamp:` maps document
  columns to `decision.*`/`document.*`/`principal.*` paths or literals; the engine
  issues one UPDATE in the transition's transaction — after the state advance, before
  the author command — and refreshes the in-memory document so later reads see the
  stamped value. `stamp: {column: null}` is the declared rework clearing. Lint
  `TQL-WORKFLOW-3111`: columns must be plain identifiers, a `decision.*` value must
  name a declared `decide:` alias, and a dotted value outside the whitelist warns
  before being stamped as a literal. The `transition:` suite target applies stamps
  identically. The procurement demo's three hand-written stamp commands (submit ×2,
  rework ×2) shrink to audit notes — the forgot-to-clear-the-lane bug class goes
  with them.

- **SQL guard files — the guard asks the database**
  (docs/workflow-expressiveness.md slice 1): a workflow transition's `guard:` gains a
  file form, `guard: {file: …, code: …, message: …}` — a 2-way **query** evaluated on
  the transition's connection after `decide:` resolution. Rows pass; no rows fails
  `422` carrying the declared code (and optional `messages/` key) as
  `guard`/`guardMessage` in the payload, so a refusal names itself instead of
  surfacing as a generic conflict. Lints: a guard declares exactly one form
  (`TQL-WORKFLOW-3108`), the file must exist (`3104`) and must be a query — a guard
  never writes (`3109`). The `transition:` suite target evaluates both forms, a
  guard-file refusal answering `guard: <code>` as data. The procurement demo converts:
  the quote submit guard becomes `quote-priced.sql` and the `total_lines`/
  `priced_lines` counters (plus the counter-refresh step on every pricing write) are
  **deleted**; the ship gate moves from an exists-in-WHERE command into
  `shipment-registered.sql` with its own refusal code.

- **The `transition:` suite target — the state machine, asserted declaratively**: a
  test case fires one declared workflow transition against a named document, inside
  the case's rolled-back transaction, through the documented pipeline (state
  legality, `decide:` after the document binds, the guard, the conditional advance,
  the scoped command, the zero-row contract). An advance answers `from`/`to`, a
  refusal answers a `code` row (`TQL-WORKFLOW-3201/3202/3204`,
  `TQL-DECISION-4720/4721`) — every posture assertable as data, with `verify:`
  read-backs observing the uncommitted command under the case's `principal`. The
  procurement suite now proves its manager-lane stamp and a wrong-state conflict
  without HTTP.

- **`tesseraql token` — the smoke-test loop's missing tool**: mints a development
  bearer token signed with the app's configured HS256 secret. Roles land under the
  configured `rolesClaim`, permissions under `permissionsClaim`, `--claim name=value`
  adds custom claims (a JSON-looking value — `'["a","b"]'`, `7`, `true` — embeds
  structurally), `--ttl 30m/12h/7d` bounds the lifetime. Development only by
  construction: an app that verifies asymmetrically (publicKey/JWKS) has nothing the
  command could sign with, and it says what it signed on stderr. The procurement
  demo's tour now mints its five persona tokens in five lines.

- **`serve --watch` covers the whole authoring surface** (the procurement demo's own
  dev-loop friction, generalized): the watcher now also registers `workflow/` and the
  shared-definition trees (`decisions/`, `rules/`, `scope/`, `domains/`). A
  shared-definition edit rebuilds every route (those definitions bake into any route
  that references them — cheap-and-correct over per-route reference graphs), a
  workflow edit rebuilds its synthesized transition routes in place, and the reload
  result names them (`<workflow>.<transition>`). Studio's Apply and the manual reload
  hammer ride the same reloader, so all three entry points gain the scope at once.
  Jobs, consumers, and `config/` changes still need a restart.

- **Suites run as a principal, so scoped SQL is finally testable**
  (docs/testing.md "Writing a suite"): a declarative test case may declare
  `principal:` (subject, loginId, roles, permissions, groups, claims) — the shape
  every authentication mechanism produces. The runner resolves `/*%scope … */`
  directives through the app's `scope/` declarations with the production resolver
  (matching arms bind the principal's claims, no matching arm renders `1=0`) and
  seeds the `principal.*` ambient paths, so one case per role proves each scope
  posture against a real database and the `data-scope` coverage kind becomes
  earnable. `CompiledScopeResolver` moved from `tesseraql-compiler` to
  `tesseraql-identity` (`io.tesseraql.identity.scope`) so the Camel-free test runner
  shares the exact runtime arm matching.
- **procurement-app joins the gallery** (docs/procurement-demo.md, slice 1): the
  suite-scale demo's first slice — purchase requisitions with a two-input
  `approvalRoute` decision (amount × category) stamping a one-stage or two-stage
  approval lane, department-claim data scoping with deny-by-default, line items
  riding the header's scope through the join, and a `req.cost`-gated internal
  estimate on the JSON surface.

- **Suites provision the managed framework schema** the runtime would provision at
  startup (`tql_workflow_*`; `tql_org_unit`/`tql_org_closure` when
  `tesseraql.orgunit.mode: managed`), so app SQL that legitimately reads it — an
  inbox scope over the task table, a rule reading `tql_workflow_instance` — runs in
  a declarative suite against the same schema it sees on a request.
- **procurement-app slice 2 — the RFQ leg**: a second managed workflow (draft →
  submitted → issued → closed) whose creation route enforces "approved requisitions
  only" through a shared rule reading the managed workflow state; supplier
  invitations with an idempotent invite; and a quote-collection follow-up task with
  an engine deadline (168h, sweeper reassigns to the procurement head) plus
  assignment/escalation mail through the outbox.

- **procurement-app slice 3 — the supplier portal**: suppliers log into the same app
  with a `partner` claim and reach only their own partner's rows
  (`scope/quotes_scope.yml` — a competitor's quote is outside their row reach, not
  merely hidden). Starting a quote copies the requisition's lines in one two-step
  transaction with a deterministic id (restarts are no-ops); pricing maintains the
  counters an **app-mode status-column quote workflow**'s submit guard reads — both
  workflow modes now live side by side in one app, and a cross-partner transition
  updates zero rows and fails, deny-by-default.

- **procurement-app slice 4 — comparison to order**: the comparison ranks submitted
  quotes with each one's distance from the lowest; creating an order computes and
  stamps the selection facts (total, lowest-or-not, % above lowest) in SQL — never
  client-asserted. A non-lowest pick demands a written reason (shared rule, 422
  before anything writes), and the `orderApproval` decision routes the submit:
  lowest or within 3% issues with no human in the loop (the assign resolver returns
  zero assignees, so no task opens), anything above waits for the procurement head.

- **procurement-app slice 5 — delivery-date negotiation**: the supplier proposes a
  new date (the slip computed server-side against the ordered promise) and the
  **table-backed `deliveryAutoAccept` decision** judges it — the tolerance is
  business data in `delivery_tolerances`, maintained at runtime, and the very next
  proposal is judged by the new rows with no deploy. Within tolerance the proposal
  confirms with no human in the loop; outside it a review task opens for whoever
  placed the order (accept, or decline back to `issued`). Supplier-facing
  transitions carry per-transition `security:` overrides over the buyer-side
  workflow default, with the command's scope as the row authority.

- **procurement-app slice 6 — shipment to receipt**: the supplier registers one
  shipment per confirmed order (the split-shipment fence is a `unique` constraint),
  the `ship` transition demands the registered row in its command's WHERE, and the
  requester who started the chain closes it with `receive` — stamping the shipment
  in the same transaction as the state advance. A `query-export` CSV covers shipped
  lines and a `/dashboard` reads the managed workflow state as rows.

- **procurement-app slice 7 — the demo speaks Japanese**: display names, titles, and
  budget labels become Japanese-first (a data pass; ids and assertions untouched),
  the shared rules carry localized messages (`messages/ja.yml` + `en.yml`), and the
  README gains the scripted three-login tour — one requisition walked from creation
  to goods receipt across requester, procurement, and two suppliers. With it the
  committed design (docs/procurement-demo.md slices 1–7) is fully implemented; the
  EDI companion remains an explicit open decision.

### Fixed

- **A managed-mode workflow transition whose command updates zero rows no longer
  advances the state** (`TQL-WORKFLOW-3204`, 409). docs/approval-workflow.md always
  promised that a satisfied guard with no authorized rows — a `/*%scope */` matching
  nothing, or an absent data state the WHERE demands — updates nothing and fails;
  app mode enforced it through the state column's own conditional UPDATE, managed
  mode silently advanced. Found by the procurement demo's ship-without-shipment
  probe; the same enforcement now also blocks a cross-partner `confirm`/`propose_*`
  on another supplier's order.
- `TQL-DECISION-4716` no longer flags a decision whose only consumer is a workflow
  transition's `decide:` block (previously only route documents counted as
  references, so the purchase-request archetype warned on every lint).

## 0.9.0 - 2026-07-31

### Added

- **Decision tables: one contract, two row sources** (docs/decision-tables.md, user
  guide in declarative-validation.md "Decision tables"): named, value-producing
  decisions — approval routing, fee tiers, assignment matrices — declared once under
  `decisions/` and referenced from a command's or a workflow transition's `decide:`
  block. Rows live inline as YAML (release-versioned policy, with build-time overlap /
  shadowing / enum-typo checks) or in an app-owned table (`source:`) business users
  maintain at runtime, evaluated as one generated SELECT inside the operation's
  transaction — `in` via a normalized child table, `orgSubtree` via the managed org
  closure, dated rows via `effective:` + `effectiveAt:`. Outputs publish as
  `decision.<alias>.<output>` for SQL binds, `/*%if */` directives, the new step
  `when:` guards (a falsy guard skips the step, recording `steps.<name>.skipped`), and
  workflow guards/assignee resolution; a lookup never resolves to silent nulls
  (`TQL-DECISION-4720/4721`). Enum-typed outputs buy consumption-side proofs: comparing
  against a value the decision cannot produce is `TQL-DECISION-4713`, and a state whose
  guarded transitions leave declared values unhandled is `TQL-DECISION-4712`.
  `scaffold decision` generates the declaration plus the typed backing-table migration;
  the purchase-request gallery app carries the worked archetype, and the docs portal
  gains a Decisions page next to Domains and Rules. Declarative suites test the table
  itself with a `decide:` case (params in, matched outputs out, misses assertable as
  `code:` rows), counted by the new `decision` coverage kind. The editors keep up:
  `tesseraql symbols` emits declared decisions and the VS Code extension (0.3.3) adds a
  Decisions tree with `use:`/`decision:` completion and go-to-definition; Studio adds a
  decide-snippet builder, a YAML-rows grid editor (validated before it ever persists,
  through the draft flow), a data-browser overlay naming each decision-backed column's
  role, and "backs decision" chips on schema pages. The route form also stops dropping
  a chosen field domain on save (`in<i>domain` was never wired through).

- **Ambient framework state can ride its own pool or database**
  (docs/framework-datasource.md): `tesseraql.framework.datasource` (default `main`)
  points sessions, credential tokens, SAML replay / OIDC flow state, rate leases, route
  audit and preferences at any named datasource — same-DB/separate-pool isolates login
  from business-query saturation with zero migration. Transactionally-coupled stores
  (outbox, workflow, idempotency markers, webhook replay) deliberately ignore the key:
  a config line must not be able to break outbox atomicity. An unknown name refuses the
  boot (`TQL-APP-5205`). Bundled session-store hardening: an `expires_at` index (the
  login-path prune scanned), and `rotate()` is now a single transaction — the crash
  window in which an elevated session's old id stayed alive is closed.

- **Guessing has a budget** (docs/credential-throttle.md): the credential surfaces —
  login (password+TOTP), password-reset request and confirm, invite acceptance, and the
  OIDC/SAML callbacks — now throttle failed attempts, keyed per submitted login id
  (10/15m, checked before any existence check or hashing: no enumeration oracle, no
  hash burn) and per presented address (100/15m). Failures only — the 9am rush behind
  one NAT never throttles — a success clears the login budget, and there is no lockout:
  windows expire on their own. On by default; tune or disable under
  `tesseraql.security.credentialThrottle`. Browsers see the login page's rate message,
  API callers get `429` `TQL-RATE-4292` with `Retry-After`, and hits count into
  `tesseraql_credential_throttled_total{surface,key}` on the scrape.

- **Session policy is a declaration, not a framework opinion**
  (docs/session-visibility.md addendum): `tesseraql.sessions.maxPerSubject` caps live
  sessions per account with evict-oldest semantics — the newest login wins, a stranded
  or stolen session is pushed out, and `maxPerSubject: 1` is the single-session policy;
  rotation never trips the cap. Newly scaffolded apps declare `idleTimeout: 30m` in
  their config visibly (existing apps are untouched; the framework's code default stays
  unset through 0.x).

- **Sessions are devices you can see and end** (docs/session-visibility.md): each session
  now records a public handle, the login user agent and presented address, and a
  last-seen instant — living and dying with the session row (`V3__session_metadata.sql`).
  New `tesseraql.sessions.idleTimeout` ends a session unseen for that long, sliding
  inside the absolute `ttl` (touches are throttled to once per 60s per session).
  `SessionStore.create` now takes a `ClientInfo` (login, OIDC and SAML all record it —
  pre-1.0 signature change, old form removed), stores gain
  `invalidateByHandle(subject, handle)` and `activeSessions(limit)`, and rotation carries
  the metadata forward so an elevated session does not look freshly created.

- **Every session surface shows the device and can end it**: the account page's
  self-service list and the IAM Admin user panel gain last-active/device/address columns
  and a per-row *Sign out* — self-service via the new `POST /_tesseraql/logout-device`
  (revoking the device that is this browser is an ordinary sign-out), admin-side via the
  subject-scoped handle routes. A new IAM Admin *Sessions* page
  (`/_tesseraql/admin/sessions`, `iam.admin.view`) lists every live session across
  subjects with a subject-prefix filter, per-row sign-out, and links to each user page.

- **The ops console covers what its machinery already knew** (docs/ops-console-coverage.md):
  an always-mounted *Audit* page renders the business-route audit trail with the same
  scope narrowing and policy as the JSON API — and names
  `tesseraql.audit.routes.enabled` honestly when the store is off; the overview gains a
  health panel (the `health()` roll-up, per-datasource probe badges) and the deployed
  version; and manual job runs take their declared parameters — the jobs page renders
  inputs from the `params:` declaration, posted `param.<name>` fields ride to the runner
  whole, and `bindJobParams` stays the single validation point, refusing a missing
  required parameter before the job starts.

- **The poll-source registry reaches the scrape** (docs/poll-source-metrics.md):
  `/_tesseraql/metrics` now carries per-node poll-source gauges rendered from the registry
  at scrape time — `tesseraql_poll_source_wired` (0 = refused at wire time),
  `tesseraql_poll_source_consecutive_failures`, and
  `tesseraql_poll_source_last_poll_age_seconds` (absent until a poll completes), `jobId`
  as the only label — so a silent poll source is alertable without anyone watching the
  console. `http-call` egress refusals (`TQL-BATCH-5305`) additionally count into
  `tesseraql_egress_denied_total{host=...}` on the existing meter, giving denial *rates*
  an alertable shape after a config rollout.

- **A dead model field turns the build red** (docs/yaml-surface-consumers.md): a
  reactor-wide guard walks every module's compiled classes with method-level attribution
  (ASM, test-scoped in `tesseraql-maven-plugin`) and fails when a YAML-model record
  component has no behavioral consumer — separating the canonical accessor every record
  carries from the derived-accessor chains the model actually defaults through, and from
  display-only reads. Deliberate exceptions live in a probed registry
  (`DISPLAY_ONLY`/`UNWIRED`, both empty today) whose entries must stay true to pass.

- **The session id rotates in place on elevation** (docs/session-rotation.md): a new
  `response.session.rotate: true` directive re-issues the caller's session cookie after a
  successful execution — a fresh id and CSRF token, the old id invalidated before the
  response leaves — closing the ASVS residual where a non-credential elevation kept the
  pre-elevation id alive. The account app's TOTP enrollment confirm declares it; bearer
  and public callers on a rotating route are untouched. `SessionStore` gains a default
  `rotate(sessionId)`, and the auth component gains the `rotate` operation beside
  authenticate/authorize.

## 0.8.0 - 2026-07-26

### Added

- **No authored YAML is editor-blind** (docs/vscode-extension.md, schema completion): three
  new shipped JSON Schemas cover `config/tesseraql.yml` (security path rules, response
  headers, policies, poll connectors, sessions, metrics, audit, outbox, retention — with the
  same descriptions the reference renders), `tests/**/*.yml` suites, and
  `messages/<locale>.yml` catalogs; `tesseraql new` lands and associates all three. The
  route/job schema stops stubbing what the model accepts: `trigger:` (schedule + poll),
  `params:`, `perTenant:`, `idempotency:`, and `policy:` gain real properties — `policy`'s
  description had drifted to describe row-authority scoping instead of admission — and the
  `security.provider` / `response.stream.contentType` keys retired from the model earlier
  leave the schema too. A new `SchemaSyncTest` guard reflects `RouteDefinition` and
  `JobDefinition` against the schema root, closing the drift class that let those keys sit
  undocumented (it caught `perTenant` on its first run).

- **Poll sources report their health** (docs/poll-source-status.md): a per-node registry
  tracks every poll-triggered job — a source refused at wire time (egress-denied host,
  missing credential or trust store, no `import:` block) now reports *not polling* with the
  reason, instead of only a startup log line, and each polled file stamps the last import's
  outcome and failure streak. The ops console jobs page gains a *Source* column, and a
  skipped or repeatedly failing source raises the new `TQL-OPS-9007` operational alert,
  riding the existing alert surface and outbox notifier.

- **Session administration in IAM Admin, and disabled means disabled**
  (docs/session-administration.md): the user detail page gains an *Active sessions* panel
  (sign-in and expiry times only — session ids never reach a template) with a confirm-gated
  *Sign out everywhere* action (`iam.admin.write`), and disabling a user — the per-user
  action and the bulk toolbar alike — now ends every session of that subject immediately. A
  revoked account previously kept browsing until its cookies expired.

- **The schema sidecar lifecycle closes inside Studio** (docs/studio-schema-lifecycle.md): a
  *Refresh schema* button on the docs schema page introspects the runtime's own datasources
  live and rewrites `.tesseraql/docs/schema.json` — the SQL builder, migration DDL builder,
  and docs pages stop depending on an out-of-band `tesseraql:schema` run; and a *Capture
  baselines now* button on the release-diff page writes `schema.baseline.json` and
  `openapi.baseline.json` in one action, replacing the hand-copy instructions (including one
  that named `.tesseraql/docs/openapi.json`, a file nothing writes at runtime). Both are
  edit-gated, path-confined, and audited (`schema-refresh` / `baseline-capture`); capturing
  with no schema sidecar refuses with the new `TQL-STUDIO-4236` (409). The schema page's
  empty state now distinguishes a corrupt sidecar from an absent one.

- **A Jobs page in the ops console, and executions know who started them**
  (docs/ops-console-actions.md): `/_tesseraql/ops/console/jobs` lists the scope-filtered job
  catalog — trigger, owning app, last run — with a *Run* button per job (`ops.batch.run`) that
  starts it now and lands on the new execution's detail page. `tql_job_execution` gains a
  `triggered_by` column (V3 framework migration): manual runs — console and JSON API alike —
  record the principal's login id, and the execution page shows it. Scheduled firings are now
  recorded as `trigger_type = 'schedule'`; they previously rode the shared runner's hardcoded
  `manual`, making every cron firing look operator-initiated.

### Changed

- **`JobExecutor.run`, `JobRepository.startExecution`, and the internal `JobRunner` contract
  carry the trigger facts** (`triggerType`, `triggeredBy`) instead of a hardcoded string;
  `JobExecution` gains the `triggeredBy` component. Embedders constructing these records or
  calling these APIs must pass the new arguments (pre-1.0, no compatibility overloads).

### Added

- **The ops console redelivers dead outbox events** (docs/ops-console-actions.md): the outbox
  page's DEAD rows carry a *Redeliver* button — a plain CSRF-guarded form posting to the
  console's first write route (`ops.batch.run`, the same policy as the JSON API it fronts),
  requeueing the event and flashing confirmation. The page previously printed the `curl`
  command for the API instead of offering the action; not-yet-dead FAILED events stay
  button-free — they are the dispatcher's to retry. An error page rendering `TQL-BATCH-4040`
  now answers 404 (it fell through to 500), matching the JSON API's Not Found for the same
  code.

- **The VS Code extension publishes itself** (docs/vscode-extension.md): pushing an
  `ext-v<version>` tag runs the new *Extension release* workflow — tests, a
  manifest-version-matches-tag gate, one `vsce package`, then `vsce publish` of that exact vsix
  to the Visual Studio Marketplace and a GitHub release with the same vsix attached. The
  extension versions independently of the framework, so publication rode no `v*` release and
  stayed a manual operator step; running the workflow manually is a dry run that proves the
  `VSCE_PAT` secret is still valid without publishing anything.

- **The editor knows the shared definitions** (docs/vscode-extension.md): `tesseraql symbols`
  now prints the field domains declared under `domains/` and the validation rules declared under
  `rules/` (name, file, line), and the VS Code extension (0.3.2) completes and go-to-defines
  `domain:` and `use:` values over them, exactly as it already did `policy:` and `message:`. The
  explorer gains *Domains* and *Rules* sections — shared definitions were referenceable
  everywhere but visible nowhere in the editor. A pre-0.8 CLI omits the two arrays and the
  extension degrades those features to empty instead of rejecting the document.

- **FTPS client certificates for poll sources** (docs/connectors.md): a poll credential may
  declare `keyStoreFile:` (with `keyStorePassword:` and an optional `keyStoreType:`) and the
  source presents it. Mutual TLS was unreachable: the trust store proved who answered and nothing
  carried a certificate the other way, so an FTPS server requiring one could not be polled at all.
  A password may accompany the certificate — mutual TLS and a login are separate questions a
  server may ask together.

- **Studio's validation builder can reference a shared rule** (docs/validation-rule-sets.md): a
  `use:` operation offers the rules declared under `rules/` by name and generates the reference
  with its whole bind contract laid out. The builder previously emitted only inline rules, so an
  author generating a SQL rule got an inline copy with unbound binds — nudged into exactly the
  duplication shared rules exist to eliminate, by the tool meant to help.

- **A shared validation rules page in the documentation portal**
  (docs/validation-rule-sets.md): every rule declared under `rules/` with its kind, bind contract
  and default code, and the routes referencing it with `use:`. validation-rule-sets.md promised
  this page and nothing built it, so "which routes share this rule" — the entire reason to declare
  one once — could not be answered from the portal. An unreferenced rule is marked, the same
  signal as lint `TQL-FIELD-4612`.

- **Key-based SFTP for poll sources** (docs/connectors.md): a poll credential may declare
  `privateKeyFile:` (with an optional `privateKeyPassphrase:`) instead of `password:`. Exactly one
  method is required — declaring both is refused with `TQL-SEC-4089` rather than silently
  preferring one, and declaring neither now says so instead of failing later about a missing
  password. A private key on an `ftps` source is refused, since only SFTP can use one.

- **A guard on the framework's own HTTP surface** (docs/framework-surface-parity.md):
  `FrameworkSurfaces` records the framework routes that answer without an authentication step —
  `PUBLIC_BY_DESIGN` with a reason, `PROCESSOR_ENFORCED` naming the method that enforces the gate —
  and a test starts a context with Studio, metrics, MCP and SCIM all mounted, reads each route off
  the model, and fails on one that neither authenticates nor appears there. The audit this closes
  found two framework routes shipped without the gate their siblings had, and neither was caught in
  review because an absence looks like nothing.

- **Shared definitions on the published surface** (docs/reference-yaml-surface.md): `domains/`
  and `rules/` documents now ship their own JSON Schemas, are associated in a scaffolded app's
  `.vscode/settings.json`, and appear in the generated YAML-surface reference alongside routes.
  `domain:` on an input field and `use:`/`params:`/`field:`/`when:` inside `validate:` are
  documented rather than merely accepted — the route schema had described `validate:` as "one of
  rule: or file:" since before shared rules existed. `SchemaSyncTest` now asserts property
  coverage against the model records, not just enum coverage, so the next key added to an input
  field or a rule fails the build instead of shipping undocumented.
- **The shared rule bind contract is checked against its SQL** (docs/validation-rule-sets.md):
  a rule set's `binds:` is now compared with what `rules/*.sql` actually binds at load time, and
  an expression rule may not declare a contract at all (`TQL-FIELD-4609`). Every *reference* was
  already checked against `binds:`; `binds:` itself was checked against nothing, so adding a bind
  to a shared rule's SQL and forgetting the contract passed load and lint on every referencing
  route and failed on the first request that triggered the rule. Ambient binds (`principal.*`,
  `audit.*`) are excluded, from the framework's own list rather than a second copy of it. A
  route-local rule whose expression repeats a shared one is now the warning the design promised
  (`TQL-FIELD-4613`).

- **Path-matched route security defaults** (docs/authentication.md "Route security defaults"):
  `tesseraql.security.defaults.routes` declares firewall-style rules (`match` glob over the
  served URL path, first match wins) that fill a route's `auth`, `csrf`, and `policy` when the
  route leaves them out; route-local keys always win, a `public` route never inherits a policy
  (`TQL-SEC-4131` flags the combination), and `csrf: auto` requires CSRF exactly on browser
  writes. Resolution happens at manifest load, so the compiler, linter, coverage, and Studio all
  see effective values. A malformed rule fails the load (`TQL-SEC-4132`).

- **Ambient `principal.*` SQL binds** (docs/two-way-sql.md "Ambient binds"): queries, command
  steps, named queries, and validation SQL can bind the authenticated caller directly —
  `/* principal.loginId */`, `/* principal.tenantId */`, `/* principal.roles */` — without
  per-route `params:` wiring, generalizing the `audit.*` precedent. The namespace is closed
  (subject, loginId, tenantId, roles, permissions, groups; no raw-claim passthrough), a declared
  parameter named `principal` shadows it entirely, and a public route seeds nothing so a
  `principal.*` bind fails loudly as an unbound parameter.

- **Field domains** (docs/declarative-validation.md "Field domains"): named app-level field
  definitions under `domains/` — type, bounds, pattern, format, enum, classification, mask —
  referenced from any route's `input:` via `domain:`, plus an app-level `constraints:` catalog
  mapping database constraint names once. The manifest loader merges references at load time
  (route keys win; operational keys are rejected inside a domain, `TQL-FIELD-4602`), so binding,
  the error model, OpenAPI, and coverage consume fully-populated fields unchanged. Lint flags
  loosening overrides (`TQL-FIELD-4610`) and unreferenced domains (`TQL-FIELD-4611`); duplicate
  names and unknown references fail the load (`TQL-FIELD-4600`/`4601`).

- **App-wide default response headers** (docs/response-shaping.md "Default response headers"):
  `tesseraql.security.responseHeaders` declares the security header block once; the compiler
  merges it under every HTML response (pages, fragments, MCP UI resources), route entries win by
  name, and the literal value `unset` suppresses a default. Lint flags identical restatements
  (`TQL-SEC-4133`) and suppressed or wildcard-broadened defaults (`TQL-SEC-4134`); a malformed
  declaration is `TQL-SEC-4135`.

- **Ambient principal lint pair** (docs/ambient-params.md): a `principal.*` bind on a route
  that never carries an authenticated principal — public, no effective security, or a webhook —
  is an error (`TQL-SEC-4136`); a `params:` entry that merely renames an ambient field draws a
  migration nudge toward the ambient spelling (`TQL-SEC-4137`).

- **Scaffolded field domains** (docs/field-domains.md): `scaffold crud` now generates
  `domains/<table>.yml` — the DDL-derived field knowledge (types, VARCHAR sizes, temporal parse
  formats) plus the unique-constraint catalog — and routes that reference the domains with only
  their operational `required:` choice. Re-scaffolding after a schema change updates one file,
  not every route; the scaffold-demo gallery is regenerated, and the inventory app hand-adopts a
  shared `sku` domain that had already drifted across its two write routes (only one carried the
  pattern).

- **`TQL-SEC-4137` scopes to SQL-file params**: the ambient-spelling nudge no longer fires on
  `sql.service:` invocations — service params are the service's arguments, not SQL binds (the
  bundled Studio/account apps wire `principal.*` into services exactly this way, by design).

- **Field domains in the OpenAPI contract** (docs/field-domains.md): a pure `domain:` reference
  emits a named component schema (`domain.<name>`) `$ref`'d from every operation accepting the
  field — one business field, one schema; a route that tightens a domain key keeps its inline
  schema so the contract never hides an override.

- **Field domains on the documentation portal** (docs/field-domains.md): a route input's
  constraint chips lead with its `domain` reference, and the route-spec JSON carries the field —
  the shared identity reads first wherever a route's contract is browsed.

- **Bundled apps rely on the default response headers** (docs/route-defaults.md): the
  Studio/IAM-admin/ops-console/account/auth-ui bundled apps declare `security.responseHeaders`
  once in their own `config/tesseraql.yml` — the one whitelisted key a mounted app's config
  contributes (everything else stays inert, so a third-party app can never override host
  datasources or policies) — and their 72 route files drop the copy-pasted header block (pages
  needing a different CSP or `Cache-Control` keep just that override).

- **Field domains in Studio** (docs/field-domains.md, closing the design): a Domains reference
  page in the documentation portal (declarations with constraint chips, the routes referencing
  each, the constraint catalog, and an `unreferenced` marker mirroring `TQL-FIELD-4611`); a
  per-column domain chip on schema table pages via the scaffolder's `<table>.<field>` naming;
  a domain select per input row in the route form (saved as `domain:` ahead of the row's own
  tightening keys); and the validation builder points single-field constraints at domains
  before a cross-field rule is written.

- **Dead scaffolded config retired; pool size wired** (docs/config-consumers.md): the
  `tesseraql new` skeleton no longer emits `runtime.engine`, `runtime.profile`
  (`TESSERAQL_PROFILE` was a misleading twin of the real `TESSERAQL_ENV` +
  `config/env/<profile>.yml` overlay mechanism, which the template now points at),
  `java.baseline/compatibility`, or the inert `datasources.main.type` — none had a consumer.
  `db.main.maximumPoolSize` now actually reaches the connection pool (the scaffold maps it
  into `datasources.main.maximumPoolSize`, the key `DataSources` reads); previously the value
  was silently ignored. Gallery configs cleaned to match.

- **Camel component guard** (docs/component-guard.md): the previously unread
  `tesseraql.camel.components` block is now enforced. A built-in baseline refuses
  `exec`/`script`/`groovy`/`class`/`language`/`bean` at component-registration time — config or
  not — failing boot with `TQL-SEC-4138`; `denied:` adds to the baseline, `allowed:` narrows
  beyond the framework's own components, and a re-allow attempt is ignored and linted
  (`TQL-SEC-4139`). The scaffold emits guidance instead of the dead lists.

- **`tesseraql.app.work` honored everywhere; scaffold⇄consumer drift test**
  (docs/config-consumers.md): the shared `WorkHome` resolver relocates the work tree for the
  manifest index pruner, mounted-app materialization, packaging output, test/coverage reports,
  module and DuckDB-extension caches, and the embedded-db marker — previously the key and
  `TESSERAQL_WORK_HOME` were read by nothing. `ScaffoldedConfigKeys` registers a consumer for
  every key the `tesseraql new` templates emit, and its drift test renders the real templates
  and fails the build on an unregistered key ("wire it or don't emit it"), with an honesty
  probe on each registered consumer file.

- **Bundled apps rely on the security defaults** (docs/route-defaults.md, closing the design):
  Studio, account, IAM-admin, and ops-console declare one `security.defaults.routes` rule each
  and their 112 route-file `auth:`/`csrf:` lines are gone; deliberately public routes keep
  their explicit declarations, auth-ui stays fully explicit, and
  `BundledAppSecurityPostureTest` pins every bundled route to an explicit effective auth mode
  with CSRF on browser writes.

- **Shared validation rule sets** (docs/declarative-validation.md "Shared rule sets"): a
  cross-field or SQL rule is declared once under `rules/` with a bind contract and referenced
  from any `validate:` block via `use:` — the reference wires its own `params:` (checked
  against the contract exactly), `field:`, and `when:`, while the rule's substance and default
  `code`/`message` live in the set. `scaffold crud` generates the per-unique-index `…IsFree`
  rule shared by create and update (self-exclusion via a conditional directive, portable across
  dialects), exercised end-to-end by the dogfood suites. Load errors `TQL-FIELD-4604..4608`;
  unreferenced rules lint `TQL-FIELD-4612`.

- **Rule-set generation completed** (docs/validation-rule-sets.md, closing the design):
  `scaffold crud` also generates a `…Exists` rule per single-column foreign key (referenced by
  create and update, `when:`-guarded for nullable columns; composite keys skipped like
  composite unique indexes), and the purchase-request gallery app carries the hand-authored
  duplicate-application archetype — its SQL identifies the caller via the ambient
  `/* principal.loginId */` bind, so the contract is a single `title` bind, exercised by two
  declarative suite cases.

### Fixed

- **OpenAPI and MCP describe an array input's elements** (docs/reference-yaml-surface.md): the
  OpenAPI document emitted `type: array` and stopped, and an MCP tool's declared array fell
  through to `"string"` — so a model was told to send text where the framework rejects anything
  but a list, a rejection it cannot diagnose because the schema it was given is what it followed.
  Both now carry the element type and enum that `items:` declares and the binder enforces.

- **The route page shows every constraint an input declares** (docs/documentation-portal.md):
  `pattern`, `minLength` and `requiredWhen` were rendered by OpenAPI and the Domains page but not
  by the route page, so an input carrying a real constraint read as unconstrained exactly where a
  reviewer decides whether it is safe. A validation rule also now carries its `use:` and `code:`,
  so a rule that references a shared one says which one and under what code.

- **A row-scope directive in batch SQL is refused rather than ignored** (docs/data-scoping.md):
  pinned rather than changed. `JobExecutor` passes `ScopeResolver.UNSUPPORTED`, so a
  `/*%scope%*/` in a job's SQL fails with `TQL-SQL-2106` instead of rendering unscoped and
  returning every tenant's rows — the safe posture, and one that a matrix cell reading "absent"
  made indistinguishable from a hole.
- **File transfers take the dialect variant and the query timeout** (docs/file-transfers.md):
  `JdbcFileTransferService` read its declared SQL file directly and never asked the datasource its
  vendor, so an `x.postgresql.sql` beside `x.sql` was never opened; and it set no query timeout,
  so an export query or an after-SQL statement held a pooled connection for as long as the driver
  allowed. Both are the same gaps the batch executor had, for the same reason — it resolves its
  own paths and prepares its own statements instead of going through the producer.

- **Batch SQL runs under the app-wide query timeout** (docs/batch-jobs.md): `JobExecutor` prepared
  its statements and never set one, so a job step's SQL ran for as long as the driver allowed while
  holding a pooled connection — where the same statement on a route or inside a command has been
  bounded by `tesseraql.sql.timeoutSeconds` all along. A job is where a runaway statement goes
  unnoticed longest, because nobody is waiting for the response.
- **Batch steps run the dialect variant beside their SQL** (docs/batch-jobs.md): `JobExecutor`
  resolved the SQL path itself and never asked the datasource its vendor, so an
  `x.postgresql.sql` sitting next to `x.sql` was never opened and the generic file ran instead —
  silently, which is the failure a dialect variant exists to prevent. The vendor is read once per
  pool and cached, since reading it costs a pooled connection.

- **A job's declared `params:` are bound before it runs** (docs/batch-jobs.md): they were accepted,
  documented with a shipped example, and never read, so whatever the caller sent reached the job's
  SQL uncoerced — a numeric parameter arrived as its text — and a missing required parameter
  surfaced later as an unbound SQL parameter, an error naming the SQL rather than the input. All
  three entry points (the operations API, `runJob`, and the per-tenant variant) share one binding.

- **A declared array input keeps its elements, and they are validated** (docs/reference-yaml-surface.md):
  the binder read every request value as text, so a JSON body's list became `String.valueOf(list)`
  — `[{productId=10, quantity=1}]` — as the input's effective value, and any binding reading
  `params.<name>` put that text into SQL. Routes that read `body.<name>` saw the real list, which
  is why this went unnoticed. `items:` is now honored too: elements are coerced to the declared
  item type and checked against an element enum, naming the offending index, and a non-list value
  for a declared array is refused rather than stringified.

- **A remote poll source without a credential is refused** (docs/connectors.md, `TQL-SEC-4088`):
  it was accepted and produced an endpoint URI with no username and no password, so SFTP failed at
  connect with a message about the server while FTPS could succeed as an anonymous session — a poll
  job quietly reading whatever anonymous access allows. Neither outcome named the declaration as
  the incomplete part.

- **OIDC and SAML answer the framework error envelope** (docs/framework-surface-parity.md): both
  returned `{"error": "<string>"}` — a shape no other endpoint uses, carrying no code an operator
  could search for — and both wrapped every failure in `onException(Exception.class)` that answered
  400, so a broken IdP, an unreachable JWKS endpoint and a genuinely malformed callback were all
  reported as the caller's fault. They now share `FederationErrors`: an authentication failure is
  `TQL-SEC-4011` (401), a `TqlException` keeps its own code and status, and an unexpected failure
  is `TQL-SEC-4140` (500) with the detail in the log rather than the response.

- **The multi-app gateway releases its client and executor on close** (docs/multi-app.md): it
  stopped the HTTP server and closed the hosted app, and left behind the outbound `HttpClient` —
  its connection pool and selector thread — along with the virtual-thread executor created inline
  and never referenced again. A host that restarts a gateway accumulated both. The unbounded
  request-body buffering on that surface is a separate row and still open.
- **The multi-app gateway bounds the request body it buffers** (docs/multi-app.md): it read the
  whole body with `readAllBytes()` before forwarding, so a stranger decided how much of the front
  door's heap to take. Bodies over 10 MB are now refused with 413. A fixed ceiling rather than a
  config key, deliberately: the gateway fronts several apps, so a per-app limit would be ambiguous
  here — this is the door's own bound, and the app behind it keeps whatever limits it declares.

- **MCP sessions expire and are capped** (docs/mcp.md): the handler kept session ids in a set that
  `initialize` added to and only an explicit `DELETE` removed from, so a client that reconnects
  instead of closing — which is what a crashed or restarted one does — grew it for the life of the
  process. Sessions now carry an idle TTL (two hours) with a 10,000 ceiling behind it, and use
  refreshes the window rather than total age counting against it.
- **The queue consumer's send template is stopped with the context** (docs/messaging.md): it was
  created lazily and never stopped, and a `ProducerTemplate` holds a producer cache, so an app
  close or a reload left its endpoints and their connections behind. Neither owner — a
  `PgNotifyListener` and a route builder — had a close path to add one to, so the template is now
  registered with the context, which stops it. The lazy creation was also unsynchronized: two
  threads reaching the unset field both built one, and the loser was leaked silently.

- **An invalidated session ends an already-open SSE stream** (docs/security-hardening.md): the
  stream authenticated once at connect and never looked again, so "sign out others" and a password
  change left an open stream delivering data for up to its fifteen-minute lifetime — against the
  claim that a credential change evicts a parallel session. Every frame now re-checks the session
  and closes the stream when it is gone. The refusal envelope also stops concatenating the internal
  exception message into JSON, which is the leak the Studio reload stub had; the code is returned,
  the detail goes to the log.

- **Static assets and SSE streams carry the app's `security.responseHeaders`**
  (docs/response-shaping.md): the block was applied by the HTML render, so the two surfaces that
  write their own responses — a hand-written asset route and the Vert.x SSE streams — left without
  it. An asset served without a CSP is a place to host what the CSP was written to prevent, and a
  stream cannot be given headers by a completion hook at all, since they must precede the first
  frame. Both now read the block from the registry and apply it where they write their headers. A
  404 from a path nothing mounts still answers without it, because no code of ours runs.

- **An ambient `principal.*` bind with no principal now fails** (docs/ambient-params.md,
  `TQL-SQL-2112`): the documentation promised it "fails loudly as an unbound parameter instead of
  binding null", and it bound null. A missing path segment evaluates to null like any other, so
  `where owner = /* principal.loginId */` on a request carrying no principal became
  `where owner = NULL` — a predicate matching nothing, or matching the rows whose owner is unset.
  A quiet wrong answer where the documentation promised a loud failure, and `TQL-SEC-4136` only
  covers routes whose posture is decidable statically. Only the whole namespace being absent is
  an error: a seeded `principal.tenantId` that is genuinely null still binds null.

- **An MCP tool's `emit:` reaches live views** (docs/realtime.md): `buildMcpTool` never added the
  topic-emit step, so `emit:` on a tool was accepted by the model, described in the reference, and
  did nothing — a model-driven write left every view watching the same data stale, while the
  identical declaration on an HTTP command worked. The step now runs in the same position it does
  for a command, after the write, so a rollback bypasses it. `lintTool` also never called
  `lintEmit`, so neither half of the omission was visible: the topic-name and query-recipe checks
  now apply to tools as they do to routes and queue consumers.
- **A file-export's `params:` reach its query** (docs/file-transfers.md): `RequestBinder` filled
  the SQL parameter map from `route.sql()`, which is null for a `file-export` — its binding lives
  at `export.sql` — so every declared param resolved to a silent null bind and the export returned
  whatever the comparison against null returns. A route-level `sql: {params:}` on the same route
  *did* reach the query because nothing rejected it, so the surface offered two spellings and
  honored the undocumented one.
- **A command's query-steps shape rows like every other read** (docs/transactional-writes.md):
  the SQL producer normalized column labels per dialect and converted JDBC temporals to ISO-8601,
  while a command's query-steps force-lowercased every label and passed `java.sql.Timestamp`
  through untouched. The same `select … as "orderTotal"` came back as `orderTotal` on a query
  route and `ordertotal` inside a command on Oracle, and a date rendered as `2026-07-25` on one
  path and as a driver-specific `toString()` on the other, so a response binding written against
  one path broke on the other. Both paths now ask `ResultRows`. **This changes what a command's
  query-step rows look like**: temporals are ISO-8601 strings, and a quoted mixed-case alias
  keeps its case on Oracle. Generated-key lookup stays case-insensitive — it matches a declared
  key, not a label a binding reads.

- **A polled file that fails to import lands in `moveFailed`** (docs/connectors.md): the import
  ran asynchronously — the transfer service spooled the bytes, recorded the transfer and handed
  the work to an executor — so the Camel exchange completed before a single row of SQL had run
  and the consumer archived the file into `move` (`.done`) regardless of the outcome. `.error`
  could only ever collect three synchronous failures (no transfer service, an empty body, an IO
  error while spooling), while the documentation described it as where a file that could not be
  ingested goes. An operator reconciling by directory therefore read a rejected file as
  ingested. The poll consumer now waits for the transfer to resolve and raises `TQL-LD-2849`
  when it did not complete, uniformly for local, SFTP and FTPS sources.

- **Remote poll sources no longer load the whole file into heap** (docs/connectors.md): the SFTP
  and FTPS components default to materializing a polled file in memory before the route sees it
  (`streamDownload` false, no `localWorkDirectory`), so `PollImportProcessor`'s "a large file
  never materializes in memory" was true for `source: local` only — and the transfer service
  then spooled a second copy. Remote sources now stream through a local work directory under the
  app's work home, making the spool a disk-to-disk copy.

- **Error pages carry the app's security headers** (docs/response-shaping.md): the
  `security.responseHeaders` block was merged by the successful HTML render, which the error path
  short-circuits — so a custom `templates/errors/<status>.html` page and an htmx error fragment,
  both HTML a browser renders like any other, arrived with no `Content-Security-Policy` and no
  `X-Frame-Options`. Both now carry the block. SSE and static assets still do not, and need a
  different mechanism: SSE has to set its headers before the first frame, so no completion hook
  can serve it.

- **Every recipe carries the same governance head** (docs/route-governance-parity.md): the
  sequence — telemetry, audit, rate limit, lane, security, tenancy, locale — was restated by
  hand in each `build*` method, and each copy had dropped something. `file-import`/`file-export`
  ran with no tenancy and ignored `policy.rateLimit` and `lane:` entirely; `queue-consume` routes
  and MCP tools never reached the audit trail, so with `tesseraql.audit.routes.enabled` an HTTP
  write was recorded and the identical write from a queue message or an AI agent was not;
  workflow delegation carried only security and tenancy, so its errors skipped locale
  negotiation; all three attachment routes skipped tenancy and only upload resolved a locale;
  and a `page` route opened an idempotency record it never closed, so a retry with the same key
  answered 409 for the whole TTL. One shared applier now supplies the head, and a test compiles
  each recipe and reads the processors back off the Camel model, so a recipe that skips a step
  fails the build rather than a review.

- **Sessions expire on the default store** (docs/deployment.md): `tesseraql.sessions.ttl` was
  read only when `tesseraql.sessions.store` was `jdbc`, and the default in-memory store resolved
  a session id with a bare map lookup — no expiry check, no pruning, no cap. So on the default
  configuration a session id stayed valid until the process restarted, the map grew one
  principal per login for the life of the process, and a stolen cookie outlived every control
  except an explicit logout. The TTL is now read once and applied by whichever store is
  selected, and the in-memory store prunes on write behind a 50,000-session ceiling.
  Constructing it without a TTL still yields a non-expiring store, for embedders that want one.

- **`source: local` poll jobs are confined to declared roots** (docs/connectors.md): the
  deny-by-default allow-list gate applied to remote sources only, and a local `path:` went into
  the endpoint URI verbatim — no anchoring, no normalization, no traversal check — while
  camel-file's `autoCreate` default is true. A path like `data/../../secret` therefore polled a
  directory outside the app *and moved its files into `.done`*, since the consumer relocates
  what it ingests. `tesseraql.connectors.poll.allowedPaths` now declares the roots, resolution
  re-checks the normalized prefix (the rule `FileScopes` already applied everywhere else), and a
  local source without a root is refused with `TQL-SEC-4086` at lint time.

- **Poll option values cannot smuggle endpoint options**: `include` and `username` were
  concatenated into the consumer URI unescaped while `password` was `RAW(...)`-wrapped, so an
  `&` in either bound whatever followed as extra Camel consumer options. Both are wrapped now.
  `move:`/`moveFailed:` are **rejected** rather than escaped when they contain `${`, `..`, a
  leading `/`, `&` or `?` — Camel evaluates them as Simple expressions, so escaping would not
  stop `${file:parent}/../../elsewhere` from writing the polled file outside the poll tree.

- **Poll lint parity**: an unparseable `delay` is reported at lint instead of throwing at
  startup, where it was logged and the job dropped — leaving the app healthy with nothing ever
  arriving; `port` is range-checked; and `host:`/`credential:` on a `local` source are flagged
  rather than silently discarded.

- **`POST /_tesseraql/studio/reload` requires the Studio edit role** like every other mutating
  Studio endpoint. It authenticated and stopped there, so in the **default** posture — where
  `tesseraql.studio.readOnly` is true and draft/apply/scaffold all 403 — any authenticated
  bearer principal could still force a rebuild of every route in the running context, as often
  as it liked.

- **The hot-reload compile-failure stub no longer echoes the compile error.** The stub replaces
  the broken route's own security chain, so it answers without credentials, and its body carried
  `cause.getMessage()` — absolute file paths, SQL text, table and column names. It now returns
  `TQL-CAMEL-3103` with a generic message and logs the cause. **Behavior change:** clients (or
  tests) reading the compile detail out of the response body will no longer find it.

- **Command steps and validation SQL are bounded** (docs/transactional-writes.md): a command
  opens its own JDBC transaction with no transaction manager behind it, and its statements ran
  with no query timeout and no row cap — so `tesseraql.sql.timeoutSeconds` and
  `tesseraql.resultMaterialization.maxRows` silently did not apply inside a command, and a
  `mode: query` step could materialize an unbounded result set *inside an open write
  transaction*. Both now resolve from the same config keys the route-level SQL path uses, with a
  step's own `timeoutSeconds:`/`materialize:` taking precedence and overflow raising the same
  `TQL-LD-0001`. Validation SQL gets the timeout (a rule that hangs pins the transaction); its
  rows stay uncapped on purpose, since truncating violations would hide why a write was refused.

- **`query-export` runs on the same execution contract as every other statement**
  (docs/file-transfers.md): its `tesseraql-sql:` URI was hand-built with only `datasource`,
  `mode` and `filename`, so a dialect variant (`select-events.postgres.sql`) was never picked
  up, the statement ran with no timeout whatever `tesseraql.sql.timeoutSeconds` said, a
  binding-level `sql.datasource:` was ignored, and — because the dialect also selects the
  streaming profile — PostgreSQL exports left autocommit on, so the driver ignored the fetch
  size and buffered the entire result set, which is precisely what streaming an export exists
  to avoid.

- **FTPS poll sources verify the server certificate** (docs/connectors.md): the client accepted
  any in-date certificate from any host — commons-net's default trust manager checks validity
  dates only, with no chain building and no hostname verification — and there was no
  configuration surface to change that, so a TLS handshake proved nothing about the peer. A new
  `tesseraql.connectors.poll.trustStore` (`file:`, `password:`) pins the CA the certificate must
  chain to and turns hostname checking on; an `ftps` source without one is refused at wiring
  time, and lint reports it first as `TQL-SEC-4085` (an error — unlike SSH host keys, a CA
  bundle has no trust-on-first-use posture worth preserving). The integration test covers the
  negative case: a server presenting an untrusted certificate ingests nothing.

- **Row scoping applies to writes** (docs/data-scoping.md): a `/*%scope … */` directive in a
  command's SQL, a command step, a validation rule, or a workflow `assign:` block threw
  `TQL-SQL-2106` at request time — the scope resolver was wired into the SQL component only, so
  scoping worked on reads and failed on the writes the documentation describes ("this is how an
  approval workflow state transition carries its row authority"). Worse, `TQL-SEC-4100` warns
  when a write route touches a scope-governed table *without* a scope predicate, so following
  the lint produced a route that 500s. The resolver now reaches every request-path executor;
  the failure was fail-closed throughout, so no unscoped write was ever committed. A scope
  directive in **batch-job** SQL is now a lint error (`TQL-SCOPE-3014`) instead of a runtime
  failure: a job runs on a schedule with no principal to scope against.

- **Workflow `assign:` SQL binds the ambient namespaces**: it rendered with only its declared
  `params:`, so `/* principal.loginId */` or `/* audit.now */` bound **null** rather than
  failing — a missing segment evaluates to null. It now seeds the same ambient principal and
  audit binds every other statement in the transaction gets, reusing the command's own audit map
  so one clock reading still covers the whole transaction.

- **FTPS poll sources encrypt the file, not just the login** (docs/connectors.md): the `ftps:`
  endpoint carried `disableSecureDataChannelDefaults=true`, which reads like hardening and does
  the opposite — it suppressed the `PBSZ`/`PROT P` negotiation, so TLS protected the control
  channel while every polled file's bytes crossed the network in cleartext. The endpoint now
  sets `execPbsz=0` and `execProt=P` explicitly, and also `binary=true` (ASCII mode
  line-ending-translated payloads in flight, corrupting Excel and archive imports) and
  `passiveMode=true` (active mode asks the server to open a connection back into the runtime,
  which no containerized or NAT'd deployment can accept). An in-process FTPS integration test
  now covers the branch, which had none. **Server-certificate validation is still absent and
  still unconfigurable** — treat an FTPS partner as authenticated by the network, not by TLS,
  until that ships.

- **Shared definitions reach queue consumers and MCP tools** (docs/field-domains.md,
  docs/validation-rule-sets.md): `use:` and `domain:` were resolved only for `web/` routes. A
  `use:` reference in a `consume/` or `mcp/` document failed at startup with "validation rule
  must declare exactly one of rule: or file:" — an error naming keys the author never wrote —
  and a `domain:` reference silently lost every constraint it carried, which on an MCP tool
  meant an agent-facing surface advertised without its declared limits. Resolution now happens
  above the tree split, and every check that answers "is this referenced?"
  (`TQL-FIELD-4611`/`4612`, `TQL-SEC-4136`) walks all three trees so the fix does not just move
  the problem. `TQL-SEC-4136` additionally covers queue consumers, which can never carry a
  principal at all.

- **`TQL-YAML-1003` no longer rejects working routes**: it fired for every recipe but
  `command-json`, while the compiler runs `validate:` on any route reaching the transactional
  command pipeline — so a `webhook` route, or a `query-json` route with a validate block, failed
  the lint gate at error severity for validation the runtime performs. The check now gates on
  that same set. Conversely, consumers and MCP tools had no validate lint at all (a typo'd
  validation SQL filename reached startup) and now get the same shape checks routes do.

- **Writes honor per-tenant datasource routing** (docs/multi-tenancy.md): under
  `database-per-tenant` or `schema-per-tenant`, a `command-json` write — and a `queue-consume`
  write, an MCP write tool, validation SQL, and workflow delegation — resolved the route's named
  connector directly and committed to the shared `main` pool, while every read on the same
  request went to the tenant's pool. A tenant with no configured pool was refused on the read
  path with `TQL-TENANT-4031` (403) but still had its write committed. Every executor now
  resolves the datasource through one shared rule, so the 403 covers writes too and a tenant's
  rows land in the tenant's database. Deployments in a per-tenant isolation mode should check
  the shared pool for rows written before this fix.

- **The pg-notify listener no longer leaks its LISTEN connection**: a drain failure surfaces as a
  `TqlException` (unchecked), and the reconnect path released the connection only on the checked
  branch — so a persistent failure orphaned one connection per five-second cycle from the app's
  *main* pool until every component sharing it was starved. Both listeners now release from a
  `finally`, and the cross-node topic bridge gained the unchecked-exception catch its sibling
  already had (without it, one unchecked exception ended live-view signalling for the life of the
  process).

- **Live streams no longer strand the subscription they evict for**: at the global cap, a subject
  holding a single stream could have its own list evicted and dropped from the registry while a
  new subscription was being added to it. That subscription then never unregistered — it kept
  receiving signals after close and never returned its slot, so the cap ratcheted down and every
  later stream evicted a live one despite spare capacity. Eviction now runs before the list
  reference is taken, and the cap loop stops when there is nothing left to evict.

- **The route watcher survives an unreadable filesystem event**: `Files.walk` reports a directory
  that vanishes mid-traversal as `UncheckedIOException`, which escaped the watcher's
  `IOException` handling and killed its thread for the life of the process — `serve --watch`
  stopped reloading with no way to restart it short of restarting the server.

### Removed

- **Three YAML keys that were parsed, documented, and never consumed** (docs/yaml-surface-consumers.md):
  `policy.concurrency.rejectStatus` (rejections are always 429 — `ConcurrencyLimiter` has no
  status field), `security.provider` (route-level provider selection does not exist;
  `applySecurity` branches on `auth`/`csrf`/`policy` only), and `response.stream.contentType`
  (the codec decides a download's content type). The last two were **read only by the docs
  portal and Studio**, which displayed them faithfully and so confirmed the illusion — those
  readers are gone too. An app still setting one of these now fails to parse, which is the
  point: it never did anything.

### Fixed

- **The error-code reference no longer lists an unsearchable code**: `ErrorIndex` rendered the
  number unpadded while `TqlErrorCode.toString()` pads to four digits, so the maxRows overflow
  appeared as `TQL-LD-1` for a code the runtime emits as `TQL-LD-0001`.

### Changed

- **The gallery apps rely on the default response headers**: all five example apps declare
  `security.responseHeaders` and their HTML routes dropped the copy-pasted four-header block
  (18 files). The `tesseraql new` skeleton declares the block once in config, and the CRUD
  scaffolder omits per-route headers when the target app declares response-header defaults.
  The bundled Studio/IAM/ops apps migrate separately with their module resources.

- **The gallery apps rely on the security defaults**: all five example apps declare
  `security.defaults.routes` and their route files stopped restating `auth:`/`csrf:` the
  defaults reproduce (34 files; `policy:` stays route-local). Effective posture is unchanged and
  now pinned by a test asserting every gallery route resolves an explicit auth mode. The CRUD
  scaffolder emits the slim form when the target app's defaults cover its pages (explicit
  otherwise), and the `tesseraql new` skeleton's sample API route defers to the generated
  defaults. Workflow and attachment documents keep their explicit `security:` blocks.

- **Retired the kind-keyed `security.defaults.api`/`htmx` config shape** — it was emitted by the
  scaffolder and documented, but never read by any compiler code, and it is not decidable
  (`command-json` serves both browser and API writes; the URL path is the framework's
  discriminator). Apps still carrying it get lint `TQL-SEC-4130`; the scaffolder now emits the
  path-matched `security.defaults.routes` shape instead. No effective behavior changes: the old
  shape did nothing.

## 0.7.1 - 2026-07-21

### Fixed

- **Maven Central publishing**: the imported `camel-bom` 4.18.0 manages the retired
  `org.apache.camel:camel-test` artifact at a SNAPSHOT version, and Central refuses any
  published POM whose effective dependency management carries a SNAPSHOT — the v0.7.0
  `central-publish` job failed on exactly this (every other 0.7.0 channel — GitHub
  Packages, the GHCR image, the GitHub release, Homebrew/Scoop — shipped normally). The
  parent POM now pins the entry to the release version; nothing depends on the artifact.

## 0.7.0 - 2026-07-21

### Added

- **DuckDB analytics datasources** (docs/duckdb.md): CSV and Parquet files become queryable
  with plain SQL through a new `duckdb` datasource kind — an in-process engine that is a
  query engine, never a system of record. Every pooled connection starts behind a proven
  fence (extension autoinstall/autoload off, external access disabled, allowed directories
  = the declared roots, configuration locked), and dynamic file paths exist only as
  placeholder channels: tenant-partitionable **`${scope.*}`** file scopes and owner-gated
  **`${dataset.*}`** reads over scan-passed managed attachments, bridged by a
  content-addressed local spool. Engine extensions are provisioned **offline-first** with
  `tesseraql duckdb install-extensions` / `info` (DuckDB's own repository layout and
  signature verification, corporate `--repository` mirrors, air-gap zip `--bundle` /
  `--from-bundle`); a managed **`attach:`** surfaces declared PostgreSQL datasources inside
  the engine (credentials injected, `READ_ONLY` default, the `--embedded-db` override
  honored), and batch jobs gain **`datasource:`** for one-statement pull ETL. The BOM now
  version-manages `org.duckdb:duckdb_jdbc` (module channel only — the driver never enters
  the CLI fat jar), and the inventory gallery app dogfoods the whole surface.
- **Lake tables — DuckLake under the fence** (docs/duckdb.md): `duckdb.lake:` attaches a
  [DuckLake](https://ducklake.select) lakehouse whose **metadata lives on `main`** (confined
  to a self-managed schema) with Parquet data under a fence-admitted directory — ACID
  snapshots, `AT (VERSION => n)` time travel, and commits from separate engine instances
  serialized through the catalog, so lake-table writes are multi-node safe. Retention is an
  app-declared job over `ducklake_expire_snapshots` / `ducklake_cleanup_old_files` (the
  `CALL`s return rows: run them as query-mode steps).
- **Remote lakes and ad-hoc remote reads, S3-compatible from day one** (docs/duckdb.md):
  `lake.data:` takes an `s3://` prefix — AWS or any S3-compatible store via
  `endpoint`/`region`/`urlStyle`/`useSsl`, credentials as secret references or `instance`
  (AWS credential chain) — lifting the shared-storage constraint: every node reads and
  writes the same lake. **`${remote.*}`**, the third and last file-placeholder channel,
  resolves declared object-storage prefixes for ad-hoc Parquet/CSV reads (globs and Hive
  partitioning welcome). Each lake and remote gets a **prefix-scoped engine secret** — an
  out-of-scope bucket fails authentication — and on every duckdb datasource app SQL must be
  plain queries: `ATTACH`/`INSTALL`/`LOAD`/`CREATE SECRET`/`SET`/`PRAGMA` are lint errors
  (`TQL-SQL-2111`). The admission report gains informational **NOTEs** naming every
  readwrite attach, remote lake endpoint, and remote URL.
- **Module-channel JDBC drivers now reach `DriverManager`**: drivers arriving as
  `tesseraql.modules` jars (DuckDB, MySQL, Oracle, SQL Server) register through a
  base-classpath shim at boot and in the authoring commands — previously only the bundled
  PostgreSQL driver was visible, and module-declared drivers required baking into the image.
- **Distribution**: release tags now publish the reactor to **Maven Central** (signed, with
  sources and javadoc) alongside GitHub Packages; Homebrew and Scoop channels are documented
  and auto-bumped on release; the TesseraQL brand mark ships across the CLI, docs site, and
  VS Code extension.

### Changed

- **`AdmissionProfile.Report` carries `notes()`** (pre-1.0 API change for Java consumers):
  the record gained an informational component beside `failures()`; the CLI `admission`
  command and the Maven mojo print the NOTEs.

### Fixed

- **2-way SQL parser: quoted strings and `--` comments are opaque**: a string literal
  containing `/*` (a glob like `'s3://x/**'`, a `LIKE '%/*%'` pattern) no longer opens a
  directive comment, and an apostrophe inside a `--` line comment (`-- don't`) no longer
  opens a string. Both shapes previously failed to parse, so no behavior changed for
  templates that compiled before.

## 0.6.0 - 2026-07-12

### Changed

- **The copilot endpoint obeys the outbound egress allow-list** (breaking): the host of
  `tesseraql.copilot.endpoint` must now be in `tesseraql.http.outbound.allowedHosts` —
  the same deny-by-default egress rule an `http-call` step obeys. A configured copilot
  whose endpoint host is not allow-listed (including when no allow-list is declared at
  all) fails the boot with `TQL-SEC-4085`, an error naming the host and showing the
  exact YAML to add. Previously configuring the endpoint was itself the authorization
  and the allow-list did not apply. **Migration**: add the copilot endpoint's host to
  `tesseraql.http.outbound.allowedHosts`.

### Fixed

- **Stale inbox badge after mark-read**: a race between the unread-count cache's
  read-through and a mark-read invalidation (the SSE badge pusher reacting to a delivery
  could re-cache the pre-mark-read count) made the shell bell show the old badge for up
  to a full cache TTL. Invalidations now bump an epoch and a racing read-through discards
  its stale result.

### Added

- **Mail real-send test cases**: `notify` cases with `send: true` now deliver **mail**
  channels too — the production sender renders the channel template and inline subject,
  resolves `to`/`from`, and delivers over real SMTP to the runner's in-process capture;
  the row carries the rfc822 truth (`to`, `from`, `subject`, `wireBody`) and the channel's
  real host is never touched. Under the hood the mail channel's transport moved from the
  Camel mail component to plain jakarta.mail — the same one-sender symmetry as the webhook
  channel, byte-identical channel semantics (settings, template trust model, smtps,
  credentials via the SecretResolver SPI), one dependency fewer, and the sender reusable
  from the test runner. The shared Thymeleaf template engine (`Templates`) and i18n
  settings moved from the compiler to `tesseraql-yaml` accordingly.

- **Declarative HTTP caching for query routes** (`cache:`): `Cache-Control` from
  `maxAge`/`visibility`/`staleWhileRevalidate` (`private` default; `public` lints onto
  `auth: public` routes only — an authenticated response is per-principal) and a strong
  content `ETag` (on by default) answering a matching `If-None-Match` with `304` and no
  body. Stateless by design — no server-side cache to invalidate, nothing to coordinate
  across nodes; a 304 saves transfer, not compute. Query recipes only (`TQL-YAML-1025`).
  The platform HTTP header filter now lets `Cache-Control` through for app routes (it
  previously dropped it wholesale; request hop-by-hop headers still never echo back).

- **Asynchronous attachment scanning** (`tesseraql.attachments.scan.mode: async`): uploads
  return immediately as `pending` and the existing non-clean download gate holds them back
  (`409`, "awaiting its malware scan") — fail-closed is preserved by construction; only the
  verdict moves off the request path. A cluster-safe sweep claims pending objects by
  compare-and-set (one scanner per attachment across nodes; a dead node's claims release
  when the lease ages out), runs the installed `AttachmentScanner`, and records the verdict
  with the same `onInfected: quarantine | delete` policy; failures retry to
  `scan.maxAttempts` and then record `error` — still never served, visible to the operator.
  Default stays `sync` (existing apps byte-identical); the `AttachmentScanner` SPI is
  unchanged.

- **TOTP QR code and recovery codes**: enrollment now renders the `otpauth://` URI as a
  server-side inline-SVG QR (zxing matrix only — no imaging stack, no client scripting)
  next to the manual-entry secret, and mints eight single-use recovery codes shown while
  the enrollment is pending (the same owner-only exposure as the pending secret).
  Confirmation activates them — plain copies dropped, SHA-256 hashes at rest in
  `tql_totp_recovery` — and at login a recovery code goes in the same field as the 6-digit
  code, signs in exactly once, and fails like a wrong password when spent or wrong.
  Re-enrolling replaces the set.

- **Subdomain tenant resolution** (`tenancy.resolver.type: host`): the tenant resolves from
  the request's `Host` header against a `{tenant}.example.com` pattern — exact suffix match,
  single slug label only, deny-by-default (`TQL-TENANT-4001`) — so a wildcard-DNS deployment
  no longer needs a gateway mapping host to header.

- **SAML IdP metadata from a URL**: `tesseraql.saml.idp.metadata` now takes an `https://`
  URL as well as a file path. The metadata pins the IdP signing key, so the fetch obeys the
  egress discipline (host in `tesseraql.http.outbound.allowedHosts`, `TQL-SEC-4086`; plain
  `http://` refused off loopback, `TQL-SEC-4087`) and caches to
  `work/saml/idp-metadata.xml` — an IdP metadata-endpoint outage at a later boot falls back
  to the cached copy with a warning instead of failing the start.

- **Real-send test cases** (`send: true` on `notify` and `http-call` cases): planning proves
  the declarations resolve; real-send proves the wire. The test runner starts a local capture
  server per case, delivers over a real socket, and the rows carry what actually arrived — no
  external mock server to install. A `notify` send delivers webhook channels through the
  production sender (same JSON body, timestamp header, and HMAC signature as the outbox
  dispatcher; `delivered`/`signature`/`wireBody` columns), mail/inbox channels staying
  evaluate-only. An `http-call` send performs the request — declared headers, the credential
  header exactly as runtime builds it, the body — preserving the original path and query
  (`sent`/`requestPath`/`authorization`/`requestBody`/`responseStatus` columns), while the
  plan columns keep the true allow-list verdict for the declared host. Credential header
  construction is now one shared implementation (`Credential.authorizationHeaders`) across
  the job client, http: sources, and the runner.

- **Shared export files** (`tesseraql.temp.store: db | blob`): a spooled export no longer
  has to live on the node that produced it. `db` puts spools in a `tql_temp_spool` table on
  the main database (created on first use; writes/reads stage through local scratch so
  memory stays bounded and no connection is pinned during a slow download; per-spool
  `tesseraql.temp.maxBytes` cap, default 64 MB) — any node serves any download with no
  session affinity and no new infrastructure. `blob` spools through the configured object
  store (S3 via the opt-in `tesseraql-s3` module; `tesseraql.temp.bucket`) for heavy export
  volumes, warning at boot when the local file provider would keep it node-local anyway.
  The default stays `file` (existing deployments byte-identical); an unknown store fails
  the boot with `TQL-YAML-1024`. Every consumer of the TempStore SPI — `query-export`,
  `query-spool`, batch intermediate results, transfer downloads — picks the store up with
  no route changes.

- **Cluster-wide rate limits** (`rateLimit.scope: cluster`): a route's declared
  requests-per-second becomes one budget across every node sharing the main database,
  instead of N × node-count. Enforcement stays a local token bucket — the request path
  never touches the database — with tokens leased from a small `tql_rate_lease` ledger
  (one atomic claim per second per node per route, plain portable SQL, table created on
  first use). Claims are first-come-first-served across nodes; `burst` remains node-local
  smoothing; an unreachable ledger degrades to the per-node budget for that window with
  backoff logging — the limiter never becomes the outage. Default stays `scope: node`
  (existing apps unchanged); concurrency limits and lanes stay per-node deliberately, since
  they protect a node's own resources. Lint `TQL-YAML-1023` rejects unknown scopes.
  Documented in deployment.md ("Rate limits can be cluster-wide").

- **HTTP sources on query routes** (`http:`): compose an external JSON API with SQL results
  in one screen or one JSON response, declaratively. Each named source is a body-less GET
  executed after the route's SQL and landed in the context like a named query —
  `<name>.rows` (array → rows, object → one row, `select:` picks the array inside the JSON),
  `<name>.body`, `<name>.status` — so JSON shaping, HTML views (child/panel `source:`), and
  the docs portal all compose it. Sources run through the same outbound gateway as `http-call`
  job steps: deny-by-default `allowedHosts`, named secret-managed credentials, timeouts, and
  the per-host circuit breaker; `onError: empty` degrades a dead upstream to zero rows
  instead of failing the page. Query recipes only — a transactional write never blocks on a
  third party (`TQL-YAML-1022`; egress lints `TQL-SEC-4070/4071/4072` as for jobs). The
  `http-call` test case gains a `route:` target planning a route's sources without a network
  request. Documented in connectors.md ("HTTP sources on query routes").

- **Live views** (`emit:` / `refreshOn:`): a list, detail, or dashboard view can refresh
  itself the moment a command commits. A `command-json` route declares `emit: <topic>`;
  the view declares `refreshOn: <topic>`; after a successful commit every open page
  watching the topic re-fetches its refresh region through its own fully-authorized
  route (forms are the deliberate exception — a live replacement would discard
  in-progress input). A list's refetch carries the typed search term and current sort,
  read from the live DOM, and never clobbers in-progress typing (the search box sits
  outside the swapped region). The
  session-authenticated `GET /_tesseraql/events` SSE stream (shared with the inbox
  bell) carries topic names only —
  never data — so authentication, policies, data scoping, and tenancy all apply to the
  refresh exactly as to any request, and a rolled-back command emits nothing. Topics are
  tenant-scoped; subscriptions are bounded with coalesced signals. On PostgreSQL a commit
  rides `pg_notify` across every node sharing the main database, so viewers behind a load
  balancer refresh regardless of which node served the write; on other databases signals
  stay per-node best-effort (viewers converge on reload), matching the deployment doc's
  coordination stance. Lint checks the surface: `emit:` off command-json (`TQL-YAML-1038`),
  malformed topic names (`TQL-YAML-1039`), `refreshOn:` on form views (`TQL-VIEW-3311`),
  and a topic no route emits (`TQL-VIEW-3312`, warning). Documented in docs/realtime.md.

- **Custom expression functions** (`ExpressionFunction` SPI): a one-class Java hook for the
  predicate the built-in whitelist cannot express — a checksum, a code-format rule, a
  business-calendar check — without a full runtime extension. Implement
  `io.tesseraql.core.expr.ExpressionFunction` (`name`/`arity`/`apply`, side-effect-free and
  total by contract), register it via `META-INF/services`, and ship the jar through
  `tesseraql.modules` (or `--modules`, or as a Maven-plugin dependency for the goals). Once
  installed the function is callable wherever the expression language runs: `validate:`
  rules, 2-way SQL `/*%if*/` directives, `requiredWhen`, notify `when:` guards, workflow
  guards. `serve`, `lint`, `test`, `coverage`, and `mcp` all install the same function set
  from the app's modules classpath before parsing (`lint`/`test`/`coverage` gain a
  `--modules` option); installation fails fast with `TQL-SQL-2110` on a name collision or an
  illegal name, unknown names remain parse/lint errors (now with a message naming the
  modules channel), and `tesseraql admission` still rejects apps calling custom functions —
  custom Java stays out of the declarative-only marketplace bar by design.

- **Write routes are directly testable**: a `sql` test case may now target an
  `UPDATE`/`INSERT`/`DELETE` file. Every `sql` case runs inside a manual-commit transaction
  the runner always rolls back — pass or fail — so nothing a test writes ever persists.
  `expect.updateCount` asserts the affected-row count, and the new `verify:` list runs
  read-back queries on the same connection, inside the same transaction, after the target:
  they observe the uncommitted write and roll back with it. A write ending in `RETURNING`
  produces result rows and is asserted with `rowCount`/`rows` like a query, and mixing the
  assertion kinds fails the case with a message naming the right one. Write cases record
  SQL coverage, and `verify:` read-backs exercise their files for route and item coverage
  exactly as case targets do, so command routes now count toward the `route`/`security`
  coverage kinds. Applies everywhere suites run: `tesseraql test`/`coverage`, the Maven
  goals, Studio's per-route test runner (whose sandbox keeps its own rollback), and the
  editor Test Explorer.

- **`serve --watch`**: an opt-in file watcher for editor-first development. Saving a
  route source under `web/` (its yml, 2-way SQL, or templates) hot-reloads that route
  through the exact content-diff reloader Studio's Apply uses — no click in Studio, no
  restart. A burst of editor save events debounces into one reload (300ms of quiet),
  backup/swap/temp files and dotfiles are ignored, and each reload prints one concise
  line (what changed, after which file). A broken save never kills the watcher or the
  server: the route serves its compile error as a 500 stub until the file is fixed, and
  the watcher reports the error and keeps watching. Scope matches the reload's: `web/`
  routes — jobs, consumers, and `config/` changes still need a restart.

- **Configurable SAML clock skew**: `tesseraql.saml.clockSkew` (a duration string such
  as `30s` or `5m`) sets the allowed skew for the assertion's time-bound conditions
  (`NotBefore`, `NotOnOrAfter`, subject confirmation expiry). Unset keeps the previous
  fixed five minutes, so existing deployments are unchanged.
- **First-login hand-off**: the unseeded identity store no longer strands every entry
  path at Studio's login form. `serve --embedded-db` writes the embedded database's
  JDBC URL to `work/embedded-db.jdbc` (overwritten each start, removed on graceful
  shutdown), and `identity-schema --app .` falls back to that running instance whenever
  the app's configured main datasource is missing, unresolvable, or does not answer —
  announced as `Using the running embedded database (work/embedded-db.jdbc)`. An
  explicit `--jdbc-url` still always wins, and a stale marker is ignored (its URL must
  answer a connection probe). `serve` also prints a one-time hint right after the
  serving line when the managed store holds no users (and the password login form is a
  login path): the exact `identity-schema` command to create the first administrator,
  with no URL to hand-copy.

- **SFTP host-key verification for `poll:` triggers** (`docs/connectors.md`, "Remote
  sources"): `tesseraql.connectors.poll.knownHostsFile` pins the SSH host keys SFTP
  sources may present — an OpenSSH known-hosts file, resolved against the app home like
  other configured paths — and the consumer then runs with strict host-key checking.
  Unset keeps the historical unchecked behavior, and lint nudges with the new
  `TQL-SEC-4084` warning, so existing apps keep working while B2B exchanges adopt the
  pin.

## 0.5.0 - 2026-07-11

### Fixed

- **Queue-consume dedup on Oracle and SQL Server**: marking an event consumed with an
  idempotency key crashed with `SQLFeatureNotSupportedException: releaseSavepoint` on
  both drivers (`TQL-BATCH-5313`). The savepoint that fences the dedup insert is no
  longer explicitly released — the commit that follows releases it implicitly on every
  dialect; the unique-violation rollback path is unchanged. Caught by the gated dialect
  portability suites.

### Changed

- **The hot reload is a content diff**: an apply now bounces only the routes whose
  sources actually changed — each route's fingerprint is its source directory (its yml,
  2-way SQL, and templates live together), and a `config/` change still rebuilds
  everything. Previously every apply stopped and re-added every kept route (30+ bounces
  for a one-file edit), and a stop/re-add races in-flight requests on that endpoint —
  the leading suspect in the recurring CI hang where a request vanished right after a
  reload storm. The manual `POST /_tesseraql/studio/reload` deliberately stays the
  force hammer: it rebuilds every kept route even when nothing changed, which is also
  the recovery move if a route ever wedges.

### Added

- **IAM Admin: bulk disable** (hc 0.1.9 adoption; pattern in `docs/hypermedia-ui.md`,
  "Bulk actions"): the users list becomes an `hc-datagrid` with row selection — the kit's
  `datagrid-bulk-actions` recipe — and a confirm-gated "Disable selected" toolbar that
  posts the selection to `POST /_tesseraql/admin/users/bulk`. The endpoint is a Java
  route (repeated `ids` form fields sit below the Simple-YAML input surface), runs the
  same `identity.disable-user` contract per id behind the same browser + CSRF +
  `iam.admin.write` gates, and answers post/redirect/get; the select-all checkbox
  carries no name, so it never reaches the server.
- **The inbox bell is live** (hc 0.1.9 adoption; design in `docs/inbox.md`, "Live
  badge"): the shell subscribes the badge to a new per-session event stream,
  `GET /_tesseraql/events` (the kit's `sse-updates` recipe over the `SseRoutes`
  transport the streaming copilot introduced). Delivery, mark-read, and mark-all-read
  signal the subject's open streams through one notifying store wrapper, and the pushed
  payload is the same `InboxBadge` fragment the page renders — byte-identical on reload.
  Streams are capped per subject and globally, end themselves after a fixed lifetime,
  and the browser's EventSource reconnects; without JavaScript the badge simply updates
  on the next page load, the Phase 49 behavior.
- **The copilot streams its replies** (hc 0.1.9 adoption; design in `docs/copilot.md`,
  "Streaming replies"): the Studio chat adopts the kit's `chat-messages` and
  `streaming-response` recipes on htmx's bundled `sse` extension — send returns the user
  item plus a streaming placeholder, and an SSE stream delivers the model's deltas as
  `chunk` events with a `done` payload rendered by the same `CopilotFragments` markup the
  page itself uses. The turn id is a single-use, actor-bound capability; a no-JS post
  still runs the blocking loop and redirects (the old behavior, now the fallback). Behind
  it sits the framework's first streaming transport, `SseRoutes` — raw routes on the
  platform's Vert.x router that write frames to the wire as they are produced (a Camel
  exchange answers with a complete body, and the platform-http InputStream pump only
  delivers full buffers — unusable for SSE). The `send` endpoint moved from a YAML route
  to `CopilotRouteBuilder` (streaming and `HX-Request` negotiation are transport concerns
  below the YAML surface); the upstream chat-completions call now sets `stream: true` and
  reassembles fragmented tool-call deltas.
- **One-click light/dark toggle in the shell header** (hc 0.1.9 adoption; design in
  `docs/account.md`): the kit's `installThemeToggle` flips the page instantly, and the
  framework bootstrap mirrors `hc:themechange` to the account appearance route, so the
  stored `ui.theme` preference — not localStorage — remains the source of truth (the
  cookie re-sync then carries the choice onto pre-login pages). Rendered only when the
  bundled account app is mounted; app pages can opt in with the same
  `data-hc-theme-toggle` button, documented in `docs/hypermedia-ui.md`.
- **Studio route form options derive from the framework** (roadmap Phase 57
  slice 3, completing the phase; design in `docs/vscode-extension.md`): the form's
  recipe, auth, and input-type choices come from the same surfaces the shipped JSON
  Schema is drift-tested against (`AppLinter.knownRouteRecipes()` and the new
  `knownAuthModes()`/`knownInputTypes()`), replacing hand-coded lists that had
  already drifted — the form offered four auth modes where the framework accepts
  five (`public` was missing). `security.auth` is now a real `enum` in the shipped
  JSON Schema (editors gain completion), and `SchemaSyncTest` holds the schema, the
  linter, and the form to one source.
- **Studio: Open in editor** (roadmap Phase 57 slice 2; design in
  `docs/vscode-extension.md`): the source view gains the reverse half of the
  Studio–editor round trip — a `vscode://file/…` deep link next to *Edit as form*,
  landing on the same file in VS Code. Best-effort by design: the link assumes the
  browser and the files share a machine (the normal dev loop) and stays inert
  otherwise; the traversal guard covers the deep link too.
- **VS Code extension 0.3.0** (roadmap Phase 56 complete; design in
  `docs/vscode-extension.md`): the version carrying Phase 56's editor
  intelligence — single-case Test Explorer runs, Studio deep links, MCP
  registration, and the `tesseraql symbols` language layer.
- **The language layer — `tesseraql symbols` and editor intelligence** (roadmap
  Phase 56 slice 5, completing the phase; design in `docs/vscode-extension.md`):
  `tesseraql symbols --app <dir>` prints what the framework declares — security
  policies, default-locale message keys, and routes, each with source and line — as
  one sorted, deterministic JSON object. Over it the VS Code extension adds
  completion for `policy:` and `message:` values and go-to-definition from a
  `policy:` value to its declaration in `config/tesseraql.yml` and from a
  `message:` (or key-naming `title:`/`label:`) value to its catalog line, refreshed
  on save. Unknown references stay lint findings — the providers navigate, they do
  not judge.
- **VS Code extension: MCP registration** (roadmap Phase 56 slice 4; design in
  `docs/vscode-extension.md`): *TesseraQL: Register MCP Server* writes the Phase 24
  dev-tools server (`tesseraql mcp --app .`, stdio) into the chosen client
  configuration in the app home — `.vscode/mcp.json` (VS Code MCP clients) and/or
  the project `.mcp.json` (Claude Code) — merging with existing servers, no-op when
  already registered, and never overwriting a foreign `tesseraql` entry without a
  modal confirmation.
- **VS Code extension: Studio deep links** (roadmap Phase 56 slice 3; design in
  `docs/vscode-extension.md`): *TesseraQL: Open in Studio* — from the editor context
  menu, the TesseraQL explorer, or the command palette — opens the file's live
  counterpart in the running Studio's source view
  (`/_tesseraql/studio/ui/source?path=…` on `tesseraql.serverUrl`).
- **Single-case test runs — `tesseraql test --case`** (roadmap Phase 56 slice 2;
  design in `docs/vscode-extension.md`): a repeatable exact-name filter runs only
  the named case(s) — suites with no match are skipped, item coverage derives from
  what actually ran, and the `--format json` document reports only the filtered
  results. The VS Code Test Explorer passes the filter when a run request names
  specific cases, so one failing case re-runs alone.
- **VS Code extension: serve status** (roadmap Phase 55 slice 5, completing the
  phase; design in `docs/vscode-extension.md`): a status-bar item polls the served
  app's readiness probe (`/_tesseraql/health/ready`, Phase 45) on the new
  `tesseraql.serverUrl` setting while an app home is open — up, DOWN (a 503
  readiness answer), or offline — and one click opens the app. Extension version
  0.2.0.
- **VS Code extension: Test Explorer and SQL coverage** (roadmap Phase 55 slice 4;
  design in `docs/vscode-extension.md`): suites under `tests/**/*.yml` appear in the
  native Test Explorer (cases discovered by name and line — presentation, not
  semantics); a run executes `tesseraql test --format json` against the app's
  datasource and maps results back by case name, and a *Run with Coverage* run feeds
  the same document's per-file SQL `coveredLines`/`coverableLines` into the editor's
  test coverage API — covered and uncovered 2-way-SQL lines paint where the SQL is
  written.
- **VS Code extension: reference navigation** (roadmap Phase 55 slice 2; design in
  `docs/vscode-extension.md`): `file:`, `view:`, and `template:` values in app YAML
  are document links, resolved against the document's directory exactly as the
  runtime resolves them (a `frags.html::fragment` suffix links to the file; a
  `view: list` kind without a file extension is not a reference). A link appears
  only when the target exists — a broken reference stays a lint finding.
- **`tesseraql test --format json`** (roadmap Phase 55 slice 3; design in
  `docs/vscode-extension.md`): the test command can print the editor test-run
  contract — `{passed, failed, results: [{name, passed, message}], sql: [{file,
  lineRatio, branchRatio, coveredLines, coverableLines}]}` — as the one JSON object
  on stdout: the complete per-case results (the `report.json` overlay only carries
  cases joined to a route) plus per-file SQL line/branch coverage with the 1-based
  line lists, files and lines sorted for determinism. `--format text` names today's
  output and stays the default; exit semantics (1 on failure, 2 on the opt-in
  regression gate) are identical in both formats.
- **Scaffolded apps recommend the TesseraQL VS Code extension** (roadmap Phase 54
  slice 4, completing the phase; design in `docs/vscode-extension.md`):
  `tesseraql new` writes `.vscode/extensions.json` recommending
  `ingcreators.tesseraql-vscode` alongside `redhat.vscode-yaml`, so a fresh app opens
  with schema completion and the real lint loop one install away. Marketplace
  publishing stays an operator step; the CI-built `.vsix` installs from file.
- **VS Code extension (MVP)** (roadmap Phase 54 slice 3; design in
  `docs/vscode-extension.md`): `vscode-extension/` ships the editor shell over the
  existing engines — saving a file in an app home runs
  `tesseraql lint --format json` (the CLI named by the new `tesseraql.cliPath`
  setting) and publishes every finding to the Problems panel at its source, line,
  and column, with the finding code linking to the published error-code reference;
  *TesseraQL: Serve / Test / Migrate / Admission / Package* run in the integrated
  terminal and *Lint* headless; a *TesseraQL* explorer view walks routes by kind,
  views, migrations, and test suites; `TQL-*` hovers link into the reference; and
  snippets cover the blessed route shapes. TypeScript with zero runtime
  dependencies; the editor-free core is unit-tested with `node:test`; a new CI job
  typechecks, tests, and packages the `.vsix` (marketplace publishing stays an
  operator step per the design).
- **`tesseraql lint --format json`** (roadmap Phase 54 slice 2; design in
  `docs/vscode-extension.md`): the lint command can print the cross-surface findings
  document the MCP dev-tools' `lint` tool has emitted since Phase 24 —
  `{errors, warnings, findings: [{code, severity, source, message, line, column}]}` —
  as the one JSON object on stdout, so editors parse the same shape agents do.
  `--format text` names today's output and stays the default; exit semantics
  (including `--fail-on-warning`) are identical in both formats.
- **Cross-database projections — `datasource:` on transactional routes** (roadmap
  Phase 53 slice 3, completing the phase; design in `docs/multi-datasource.md`): a
  `command-json`, `webhook`, or `queue-consume` route moves its whole
  single-connection transaction to a named connector — the blessed shape being the
  **projection**: a command commits on `main` and publishes, a `queue-consume` route
  with `datasource:` idempotently upserts into the second database, while the
  channel, its claim, and the consumed-key dedup records stay on `main` (delivery
  semantics unchanged, no JTA/XA anywhere). A non-main transaction is plain SQL:
  `notify:`/`publish:`/`outbox:` and sequence allocation are refused at build time
  (`TQL-YAML-1036`) and again at route compile time (`TQL-CAMEL-3112`) — their
  tables live on `main`; fan-out projects through `main` instead. Proven end to end
  by `MultiDatasourceProjectionIntegrationTest` on two real PostgreSQL databases:
  commit projects, rollback never does, and a republished business key never
  doubles or reorders the projection (milestone M18).
- **Multi-datasource reads — `datasource:` on read routes** (roadmap Phase 53 slice 2;
  design in `docs/multi-datasource.md`): a read route (`query-json`, `query-html`,
  `page`, `query-export`, and read-only MCP tools/resources/UI) can declare
  `datasource: <name>` to run its SQL on a named connector under
  `tesseraql.datasources`, and a named query on a page can override per binding — so
  one response composes result sets from several databases. The baked SQL dialect
  (pagination clauses, streaming profiles, label normalization) now resolves per
  connector, and an explicit non-main `datasource:` is authoritative over per-tenant
  datasource routing (tenant routing replaces only `main`). Lint guards the surface:
  an undeclared connector is `TQL-YAML-1035`, a route-level connector on a
  transactional recipe is `TQL-YAML-1036` (until the projection slice), and a
  per-step connector inside a transactional pipeline is `TQL-YAML-1037`.
- **Embedded PostgreSQL version pinning** (`serve --embedded-db`): a persistent data
  directory now records the exact zonky binaries version that initialized it (in a
  `tesseraql-embedded.properties` marker) and re-resolves that version on every later
  start, so bumping the CLI's default binary version can never re-open an existing
  directory with a format-incompatible major. A new `--embedded-db-version <version>`
  flag pins the version explicitly; ephemeral runs continue to use the default.
- **Embedded PostgreSQL major-version guard**: when the version resolved for an
  `--embedded-db` run cannot open the target directory (its `PG_VERSION` records a
  different major), the CLI now fails fast with an actionable message — how to pin the
  matching major, or start fresh — instead of surfacing a cryptic `postgres` startup
  crash.
- **`tesseraql embedded-db info <data-dir>`**: reports an embedded data directory's
  on-disk PostgreSQL major, its pinned binary version, and the CLI default — and, when
  the directory sits on an older major, prints the safe dump/restore upgrade procedure.
  The embedded binaries are server-only, so a cross-major upgrade is driven with the
  operator's own `pg_dumpall`/`psql`; the command produces the exact steps.

- **Documentation site, slice 2 — the generated reference** (completing the
  documentation-site leg of roadmap Phase 35; design in `docs/docs-site.md`): a
  build-only `tesseraql-docs-reference` module generates two committed markdown pages
  under `docs/` — the **YAML surface reference** walked from
  `tesseraql-v1.schema.json` (every property with type, constraints, and description,
  nested sections per object path) and the **error-code index** scanned from the
  modules' sources (both the literal `TQL-*` form and the `TqlDomain` constructor
  form: 316 codes across 29 domains, each with raising-file provenance and links to
  the cookbook pages that mention it). A drift test fails the build when the
  committed pages no longer match their sources (refresh:
  `mvn -q -pl tesseraql-docs-reference exec:java`); the site gains a **Reference**
  sidebar section, and the links validator checks every generated link and anchor.
- **Documentation site, slice 1 — the Starlight app** (the documentation-site leg of
  roadmap Phase 35; design v2 in `docs/docs-site.md`): `docs-site/` is an Astro
  Starlight project mirroring the Hypermedia Components docs stack — Pagefind search,
  Expressive Code highlighting, `starlight-links-validator` failing the build on
  broken internal links, and `starlight-llms-txt` emitting `/llms.txt` for AI agents.
  `docs/` stays the canonical GitHub-browsable tree: a sync step derives frontmatter
  from each H1, rewrites same-tree `*.md` links to site URLs and repo-relative links
  to GitHub, and **fails the build when a document is neither mapped into the nav nor
  explicitly excluded**. Deploys to Cloudflare Workers Static Assets (`wrangler.jsonc`
  + base-path `worker.mjs`, git-connected with per-PR preview versions); the CI
  `docs-site` job runs the same build, and the dashboard-side one-time setup is the
  runbook in `docs-site/DEPLOYMENT.md`.
- **Personal productivity, slice 2 — recents** (roadmap Phase 51, final slice — **the
  phase is complete and milestone M17 is met, closing every phase named into
  Horizon 9**): rendering a **`view: detail`** page records it in the user's bounded
  recent ring (20, deduped by URL with rapid reloads coalesced inside the cache TTL,
  bumped on revisit, labelled by the view's own title), listed and removable on the
  account page — never in the sidebar, so the chrome stays calm. The planned in-pattern
  *Pin this view* button was dropped as redundant: the header control already pins any
  page with its query string.
- **Personal productivity, slice 1 — pins** (roadmap Phase 51; design in
  `docs/productivity.md`): every shell page's header gains **Pin / Unpin** for the
  current URL — **query string included, so pinning a filtered list IS saving the
  filter** (one control covers Phase 39 list views, the Studio data browser, and
  dashboards alike). Pins render as a **Pinned** sidebar group on every page (the
  reserved `_shortcuts` variable, TTL-cached read) and are managed on the account page.
  Capped at 20 (oldest fall out), re-pinning bumps and relabels, and hrefs are
  **relative paths only** — absolute, `//`, and `/\` forms are refused
  (`TQL-ACCOUNT-4802`), so a pin can never point off-site. One managed
  `tql_user_shortcut` table (keyed on the href's SHA-256 so the composite key fits every
  dialect's index limits) behind the `ShortcutStore` SPI; recents ride the same store in
  slice 2.
- **Workflow delegation and absence, slice 2 — operator visibility + the gallery proof**
  (roadmap Phase 52, final slice — **the phase is complete and milestone M16 is met**):
  the IAM admin gains a read-only **Active delegations** panel
  (`/_tesseraql/admin/delegations`, `iam.admin.view`) — who is absent, who covers, until
  when — and the full M16 loop is proven against the **real purchase-request gallery
  app**: absence set on the account page, the submitted request lands with the delegate
  marked "for" the approver, the absent approver is refused, the delegate approves as
  themselves, and after the window new requests reach the approver again.
- **Workflow delegation and absence, slice 1** (roadmap Phase 52; design in
  `docs/delegation.md`): a standing **out-of-office rule** (one window, one delegate per
  subject, strictly self-service on the account page) redirects **new assignments at
  assignment time** — the transition `assign:` rows, the sweeper's `reassign` fallback,
  and the per-task hand-over target all resolve through one one-hop helper, so chains
  and loops are impossible by construction. **No identity is ever borrowed**: the
  delegate becomes the assignee and acts as themselves, and the absent approver holds
  nothing. The task row records `delegated_from`, so meant/received/acted is a
  structural trail on the persisted task. Candidate groups and already-open tasks are
  deliberately untouched.
- **Credential lifecycle, slice 3 — TOTP second factor** (roadmap Phase 50, final slice
  — **the phase is complete and milestone M15 is met**): RFC 6238 over
  `javax.crypto.Mac` (HmacSHA1, 6 digits, 30 s steps, ±1 window) with a hand-rolled
  Base32 — no new dependency, validated against the RFC's own test vectors. Enrollment
  lives on the account page and **confirms with a valid code before anything
  enforces**; a confirmed enrollment makes the login's optional code field required,
  with missing, wrong, and **replayed** codes all answering exactly like a wrong
  password — the replay guard is the store's `last_used_step` **compare-and-set**, so a
  captured code can never be accepted twice. Disabling re-verifies the password
  (`TQL-ACCOUNT-4804`). QR rendering and recovery codes are deliberately out of scope
  and documented.
- **Credential lifecycle, slice 2 — invitations** (roadmap Phase 50): the bundled IAM
  admin grows **Invite user** — the account is created with status `INVITED` (which the
  credential contract already refuses at login) and the one-time accept link mails over
  the outbox; **the operator never knows a password**. `/_tesseraql/invite` sets the
  first password and the existing `enable-user` contract flips the account ACTIVE.
  Re-inviting a still-INVITED account politely resends (subject to the token cooldown);
  an already-usable login answers 409 — an invite can never take over an account.
  Requires `tesseraql.identity.invite.{channel,url}` (half-set fails the boot,
  `TQL-SEC-4120`); the token store is shared with slice 1's reset.
- **Credential lifecycle, slice 1 — password reset** (roadmap Phase 50; design in
  `docs/credential-lifecycle.md`): the login page grows **Forgot password?** when
  `tesseraql.identity.recovery` is configured (enabled + a mail channel + the confirm
  URL; anything half-set fails the boot with `TQL-SEC-4120`). The request leg answers
  the same neutral "sent" for unknown logins, missing emails, and cooldowns — no
  enumeration oracle; the destination comes from the new overridable
  `find-recovery-destination-by-login` pack contract (default: the ACTIVE user's
  `tql_users.email`) and the mail rides the outbox. Tokens live in the new managed
  `tql_credential_token` (256-bit, **SHA-256 at rest**, purpose-bound, **single-use by
  check-and-set**, issue cooldown, expiry prune). The confirm leg rotates through the
  existing `update-password` contract and **invalidates every session of the subject**;
  unknown, used, and expired links all answer the same honest dead-link page.
- **The in-app notification center, slice 2 — the surface** (roadmap Phase 49, final
  slice — **the phase is complete and milestone M14 is met**): the shared shell grows a
  **bell** with the unread badge (the reserved `_inbox` variable; the count reads
  through a 15 s TTL cache, so a page render costs a map lookup and local
  deliveries/reads refresh the badge at once), and **`/_tesseraql/inbox`** joins the
  bundled account app: newest-first list, per-message **Mark read** (owner-checked —
  someone else's message answers `TQL-ACCOUNT-4806`/404), **Mark all read**, and the
  honest empty state when no inbox channel is declared (then there is no bell at all).
- **The in-app notification center, slice 1 — delivery** (roadmap Phase 49; design in
  `docs/inbox.md`): a third channel type **`inbox`** delivers `recipient:`-addressed
  `notify:` events into the managed `tql_user_notification` table — the resolved
  recipient (and acting tenant) now ride the outbox envelope, `title`/`body` render as
  the channel's inline TEXT templates, and **delivery dedupes on the outbox event id**,
  so at-least-once redelivery never doubles a message. Read-state operations
  (`unreadCount`/`recent`/`markRead`/`markAllRead`) land with the store; read messages
  prune past `tesseraql.inbox.retentionDays` (default 90). An inbox notification
  without `recipient:` fails lint (`TQL-YAML-1034`); the Phase 48 opt-out silences at
  enqueue as before. The shell bell and `/_tesseraql/inbox` page follow in slice 2.
- **The account surface, slice 5 — app-declared preference groups** (roadmap Phase 48,
  final slice — **the phase is complete and milestone M13 is met**): an app declares
  fields in `config/preferences.yml` (`boolean` | `choice` | `text`, message-catalog
  labels, defaults); the account page renders them as an App settings section, writes
  stay bounded by the declaration (`TQL-ACCOUNT-4802`), and routes/templates/SQL read
  them back through the **`preference.<key>`** namespace — stored value else declared
  default, declared keys only. Lint `TQL-YAML-1030..1033`, a `preference` NOTE coverage
  kind, and the inventory gallery app dogfoods the file.
- **The account surface, slice 4 — sessions and password** (roadmap Phase 48): the
  account page lists the caller's active sessions (`tql_session` gains an indexed,
  nullable `subject` — pre-upgrade rows age out unlisted) with a **Sign out other
  sessions** action (`POST /_tesseraql/logout-others`, runtime-wired beside
  login/logout, CSRF-checked); and the **local-realm password change** verifies the
  current credential through the same contract the login path uses before writing the
  new hash via the `update-password` identity contract — wrong current password is
  `TQL-ACCOUNT-4804`, SSO-managed deployments answer the honest `4803` and the page
  says so. `SqlScripts` now tolerates the vendor duplicate-column/key codes so the
  re-runnable V2 bootstrap stays idempotent on every dialect.
- **The account surface, slice 3 — notification opt-out** (roadmap Phase 48): a `notify:`
  declaration gains an optional **`recipient:`** expression (e.g. `principal.subject`);
  when the resolved subject opted out of the channel, the enqueue path — command routes
  and job `notify:` steps alike — **writes no outbox row** and reports `{optedOut: true}`
  instead of an event id. Channels the operator marks `userOptOut: true` appear as toggles
  on the account page; channel-level notifications (no `recipient:`) are never affected.
- **The account surface, slice 2 — language and appearance** (roadmap Phase 48): the
  account page grows the two settings; a saved language flows through the Phase 22 locale
  chain via the new `preference.<key>` source kind (default order now
  `preference.ui.locale` before `principal.claim.locale`, so the choice works with zero
  configuration), and a saved theme re-skins the shared shell (`_theme` replaces the
  hardcoded `data-theme`; the renderer re-syncs a `tesseraql_theme` cookie so pre-login
  pages follow; `tesseraql.ui.theme` sets the operator default, falling back to today's
  dark). New `TQL-ACCOUNT` error domain (48xx).
- **The account surface, slice 1** (roadmap Phase 48 — opening Horizon 9; design in
  `docs/account.md`): the shared shell grows a signed-in **user menu** (avatar + native
  popover, rendered from the reserved `_account` variable beside `_csrf`/`_menu`), a bundled
  **`/_tesseraql/account`** system app serves the session principal's profile (the `auth-ui`
  precedent — on by default with console login, `tesseraql.apps.account.enabled: false` to
  opt out), and a managed **per-user preference store** (`PreferenceStore` SPI, cached;
  `tql_user_preference` with Oracle/SQL Server variants) is bound for the settings slices to
  come. The subject is always the session principal's, by construction.
- **Studio copilot chat** (roadmap Phase 44 — completing Horizon 8 in full; decision point 8
  resolved): an in-Studio panel (`/ui/copilot`) that drives the existing gated loop as tools
  against an **operator-configured** OpenAI-compatible endpoint — TesseraQL ships no model,
  the key stays a `${secret.*}` reference resolved lazily at call time, reads
  (routes/sources/lint/schema/preview) are free, the **only write is an audited draft**
  offered to the model solely when the chatting user holds an edit role, and applying stays
  a human action in the editor's diff-confirm UI. Bounded tool turns; honest disabled state
  when unconfigured; see `docs/copilot.md`.
- **The five-minute demo** (roadmap Phase 47, final slice — the phase, **milestone M12**,
  and Horizon 8 are complete): one command (`tesseraql serve --app examples/inventory-app
  --embedded-db`) boots a seeded, browsable gallery app with Studio open, and one container
  image (`deploy/Dockerfile.demo`) does the same with embedded PostgreSQL inside — no
  compose, no external database. `docs/five-minute-demo.md` is the Studio tour that walks
  the closed low-code loop end to end.
- **Template gallery** (roadmap Phase 47): three complete, declarative-only starter apps join
  `examples/` — **`purchase-request-app`** (the approval workflow: a `kind: workflow` document
  drives draft → submitted → approved/rejected in managed mode, with synthesized transition
  endpoints, a guard, task assignment, and the history on a declarative detail view),
  **`inventory-app`** (declarative views end to end: searchable/paginated list, a dashboard
  with stats/bar chart/low-stock table, forms, and a stock adjustment guarded by a
  declarative validation rule), and **`helpdesk-app`** (an app-mode workflow over the
  ticket's own `status` column plus a transactional `notify:` the suite asserts without
  SMTP). Each app is held to the marketplace admission profile, lints clean, and passes its
  own declarative suites against a real database in CI (`GalleryAppsIntegrationTest`).
- **Marketplace admission profile** (roadmap Phase 47, first slice — realizing the Phase 37
  admission gate): `tesseraql admission --app .` and the `tesseraql:admission` Maven goal run
  the machine-checkable bar a shared app must clear — declarative-only (no plugin jars, no
  service bindings, no unauthenticated writes), deny-by-default policies actually defined
  (the `TQL-SEC-4030` warning is promoted to a failure), bounded egress (no bare `*`),
  CSP on every HTML page (documented `/fragments/` convention exempt), governance approvals
  current, and zero lint errors (`TQL-ADM-4701..4706`; see `docs/admission.md`). Dogfooded:
  the shipped example apps are held to the bar in CI — which immediately caught and fixed a
  real gap (the user-admin example's admin page served HTML without CSP headers).
- **Release diff — "what does this deploy change"** (roadmap Phase 46, final slice — the
  phase is complete; the promotion recipe is documented in
  [docs/promotion.md](docs/promotion.md)): `ReleaseDiff` compares
  two app trees deterministically — routes added/removed/changed, the OpenAPI contract diff,
  the migration list the deploy will run, security-policy changes, and the table-level schema
  delta when both trees carry the introspection sidecar. Surfaced three ways: the
  `tesseraql release-diff --app <candidate> --baseline <tree>` CLI command (Markdown or
  `--json`, `--out` to write a file), the `tesseraql:release-diff` Maven goal (writes
  `release-diff.md`/`.json` beside the release evidence), and a docs-portal **Release diff**
  page consolidating the captured-baseline diffs (API changelog, schema DDL) with the app's
  migration set.
- **Environment profiles** (roadmap Phase 46, first slice): one switch — `--env <profile>` on
  `tesseraql serve`, `TESSERAQL_ENV`, or `-Dtesseraql.env` — merges
  `config/env/<profile>.yml` between the app's base config and Studio's `overlay.yml`, so the
  profile carries the environment's tuning while dev-time Studio edits still win on top. A
  named profile without a file fails startup fast (a typo'd environment must never silently
  run another environment's config); no profile keeps today's behavior exactly.
- **Business-route audit log + custom error pages** (roadmap Phase 45, final slice — the
  phase is complete): `tesseraql.audit.routes.enabled: true` records one durable
  `tql_route_audit` row per invocation — actor, tenant, route, method, path, status,
  duration, `trace_id`, and the **declared** params as JSON with `mask:`/`classification:`
  fields excluded wholesale; a failed insert never fails the request.
  `GET /_tesseraql/ops/audit` reads the trail, gated and narrowed to the caller's
  `ops.app.<name>` grants. **Per-app custom error pages**: `templates/errors/<status>.html`
  (or `errors/error.html`) renders for failed top-level browser GETs, while htmx swaps keep
  the inline fragment and API clients keep the JSON envelope.
- **Structured logging with trace-id correlation** (roadmap Phase 45): the CLI distribution
  ships a JDK-only SLF4J provider — before it, the standalone runtime had NO log backend at
  all (every line fell into SLF4J's NOP sink). Plain text by default, `--log-format json`
  for structured lines, `--log-level` for the threshold; every line carries the MDC, the
  runtime puts the request's `traceId`/`spanId` there, and Camel bridges the keys across
  async steps. An **opt-in HTTP access log** (`tesseraql.logging.accessLog: true`) emits one
  correlated line per request on the `tesseraql.access` logger, including the authenticated
  user. The Spring distribution keeps Boot's Logback untouched.
- **Safety valves** (roadmap Phase 45): every route SQL statement is now bounded **by
  default** — 30 seconds, the app-wide `tesseraql.sql.timeoutSeconds`, or a per-binding
  `sql.timeoutSeconds` override (`0` opts a deliberately long-running statement out;
  negative values are lint error `TQL-YAML-1021`) — so a runaway query is cancelled by the
  driver instead of holding a pool connection forever. Connection pools expose the remaining
  HikariCP tuning knobs (`minimumIdle`, `idleTimeoutMillis`, `maxLifetimeMillis`,
  `keepaliveTimeMillis`, `leakDetectionThresholdMillis` beside the existing
  `maximumPoolSize`/`connectionTimeoutMillis`), and `docs/deployment.md` now states the
  **per-node semantics** of the rate/concurrency limiters and lanes on multi-node
  deployments (a budget of N allows N × node-count cluster-wide; size per node or enforce at
  the balancer).
- **Truthful health** (roadmap Phase 45, first slice): `GET /_tesseraql/health/live` is pure
  liveness (the process answers; never touches a dependency) and
  `GET /_tesseraql/health/ready` — also served by the bare `/_tesseraql/health` — is the
  readiness roll-up: every configured datasource is probed live (`Connection.isValid` per
  pool) and the status degrades to **`DOWN` with HTTP 503** when one fails, so load balancers
  actually shed traffic; `WARN` stays a 200 with active alerts. A contributor that cannot
  reach its store during an outage counts as DOWN instead of crashing the endpoint into a 500,
  and the Spring Actuator bridge maps `DOWN` to `Health.down()`. The container HEALTHCHECK now
  targets `/health/live` and the kamal proxy check `/health/ready`; a new
  `tesseraql.datasources.<name>.connectionTimeoutMillis` knob bounds both borrower waits and
  the probe's detection latency.
- **Pull-based metrics** (roadmap Phase 45; decision point 9 resolved — JDK-only scrape path):
  the `Meter` abstraction gained latency **histograms**, an always-on JDK-only
  `AggregatingMeter` records per-route invocation counters, outcome-classed error counters,
  and duration histograms (`routeId`/`method`/`outcome`, status class keeps cardinality
  bounded), and `GET /_tesseraql/metrics` exposes them in Prometheus text format 0.0.4 —
  opt-in (`tesseraql.metrics.enabled`) and bearer + `ops.metrics.view` policy gated by
  default, with an explicit `tesseraql.metrics.unauthenticated` escape hatch for
  cluster-internal scrapes. OTLP push now carries the same histograms
  (`tesseraql-observability` maps them onto OpenTelemetry), and a ready-made Grafana
  dashboard ships at `deploy/grafana/tesseraql-dashboard.json`.
- **Authoring feedback outside Studio** (roadmap Phase 43, Track J5 — the phase is complete):
  the shipped JSON Schema now covers the full route/job/view document surface (recipe enum
  kept in sync with the linter by a build-time drift test), and `tesseraql new` wires it into
  the scaffolded repo — `.vscode/tesseraql-v1.schema.json` + a `yaml.schemas` association and
  a `redhat.vscode-yaml` recommendation — so any editor with a YAML language server validates
  and completes TesseraQL documents offline. **Lint findings gained positions**: `LintFinding`
  carries optional line/column, document rules point at the first occurrence of the offending
  key, and the CLI `lint`, Maven `tesseraql:lint`, and Studio health page render
  `source:line`.
- **Studio data-browser row editing** (roadmap Phase 43, Track J4): browser rows link **Edit**
  when the row editor's own opt-in (`tesseraql.studio.dataBrowser.edit.enabled`), the caller's
  `editRoles`, and a table primary key all line up. The PK-scoped single-row UPDATE validates
  identifiers against the live catalog, binds values coerced to the column types (a ticked
  empty value sets `NULL`), never touches PK columns, must affect exactly one row, always
  requires an explicit confirm, and is audited as the row identity plus column names — never
  values. The master-data maintenance screen nobody has to build.
- **Studio test recorder** (roadmap Phase 43, Track J3): a successful API-console invocation
  of a query route can be saved as a declarative test case — the sent parameters reverse-map
  onto the route's `sql.params`, the sandbox captures the row count as the expectation (the
  case passes by construction), and the `sql:` case lands in `tests/studio-recorded-test.yml`,
  runnable from the route's test runner and in CI like any hand-written case. A citizen
  developer's manual check becomes a regression test in one click.
- **Studio connector & SSO authoring** (roadmap Phase 43, Track J2): a **Connectors** page
  edits the managed connector config through the same gated overlay-write path as policies —
  egress allow-lists for `http.outbound` and `connectors.poll` (always behind an explicit
  confirm), outbound/poll credentials, and inbound webhook verifiers — with secret
  **references** only (`${secret.env.NAME}`): a literal secret value is rejected before it can
  reach a config file, and displayed values are redacted. The OIDC/SAML/SCIM/identity wizards
  became write-through: **Write to config overlay** lands the settings in `config/overlay.yml`
  beside the existing snippet download. Everything is edit-gated, audited, and honestly
  restart-bound (these sections load at boot; the pages say so).
- **Studio form-driven route editor** (roadmap Phase 43, Track J1 — first slice): a **Route
  form** page edits a route document's governed fields as structured form fields — recipe,
  auth, policy (suggested from the app's declared policies), CSRF, and the `input:` block as
  rows (name, type, required, min/max, lengths, pattern, enum). The form parses the pending
  draft when one exists (else the served source), mutates the document tree — unknown keys and
  unmanaged attributes survive; comments are not preserved and the page says so — and saves a
  **draft** through the existing preview/diff/apply flow, so the text editor stays the escape
  hatch and applying serves immediately via the Phase 42 hot reload.
- **The instant loop — Migrate now** (roadmap Phase 42, final slice — the phase is complete):
  the Studio migration page's created view gains a confirm-gated **Migrate now** action that
  applies the app's pending Flyway migrations to the dev datasource on demand (same path as
  startup: main set, tenant pools, named per-datasource sets; edit-gated and recorded to the
  audit trail with the applied count reported). Schema &rarr; scaffold &rarr; serve now runs
  end-to-end without a process bounce, and the example app defines the starter
  `app.read`/`app.write` policies so scaffolded slices serve out of the box.
- **The instant loop — dynamic route mounting** (roadmap Phase 42, first slice): applying in
  Studio now serves immediately. The hot reloader diffs the re-read manifest against the running
  routes — brand-new route documents **mount** without a restart, removed ones **un-mount**, kept
  ones rebuild in place — and the apply endpoints (draft apply, bulk apply, scaffold apply; JSON
  API and UI alike) reload as part of the request, so "needs a restart to be served" is gone from
  the route-authoring flow. Every route compiles individually: one broken definition takes only
  itself out, serving a clear 500 (`TQL-CAMEL-3103`) that carries its compile error while every
  neighbor keeps serving; an unparseable route document on disk degrades the same way (the reload
  loads the manifest tolerantly and reports the parse failure per-route) instead of failing the
  whole reload. Each reload re-runs the cross-app route-conflict guard and reports
  `{reloaded, added, removed, failed}` (the `/_tesseraql/studio/reload` endpoint and apply responses carry it).
- **Response shaping** (roadmap Phase 41, final slice — the phase is complete; see
  [docs/response-shaping.md](docs/response-shaping.md)): every `response.json.body` leaf and
  `response.html.model` value is now a core-language **expression** compiled at build time —
  dotted paths behave exactly as before (with a legacy fallback for unparsable leaves), and
  computed fields (`params.qty * params.price`, `upper(trim(...))`) come for free. **`nest:`**
  composes a named child query's rows under each parent row of a body key (grouped by a declared
  `on:` join key, canonical-text key matching, parents copied — `TQL-YAML-1019`). And
  **`statusWhen:`** maps business conditions to HTTP statuses declaratively on both JSON and
  HTML responses (first truthy arm wins; pre-compiled, `TQL-YAML-1020`; each arm's status rides
  into the generated OpenAPI).
- **Declarative pagination** (roadmap Phase 41, first slice; see
  [docs/pagination.md](docs/pagination.md)): a `page:` block on `query-json`/`query-html`
  routes paginates the main query by appending the dialect's clause at execution time — the
  authored 2-way SQL stays plain-tool runnable with no hand-written `LIMIT`
  (`TQL-YAML-1018` warns). Offset strategy owns framework `?page=`/`?size=` parameters
  (bounded by `maxSize`); keyset (`strategy: keyset, by:`) keeps the cursor predicate in the
  SQL while the framework derives `page.next` from the last row. One row beyond the page
  answers `hasNext` without a count; `count: true` adds `totalRows`/`totalPages`. The `page`
  context entry feeds bodies (`meta: page`) and templates; responses automatically carry
  `X-Total-Count` and RFC 8288 `Link` `rel="next"`/`rel="prev"`; a paginated `view: list`
  renders the kit's `hc-pagination` nav preserving search/sort state. Machine-checkable:
  `TQL-YAML-1015..1018` lint, a `page` coverage kind, and the OpenAPI
  `page`/`size`/`after` parameters. `tesseraql scaffold crud` lists paginate out of the box
  (size 50, maxSize 200, counted; the gallery regenerated).

- **Expression-language depth** (roadmap Phase 40, final slice — the phase is complete; see
  [docs/declarative-validation.md](docs/declarative-validation.md)): the core expression
  language — shared by `validate:` rules, `requiredWhen`, `headersWhen` guards, and workflow
  guards — gains decimal-exact arithmetic (`+ - * / %`, `BigDecimal` semantics so
  `qty * price <= budget` is a declarable rule with no float drift; `+` concatenates strings;
  `null` operands propagate), unary minus, and a fixed whitelist of pure functions (`length`,
  `lower`, `upper`, `trim`, `contains`, `startsWith`, `endsWith`, `matches`, `abs`, `round`,
  `floor`, `ceil`, `min`, `max`, `coalesce`). Unknown function names and wrong arities fail at
  parse — and therefore at build/lint — so evaluation still cannot reach outside the whitelist
  (no method calls, no reflection).

- **Input, validation, and path-parameter depth** (roadmap Phase 40, first slice; see
  [docs/declarative-validation.md](docs/declarative-validation.md)): declared inputs gain
  `pattern` (anchored regex, pre-compiled by lint `TQL-YAML-1012`), `minLength`, semantic string
  `format:` validators (`email`/`uuid`/`url`; unknown values are `TQL-YAML-1013` — for
  date/datetime/number fields `format:` remains the parse pattern), and `requiredWhen`
  (conditional requiredness in the core expression language, compiled at build,
  `TQL-YAML-1014`), each rejecting with a stable field-scoped code and localized en/ja message.
  A path parameter declared under `input:` now publishes its coerced, typed value in the
  `path.*` namespace. The declared constraints ride into the generated OpenAPI (`pattern`,
  length/value bounds, `format`, enums) on parameters, bodies, and typed path parameters.

### Fixed

- **`tesseraql lint --app .` no longer crashes on a relative app home.** The linter
  relativized the manifest loader's absolute source paths against the app home as
  given, so the documented relative form threw
  `IllegalArgumentException: 'other' is different type of Path` on any app with
  routes; the app home is now absolutized on entry (the MCP dev-tools and the Maven
  goal ride the same fix).
- **`min`/`max` bounds are decimal-exact.** The bound check compared `number.longValue()`, so
  `max: 5` admitted `5.9` and `min: 0` admitted `-0.9`; bounds are now `BigDecimal`-compared and
  fractional bounds (`min: 0.5`) are declarable. (`spec.json` and OpenAPI emit the same numbers
  as before for integer bounds.)
- **`head.yml`/`options.yml` route files fail lint with a clear code** (`TQL-YAML-1011`) instead
  of exploding deep in the route compiler with `Unsupported HTTP method`.

- **Declarative dashboards** (roadmap Phase 39, slice 4 — the phase is complete; see
  [docs/declarative-views.md](docs/declarative-views.md)): a `view: dashboard` document renders
  query-backed panels on the kit's `hc-grid` — a `stat` (single value), a `sparkline`, a `chart`
  (bar or line, rendered server-side as deterministic inline SVG wearing the kit's `hc-chart`
  skin: every color a `--hc-chart-*` token, the gridline group colored by the kit's
  `[aria-label$=grid]` rule, tooltips via `<title>`, no client scripting, CSP-clean), or an
  embedded `table` — each over the route's main `sql` or a named query (`TQL-VIEW-3308` when a
  panel source is undeclared). The example gallery gains a stats dashboard
  (`examples/user-admin-app/web/users/board/stats`). No upstream component brief was needed:
  `hc-chart` and `hc-grid` already ship in Hypermedia Components as CSS-only components.

- **Scaffold on views** (roadmap Phase 39, slice 3; see
  [docs/declarative-views.md](docs/declarative-views.md)): `tesseraql scaffold crud` now emits
  declarative view documents instead of hand-written templates — one list route renders through
  the `tql/view/list` pattern (live search box, server-driven sortable headers re-rendered over
  htmx via `hx-select` on the route itself — the separate `fragments/table` route is gone), the
  create/edit forms derive their fields from the command routes' `input:` blocks, and a shared
  `frags.html` carries the slot fragments (New button, back link, and the confirmed delete the
  edit view mounts in its footer slot). The list pattern grew the composition to make that
  possible: `search:` (the filter box, `TQL-VIEW-3309` when the input is undeclared),
  `sortable: true` columns (`?sort=&dir=` header links + `aria-sort`, applied by the route's
  enum-gated inputs, `TQL-VIEW-3310`), `text:`/`link:` action columns, per-record form `action:`
  placeholder resolution, camelCase→snake_case prefill fallback (plus per-field `column:`),
  number-input `step`, and not-found empty states. `tesseraql new` now also generates
  `config/menu.yml`, so scaffolded pages navigate through the server-rendered app menu. The
  example gallery regenerated on views (byte-identical dogfood check unchanged).

### Changed

- The `tql/view/table` pattern's contract is now `table(tableId, columns, rows)` (the id anchors
  the htmx sort/search swap region), and non-sortable header labels render inside a `<span>`.

- **Declarative views: detail, relations, slots, and eject** (roadmap Phase 39, slice 2; see
  [docs/declarative-views.md](docs/declarative-views.md)). A `view: detail` renders a labelled
  value list over the route's row and composes the route's named `queries:` underneath as child
  tables (`children:`, each with the list column model; `TQL-VIEW-3308` when a source is not a
  named query). Views gain **named slots** (customization ladder L1): `header`/`footer` on every
  kind plus `actions` beside a form's submit button, each filled by an app fragment referenced as
  `template::fragment` (`TQL-VIEW-3306` for an unknown slot name). The datagrid markup moved into
  a shared overridable `tql/view/table` pattern used by lists and detail children. And the
  ladder's L3 shipped: `tesseraql scaffold eject-view --route web/…/get.yml` renders the view's
  pattern once into a checksum-stamped, hand-owned template and flips the route from `view:` to
  `template:` (a list/detail must pin explicit `columns:`/`fields:` first). The example gallery's
  board gains a header slot, per-row links, and a detail page with a groups child
  (`examples/user-admin-app/web/users/board/{name}`).
- **Declarative views** (roadmap Phase 39, slice 1; see
  [docs/declarative-views.md](docs/declarative-views.md)): a `kind: view` document colocated
  with its route (`*.view.yml`) and referenced by `response.html.view` renders the page through
  framework-shipped Hypermedia Components patterns — no hand-written template. A `view: list`
  renders an `hc-datagrid` over the route's rows (columns derived from the result set when
  `columns:` is omitted; per-row `link:` templates); a `view: form` derives its fields from the
  `action:` route's `input:` block, so the rendered HTML constraints (`required`, `maxlength`,
  `min`/`max`, enum options) are the same declarations the server enforces. Customization is a
  ladder: view-document keys (L0), pattern overrides — an app shadows `tql/view/{list,form,field}.html`
  by shipping the same-named file under `templates/` (L2, resolved app-home-first) or retargets one
  view via `template:` — and ejecting to a hand-owned template (L3). Machine-checkable:
  the `TQL-VIEW-33xx` lint family (unresolved/duplicated references, unknown view kind, action
  route without inputs, undeclared fields, unknown widgets, override fragment signatures) and a
  `view` coverage kind (`coverage.thresholds.view`). The example gallery gains a view-backed
  board page (`examples/user-admin-app/web/users/board`).

## 0.4.1 - 2026-06-20

### Fixed

- Release: the **Windows app-image launcher now writes to the console**. The jpackage build omitted
  `--win-console`, so `tesseraql.exe` was a GUI-subsystem binary that ran but printed nothing when
  invoked from cmd/PowerShell — the tool looked unresponsive. It is now a console launcher, and the
  CI smoke test asserts the launcher produces stdout so the regression cannot recur. Affects the
  `tesseraql-<version>-windows-*.zip` app-image (first shipped in 0.4.0); the
  `tesseraql-cli-*-dist.zip` console launcher was unaffected.

## 0.4.0 - 2026-06-20

### Added

- Admin console **browser-session login**, switchable to **OIDC or SAML** by config alone. The
  bundled UIs (Studio, Operations console, IAM Admin) now sign in through a login page
  (`GET /_tesseraql/login`, served by a bundled `auth-ui` app) rather than a hand-minted token —
  opening a protected page with no session redirects there. Password, OIDC, and SAML all create the
  same `tesseraql_sid` session, so enabling `tesseraql.oidc.enabled` / `tesseraql.saml.enabled`
  switches the method with no per-route change; `tesseraql.console.login.password.enabled: false`
  runs SSO-only. State-changing actions are CSRF-protected. See [authentication.md](docs/authentication.md).
- Auth: the page a user originally opened is threaded through every login method as a sanitized,
  same-origin `next` (password redirect, OIDC via a short-lived cookie, SAML via RelayState), so SSO
  returns to the requested page. A single open-redirect guard rejects off-site targets.
- CLI: `serve --embedded-db` now **prints the connection URL and port**, and **`--embedded-db-port`**
  pins the embedded PostgreSQL to a fixed (localhost-only) port so a local client can attach.
- Studio: a public **`/_tesseraql/studio` → `/_tesseraql/studio/ui` redirect**, so the bare,
  documented path resolves instead of 404ing.
- CLI: a passive **"a newer release is available" notice** (Phase 38 Tier 1). On run the CLI prints a
  one-line hint to stderr when a published GitHub release is newer than the running version. The check
  is cached per user (`~/.tesseraql/update-check.properties`, refreshed at most once a day on a daemon
  thread), so it adds no latency to a command, never touches the network on the hot path, and fails
  silent when offline. Opt out with `TESSERAQL_NO_UPDATE_NOTIFIER=1`; it is also skipped automatically
  whenever `CI` is set. See [roadmap.md](docs/roadmap.md) Phase 38.
- Release: the per-OS **jpackage app-image** (a launcher with a bundled JVM — no JRE prerequisite) is
  now attached to each GitHub release as `tesseraql-<version>-<os>-<arch>.{tar.gz,zip}`, instead of
  only being kept as a time-limited CI artifact. A stable download for users without a JRE
  (Phase 38 Tier 1).

### Changed

- The bundled admin UIs now authenticate by **browser session (`auth: browser`)** instead of
  `auth: bearer`. The hand-built Studio JSON API under `/_tesseraql/studio/*` stays `auth: bearer`
  for programmatic callers; MCP is a separate transport and unaffected.

### Fixed

- **`serve --embedded-db` no longer crashes building the manifest checksum index** when the data
  directory lives inside the app home: the index walk hashed PostgreSQL's live data files, which the
  running `postgres` holds OS locks on (a hard failure on Windows; non-deterministic hash elsewhere).
  Any PostgreSQL data directory (recognized by its `PG_VERSION` marker) is now pruned from the walk.

## 0.3.1 - 2026-06-20

### Fixed

- CLI: **`serve --embedded-db` no longer crashes with
  `NoClassDefFoundError: org.postgresql.ds.PGSimpleDataSource`** (#178). The embedded-db supervisor
  (zonky `EmbeddedPostgres`) loads the PostgreSQL JDBC driver at runtime to verify the embedded
  process is ready, but the CLI dist fat jar was missing it: `tesseraql-cli` re-declared
  `org.postgresql:postgresql` directly at `test` scope, which overrode the `compile`-scoped driver
  it otherwise inherits transitively from `tesseraql-camel-runtime`, excluding it from the runtime
  classpath and the shaded jar. The driver is now declared explicitly at compile scope so the dist
  bundles it.

## 0.3.0 - 2026-06-19

### Changed

- UI: **adopted Hypermedia Components 0.1.6**, retiring three local stand-ins for the kit's new
  auto-installed behaviors (the upstream issues this project filed, now shipped). The share-URL Copy
  buttons use **`data-hc-copy`** (`installCopy`, #270) instead of the `tesseraql.js` `[data-copy]`
  handler; the route/table "On this page" navs are an **`hc-toc`** with **`data-hc-spy`** scrollspy
  (`installSpy`, #271), so the current section's link is highlighted (`aria-current="location"`); and
  the shell sidebar opts into **`data-hc-nav-current`** (`installNavCurrent`, #272) for active-link
  marking instead of the `tesseraql.js` `aria-current` script. All three are CSP-clean (declarative
  markup, behaviors from the same-origin bundle) — `tesseraql.js` shrinks to just the htmx
  error-swap wiring and the live-editor SQL grammar.

### Added

- 2-way SQL: **embedded variables** (`/*# template *​/`, Doma-style). A `{placeholder}` in the
  template is interpolated into the SQL *text* at render time (not bound as `?`), for an
  identifier-position fragment a bind cannot drive — a dynamic `ORDER BY` column, sort direction, or
  table name. The whole fragment lives in the comment, so the statement stays runnable in a plain SQL
  tool. Because the value is written into SQL text it must be safe: the linter requires every
  placeholder to resolve to an `enum`-constrained input (`TQL-SQL-2109`), and the renderer rejects a
  resolved value carrying SQL meta-characters (`TQL-SQL-2108`) as defense in depth. See
  [transactional-writes.md](docs/transactional-writes.md#embedded-variables-dynamic-identifiers).
- Scaffolding: the **CRUD list datagrid is sortable** — every column header sorts server-side. Each
  header links to `fragments/table?sort=<col>&dir=<asc|desc>`, swapped in over htmx (the search box
  carries the current sort and vice-versa via `hx-include`), and `aria-sort` drives the kit's sort
  arrow — CSP-clean, no inline JS (`hc-datagrid` expects server-driven sort: its JS only sets
  `aria-sort`, never reordering rows). The generated `search.sql` orders by a single embedded variable
  `/*# order by t.{sort} {dir}, t.<pk> *​/` — the whole clause lives in the comment, so the file stays
  runnable in a plain SQL tool, with the primary key as a stable pagination tiebreaker — and the
  `sort`/`dir` inputs are `enum` allowlists with defaults, so an interpolated value can only be a known
  column or direction (no injection; enforced by `TQL-SQL-2109`).

### Changed

- Scaffolding: the **CRUD list table now renders as a Hypermedia Components `hc-datagrid`** instead
  of a plain `hc-table`. The generated `web/<table>/fragments/table/table.html` wraps the rows in the
  kit's datagrid (`hc-datagrid__scroll` → `hc-datagrid__table` with `__head`/`__headcell`/`__body`/
  `__row`/`__cell`), so wide scaffolded tables scroll horizontally with the header in view and pick up
  the kit's grid styling — degrading to a plain styled grid with no JavaScript (CSP-clean, no inline
  JS). Markup-only: the route, search SQL, and live-search wiring are unchanged. The dogfooded example
  gallery (`examples/scaffold-demo-app`) is regenerated to match.

### Added

- Studio: **search polish and a SQL-builder doc fix** (platform-UX track H8, completing the track).
  The docs search lifts its query operators out of the placeholder into a visible hint
  (`status:passing|failing`, `coverage:covered|untested`) and the results fragment now leads with a
  result count. The standalone SQL builder's intro is corrected to the actual bind style — a directive
  names a bind (`/* id */`) resolved against the route's `sql.params`, each binding from `params` —
  instead of the stale `/* params.id */` / "values from body" copy left over before the bind-style
  fix. This completes Track H (Studio platform UX, H1–H8).

- Studio: **clearer identity-provider setup wizards** (platform-UX track H7). The SAML/OIDC/SCIM/
  identity-realm wizards threw jargon (ACS URL, NameID, OID attributes, SCIM outbound, realm type) at
  the user with no explanation, and the index gave no "which one, in what order?" guidance. The wizard
  index now describes each wizard and says to start with the identity realm; the jargony fields carry
  concise inline help (what the field is, where it's registered, when it applies).

- Studio: **Copy buttons on the share-URL fields** (platform-UX track H6). The read-only share-link
  inputs on the route, table, and coverage pages forced a manual select+copy. Each now has a **Copy**
  button driven by a small `[data-copy]` behavior in `tesseraql.js` (copies the named field's value
  via the Clipboard API and flips its label to "Copied" briefly). Copy needs JS and the strict CSP
  forbids inline handlers, so it lives in the shared app bootstrap — a candidate to upstream into the
  hc kit. A harmless no-op where the Clipboard API is unavailable.

- Studio: **a live filter on the audit trail and the drafts list** (platform-UX track H5). Both were
  dense tables with no way to narrow them (audit grows unbounded). Each now carries the explorer's
  live-filter pattern — an htmx filter input that re-selects a swappable `#…-table` region. The audit
  filter searches **server-side over the whole log** before the newest-200 window applies (so it
  reaches older actions), and the window cap is now stated rather than silent; the drafts filter
  narrows its list in the view. (`StudioService.auditEntries(limit, query)`,
  `StudioViews.audit/drafts(…, query)`, a `q` input on both routes.)

- Studio: **breadcrumbs and an "On this page" jump nav on the detail pages** (platform-UX track H4).
  The route reference (8+ sections) and the table reference were long scrolls with no in-page
  wayfinding. Each now carries a breadcrumb in the header (Docs › ‹id› / Schema › ‹table›) and a jump
  nav of native `#anchor` links to each present section (the sections gained `id`s; the jump links
  share each section's condition, so only real anchors are offered). Pure HTML anchors — CSP-safe,
  no JS or CSS.

- Studio: **the source editor's secondary tools are collapsible panels** (platform-UX track H3). The
  editor stacked 9+ always-open panels in one card, so the page overwhelmed and Save/Apply/Discard sat
  far below the preview output. Each tool — Rendered preview, Compare, Dry-run, Tests, SQL builder —
  is now a uniform `<details class="hc-disclosure">` panel (Rendered preview open as the primary
  feedback; the on-demand tools collapsed), so the page is compact and the primary actions are within
  reach. Native `<details>`, so CSP-safe with no JS or CSS.

- Studio: **a Studio section sidebar nav** (platform-UX track H1). Studio pages used the shell's
  `page(...)` form, which renders only the 3-app system nav, so the Studio sections were reachable
  only via the explorer's header link cluster. A new `tql/shell :: studio-page(...)` form mounts a
  `studio-nav` sidebar (Explorer, Docs, Coverage, Schema, Export, Scaffold, Migration, SQL builder,
  Drafts, Audit, Wizards, then the system apps); the 20 authenticated `studio/ui/**` pages adopt it
  (the public share views keep the plain `page(...)`). `tesseraql.js` highlights the current section
  via `aria-current`. The explorer header drops its now-duplicated link cluster.
- Studio: **loading indicators on every async action** (platform-UX track H2). No template used
  `hx-indicator`/`aria-busy`, so a slow database call (live render, dry-run, run-tests, scaffold,
  migration build, SQL builder, search) gave no "working" cue and read as a hang. A reusable
  `tql/shell :: busy(label)` fragment renders an htmx-native `htmx-indicator` (announced via
  `role="status"`), and each submit form disables its button (`hx-disabled-elt`) while the request is
  in flight. CSP already allows `style-src 'unsafe-inline'`, so htmx's injected indicator style
  applies with no custom CSS or JS.
- Studio: the 2-way SQL builder is available inline in the source editor (follow-on). When editing a
  route SQL file (`web/**/*.sql`), the editor offers a **SQL builder** panel — the same table /
  operation / filter-column controls as the standalone page — whose **Append to editor** button drops
  the generated snippet straight into the editor's textarea (htmx appends it, so existing content is
  kept), instead of having to copy it from the standalone page. `StudioViews.source` flags a route SQL
  file (`isRouteSql`) and the `studio.source` provider populates its table dropdown from the schema
  overlay; the panel reuses the existing build/columns endpoints.

- Studio: IN-list and optional (`/*%if*/`) filters in the 2-way SQL builder, and a corrected,
  self-documenting bind style. The SQL builder gains **select by column (in list)** —
  `where <col> in /* <col> */ (<dummy>)` — and **select by column (optional)** —
  `where 1 = 1 /*%if <col> != null */ and <col> = /* <col> */ <dummy> /*%end*/`, the common
  optional-search-filter pattern. The generated binds now reference the **param name** the route's
  `sql.params` maps (`/* id */`, resolved against `sql.params` at render — the runtime renders 2-way
  SQL against the resolved binds, not the request namespaces), rather than a request expression, and
  every snippet is **prefixed with a `-- sql.params` comment** listing the mapping each bind needs
  (each from `params.<name>` — the coerced declared inputs, matching the `scaffold crud` convention)
  so the snippet is complete and correct. `SqlBuilder` adds the new operations and the param-mapping
  prefix.

- Studio: a by-column filter in the 2-way SQL builder (follow-on). The SQL builder gains a
  **select-by-column** operation and a **Filter column** dropdown that is cascade-loaded from the
  selected table's columns (htmx, on table change) — so you can generate
  `select … from <t> where <col> = /* <col> */ <dummy>` filtering on any column, not just the
  primary key, with the bind typed from the column. New `studio.sqlBuilder.columns` cascade provider
  and `/_tesseraql/studio/ui/sql-builder/columns` fragment; `SqlBuilder.generate` gains the filter
  column.

- Studio: a 2-way SQL builder (Studio backlog: schema-driven authoring). A new **SQL builder** page
  (linked from the explorer when editable) generates a route's `select`/`insert`/`update`/`delete`
  **2-way SQL** for an introspected table and operation — with the bind directives written for you
  (`/* id */ 0`) so the template stays runnable in a plain SQL tool — to copy into a route's
  `.sql` file. It is schema-driven: the projected/inserted/updated columns and the `where` key come
  from the table's introspected columns and primary key (identity columns are skipped on insert), and
  each bind's dummy literal is typed from the column (`0` for a number, `false` for a boolean, `'x'`
  otherwise). Binds map from `params.<name>` (the coerced declared inputs). New pure `SqlBuilder`
  and `DocService.tableByName`; the `studio.sqlBuilder.new` / `studio.sqlBuilder.build` providers and
  `/_tesseraql/studio/ui/sql-builder` page.

- Studio: generate a migration from the schema diff (Studio backlog: migration authoring, final
  slice). When a schema **baseline** sidecar is present (`.tesseraql/docs/schema.baseline.json` — copy
  a captured `schema.json` there), the New migration page can **generate the migration DDL** that
  transforms the baseline into the current schema, dropping it into the DDL field — to capture changes
  made directly to a database back into a migration so the schema stays reproducible. A new
  `SchemaDiff` engine compares the two introspected catalogs: a table or column present only in the
  current schema becomes a real `CREATE TABLE` / `ALTER TABLE … ADD COLUMN`, while a destructive
  change (a table/column removed, or a column type changed) is emitted only as a commented-out line to
  review — additive-and-safe, never applied automatically. New `DocService.schemaDiffDdl`; the
  `studio.migration.diff` provider and `/_tesseraql/studio/ui/migration/diff` route.

- Studio: a create-table builder in the migration DDL builder (Studio backlog: migration authoring,
  follow-on). The New migration page's DDL builder gains a **Create table** form: a table name, a
  **columns** textarea (one column definition per line — `name type [modifiers]`, emitted verbatim so
  you can write `not null`/`default …` inline), and an optional comma-separated **primary key**. It
  generates `CREATE TABLE <t> (<defs>[, PRIMARY KEY (<pk>)]);` and drops it into the DDL field. The
  one-definition-per-line textarea handles a variable column count in plain HTML (no per-row fields).
  New `MigrationDdl.createTable`; a `create-table` case in the `studio.migration.build` provider.

- Studio: the migration DDL builder's table and column inputs are populated from the schema portal
  (Studio backlog: migration authoring, follow-on). The builder's **Table** field is now a dropdown
  of the introspected tables (from the `schema.json` overlay), and the create-index **Columns** field
  autocompletes from the chosen table's columns — loaded by an htmx cascade when the table changes —
  so you pick from what exists instead of retyping names. The add-column **Type** field offers a
  datalist of common SQL types. All of it degrades to plain free-text fields when no schema overlay
  is present. New `DocService.tableNames` / `columnNames`; the `studio.migration.columns` cascade
  provider and `/_tesseraql/studio/ui/migration/columns` fragment route.

- Studio: form-driven DDL builder on the New migration page (Studio backlog: migration authoring,
  slice 3). A **DDL builder** helper generates standard DDL for two common operations from structured
  form input — **add column** (`ALTER TABLE … ADD COLUMN … [DEFAULT …] [NOT NULL]`) and **create
  index** (`CREATE [UNIQUE] INDEX … ON … (…)`, with a conventional `<table>_<cols>_idx` default name)
  — and drops it into the migration's DDL field to review and refine before creating the migration,
  so you don't hand-write the syntax. A forgiving helper (it trims input and rejects only an empty
  required field or an embedded `;`), not a validator — the result is shown in the editor. New pure
  `MigrationDdl` (`addColumn`/`createIndex`); the `studio.migration.build` provider and
  `/_tesseraql/studio/ui/migration/build` fragment route.

- Studio: dry-run a migration's DDL before it lands (Studio backlog: migration authoring, slice 2).
  A migration file's source editor now offers a **Dry-run** action that runs the DDL — the live
  editor buffer — against the dev datasource inside a **sandboxed, auto-rollback** transaction, so it
  applies and then rolls back without persisting, surfacing "applies cleanly" or the database error
  before the next migrate. **Postgres only**: its DDL is transactional and rolls back cleanly, whereas
  MySQL/Oracle/SQL Server auto-commit DDL, so a dry-run there is declined with a clear note. Gated
  like the test runner (`tesseraql.studio.testRunner.enabled`) and reusing the same `SandboxDataSource`.
  `StudioService.dryRunMigration` / `DdlDryRun` / `isMigrationPath`; `StudioTestService.dryRunDdl`;
  the `studio.migration.dryRun` provider and `/_tesseraql/studio/ui/dry-run` fragment route.

- Studio: author Flyway migrations from the editor (Studio backlog: migration authoring). A new
  **New migration** page (linked from the explorer when editable) creates a migration under
  `db/…/migration` — a **versioned** one auto-numbered `V<n>` (plain sequential, no zero-padding; the
  framework orders versions numerically so `V2` precedes `V10`) or a **repeatable** `R__<name>` for
  views/functions. It targets a chosen datasource and optional vendor overlay, writes the DDL through
  the same gated, audited write path as scaffolding (read-only master switch + per-role
  `editRoles` + the audit trail), refuses to overwrite an existing file unless forced, and links the
  result to the source editor. The new file needs a restart + migrate to be applied (the running app
  only lists it); Flyway has no built-in undo on the free edition, so the UI notes that rollback is
  fix-forward (write a follow-up migration). `StudioService.createMigration` / `nextMigrationVersion`;
  `studio.migration.new` / `studio.migration.create` providers; the `/_tesseraql/studio/ui/migration`
  page.

- Studio: multi-binding live render preview (Studio backlog category 3). The route render panel's
  **Use live data** toggle now runs not only a route's main `sql` but **every named `query`** through
  the sandbox, injecting each result under its model name — so a `query-html`/`query-json` route whose
  template/body references `<query>.rows` previews over real data, not just `sql.rows`. The queries run
  in authored order against an accreting context (a later query may read an earlier one's result),
  matching the runtime. `StudioService.RowSource` now returns the results keyed by model name;
  `StudioTestService.liveRows` runs the main query plus each named query. (Command `steps` — writes —
  are still not previewed live.) The declarative **Run tests** action already covered every binding.

- Coverage regression gate (Studio backlog category 3). Beyond the existing **absolute** coverage
  gate, the build can now fail when SQL coverage **drops against the previous run** — the guard that
  catches a change quietly lowering coverage while every absolute threshold still passes. The
  `report` goal compares this run's aggregate SQL line/branch coverage to the most recent
  `history.json` entry (captured before this run is appended) and, with
  `tesseraql.failOnCoverageRegression` set, fails the build if it dropped by more than
  `tesseraql.coverageRegressionTolerance` percentage points (default 0); a regression is always
  logged as a warning. `tesseraql test --report --fail-on-regression [--regression-tolerance N]`
  exits `2` for the same. New `CoverageRegression` (tesseraql-coverage-core) and `ReportRegression`
  (tesseraql-report). For a meaningful baseline, `history.json` must persist across runs (committed
  or CI-cached).

- Docs portal: API spec diff / changelog on the Export page (Studio backlog). When an OpenAPI
  **baseline** sidecar is present (`.tesseraql/docs/openapi.baseline.json` — copy a released
  `openapi.json` there), the Export page shows **what changed** in the API since that baseline:
  operations **added**, **removed**, or **changed**, and for a changed operation what about it changed
  (parameters added/removed/required/typed, request body, responses, security). A new canonical
  `OpenApiDiff` engine (`tesseraql-yaml`) diffs the current generated OpenAPI against the baseline by
  HTTP method and path (so a route re-ordering is not a change), deterministically; added/changed
  entries link to their route page. Off until a baseline is captured; a corrupt baseline degrades to
  a note. `DocService.apiChangelog`; `DocViews.export` gains the changelog projection.

- Docs portal: SQL&rarr;table dependency graph on the route page (Studio backlog, v3.1 deferred
  slice). A route's reference now shows the **tables its SQL reads from and writes to**, inferred
  from the bound 2-way SQL by a new dependency-free extractor (`SqlTableReferences` in
  `tesseraql-core`) — `FROM`/`JOIN`/`USING` are reads, `INSERT INTO`/`UPDATE`/`DELETE FROM`/
  `MERGE INTO` are writes — skipping comments, directives, string literals, CTE names, and
  derived-table subqueries. A read/write table that the `schema` goal introspected into the schema
  portal cross-links to its table page; an un-introspected one stays plain text. It is a best-effort
  navigation aid, not an execution fact, and is computed live from the spec (no `spec.json` change).
  `DocService.tableLinks`; `DocViews.route` gains the data-dependency projection. The schema **table**
  page now shows the reverse: a **Used by routes** card listing the routes whose SQL reads from and
  writes to that table, each linking back to its route reference — so the dependency graph is
  navigable both ways. Built once as a cached reverse index over every route's bound SQL
  (`DocService.routesForTable`); the public shared-table view deliberately omits it.

- MCP: application-declared prompts (`kind: prompt`). An app can now declare its own MCP **prompt**
  under `mcp/` — the application-side counterpart of the dev-tool `studio_copilot` prompt — as a
  parameterized message template the runtime serves at `/_tesseraql/mcp` alongside its tools,
  resources, and UI resources. A `kind: prompt` document declares its `input:` (the prompt's
  arguments) and a colocated `template` (rendered in Thymeleaf TEXT mode against the supplied
  argument values); `prompts/list` advertises it and `prompts/get` returns the rendered text as a
  `user` message. A prompt is pure text — no recipe, no SQL, no embedded LLM — so it carries no
  per-prompt security beyond the endpoint's own auth. New `PromptFile` + `AppManifest.prompts()`;
  `ManifestLoader` parses the new kind; `AppMcpServer` registers each prompt and renders its template.

- OpenAPI: structured JSON response schemas. The generated OpenAPI document (the `generate` goal /
  the docs portal export) now describes a JSON route's response **shape** instead of an opaque
  `{type: object}`: `OpenApiGenerator` mirrors the `response.json.body` template's object/array
  structure with property names, and types each leaf source expression by convention — `…rows` is a
  row array, a row count is an integer, and a `params.X` leaf takes the declared type of input `X`.
  Unclassifiable leaves stay an open schema, and the output remains deterministic (sorted property
  keys), so client generators and Swagger UI get a real response model.
- Docs portal: signed share links for schema tables and the coverage dashboard (Studio backlog F8
  slice 3, extended). The opt-in `tesseraql.docs.share.secret` sharing that route pages had now also
  covers a **schema table** page and the **coverage** dashboard: an authenticated user gets a Share
  card with a signed, expiring link that opens that one page **read-only without signing in**. The
  HMAC now binds a **per-kind label** (route / table / coverage) plus the page's identity and expiry,
  so a link of one kind can't be replayed as another. The public coverage view withholds the
  per-test failure detail; the public table view drops the bearer-gated navigation links. New public
  `auth: public` routes `/_tesseraql/docs/share/table` and `/.../share/coverage`; `ShareLinks`
  generalized to `mintTable`/`mintCoverage` (+ verify); `DocViews.shareTable`/`shareCoverage`.

- Studio editor: confirm-the-diff-before-every-apply (Studio backlog D5 follow-up). A new opt-in
  `tesseraql.studio.confirmApply` flag makes the editor acknowledge the compare-panel diff before
  **every** draft apply, not only when there's a concurrent-edit conflict. When on, the source page
  shows a `required` "I reviewed the diff" checkbox next to Apply, and the UI apply route rejects an
  unacknowledged apply (`STUDIO-4223 → 422`); a conflict's existing force checkbox counts as the
  acknowledgment. The gate is UI-only — the programmatic JSON and MCP apply paths are unaffected
  (they have no human diff to review). Runtime `StudioAccess.requireConfirm` / `confirmApply()`.

- Studio copilot: the MCP "describe → draft → preview → apply" loop (Studio backlog G). The
  protocol core (`tesseraql-mcp`) gains the third MCP primitive — **prompts** (`prompts/list` /
  `prompts/get`, advertised in `initialize` only when registered) via a new `McpPrompt` /
  `McpPromptResult` model — and the dev-tool MCP server (`tesseraql mcp`) offers a `studio_copilot`
  prompt (write mode only) that turns a plain-language `task` (and optional `table`) into guidance
  steering the connecting agent's model through the existing tools: orient (`manifest_summary` /
  `source_read`), draft (`scaffold_crud` / `draft_save`), verify (`draft_preview` / `lint` /
  `test`), then `draft_apply`. This is "describe" without an embedded model — TesseraQL ships the
  workflow, the agent's own model does the reasoning, and each step stays a separately-gated tool
  call, so the copilot adds no LLM dependency, API key, or new privilege (honoring the roadmap's
  decision point 4: the MCP loop, not an in-app model, is the AI surface).

- Docs portal: longer-term coverage trends (Studio backlog F9). The run-history ring that feeds the
  coverage dashboard's trend sparklines is no longer fixed at 20 runs — a non-positive
  `tesseraql.historyLimit` (Maven `report` goal) / `--history-limit` (`tesseraql test --report`) now
  keeps the **full history**, so the trend can span far more than the former cap. The trend panel
  shows its depth (the run count and the retained date span) instead of a hard-coded "last 20 runs"
  note. `ReportHistory.append` treats a non-positive cap as unbounded; `DocViews.trend` adds the
  date span.

- Docs portal: opt-in signed shareable links (Studio backlog F8, slice 3, completing F8). A route's
  documentation is bearer-only by default; when the operator configures a signing secret
  (`tesseraql.docs.share.secret`, with an optional `tesseraql.docs.share.ttl` lifetime, default 7
  days), an authenticated user gets a **Share** card on a route page with a signed, expiring link that
  opens that one route's **read-only contract** — method/path/recipe, inputs, security summary,
  validations, notifications, response shape — **without signing in**. The link carries an
  HMAC-SHA256 signature over the route id and expiry, so it cannot be retargeted or extended; the
  public `auth: public` share route verifies the signature (constant-time) and the expiry before
  rendering, and shows an "invalid or has expired" notice otherwise — nothing leaks. The public view
  deliberately omits the bound SQL, tests, and coverage (implementation internals), and the signing
  secret is dedicated (not the JWT key) so docs sharing and request authentication rotate
  independently. Sharing stays off until the secret is set. New runtime `ShareLinks`;
  `DocViews.share`; the `docs.share` provider and the `/_tesseraql/docs/share/route` route.
- Docs portal: printable route catalog (Studio backlog F8, slice 2). The Export page gains a
  **Printable route catalog** view that renders the app's routes (id, method, path, recipe, covering
  tests) to a PDF table through the **canonical PDF codec** — the same `FileCodecs.discover()` path
  the export routes use, via its built-in grid (no template) — shown inline in a preview frame with a
  `routes.pdf` download link. Studio stays free of the optional `tesseraql-pdf` stack: the runtime
  renders the PDF and the page degrades to a clear note when the module is absent (the editor's PDF
  preview pattern). `DocService.routeCatalog`; `DocViews.routesPdf`; the `docs.routesPdf` provider and
  the `/ui/docs/export/pdf` route (CSP allows the `data:` preview frame).
- Docs portal: export the API specs (Studio backlog F8, slice 1). The documentation portal gains an
  **Export** page (linked from the docs chrome) that serves the app's **OpenAPI 3** document and its
  **htmx interaction contract** as downloadable JSON, generated live from the route manifest by the
  same canonical generators the `generate` build goal uses — so the portal downloads are byte-identical
  to the build's `openapi.json` / `htmx-contract.json` artifacts (no reimplementation). The download
  endpoints (`/_tesseraql/studio/ui/docs/export/openapi`, `/.../export/htmx`) stream the spec with a
  `Content-Disposition` attachment via the standard `response.file` recipe and stay bearer-gated like
  the rest of the portal, so the URLs can be shared with API tooling that carries the same token.
- Studio editor: richer syntax tokens and a 2-way SQL live grammar (Studio backlog E, completing it;
  hc 0.1.5 / #264). The server-side read-only highlighters now emit hc 0.1.5's new semantic tokens —
  YAML mapping keys as `property`, HTML element names as `tag`, plain attributes as `attribute`
  (Thymeleaf/htmx/`data-` directives stay `meta`) — so the read-only/diff views read correctly and
  match the live overlay's built-in grammars. And a consumer `tql-sql` grammar (registered through
  hc's new `registerCodeLanguage`, mirroring the server `SqlHighlighter`) gives the editable SQL field
  live highlighting that classifies 2-way SQL block-comment directives (`/*%if … */`, binds) as
  `meta` — which a generic SQL grammar can't — so the editor matches the read-only view for 2-way SQL
  too. Editable `.sql` fields use `data-lang="tql-sql"`.
- Studio editor: live syntax highlighting of the editable field (Studio backlog E; adopts Hypermedia
  Components 0.1.5 / hc #264). The editable `hc-code` source and sample fields now opt into hc's
  `installCodeEditor` `data-lang` overlay — a synced, CSP-safe highlight layer behind the textarea
  that re-tokenizes as you type and reuses the same `hc-code__tok` palette as the read-only/diff
  views, so the editor matches them. The grammar is chosen by file type (`sql`/`yaml`/`html`/`json`
  built-in grammars); the bundled hc WebJar is bumped 0.1.4 → 0.1.5. Degrades cleanly to a plain
  textarea with no JS or an unknown type.

- Studio editor: PDF preview for export routes (Studio backlog A1 follow-up). A `query-export`
  `format: pdf` route now renders an actual PDF in the editor's rendered-preview panel — the route's
  print template is converted to PDF from the sample's `sql.rows` and shown in an `<iframe>` (a
  `data:` URL) with a download link, so print layout (`@page`, fonts, pagination) can be checked
  without running an export. It reuses the canonical PDF codec, so the preview matches a real export;
  Studio stays free of the heavy, optional `tesseraql-pdf` stack — the runtime supplies the renderer
  through a new `StudioService.PdfRender` callback (the A1 live-rows/`FieldMask` pattern) and degrades
  to a clear message when the `tesseraql-pdf` module is not on the classpath. The source/render CSP
  gains `data:` in `frame-src` for the embedded PDF.
- Studio editor: output-field masking in the JSON rendered preview (Studio backlog A1 follow-up). A
  `query-json` route's `response.json.fields` policy is now applied to the rendered preview, so the
  preview shows what a caller would actually see — fields hidden (`visible: false` / a `policy:` the
  viewer fails) or redacted (`mask`/`classification`) just as in production. It reuses the canonical
  `FieldPolicyApplier`, evaluated for the sample principal the developer can put under `principal`
  (e.g. `permissions`/`roles`) in the render sample, so a privileged view can be previewed too;
  policy-gated fields default to hidden for an anonymous sample. Studio stays free of the
  security/compiler stack — the runtime supplies the mask through a `StudioService.FieldMask`
  callback (the same pattern as the A1 live-rows `RowSource`).
- Studio editor: per-role edit permission (Studio backlog D6). The all-or-nothing
  `tesseraql.studio.readOnly` switch is refined by an optional `tesseraql.studio.editRoles`
  allow-list: when set (and Studio is writable), only a caller holding one of those roles may mutate
  (save/apply a draft, discard, create a route, apply a scaffold) — every mutating endpoint and UI
  action answers `403` for everyone else, and the explorer/source pages render the read-only view
  (no edit chrome) for them. With no allow-list, any authenticated caller may edit a writable Studio,
  as before. The decision is per-caller from `principal.roles` (a new runtime `StudioAccess` gate),
  with the database-free `StudioService` still enforcing the master read-only switch as defense in
  depth.
- Studio editor: audit trail (Studio backlog D6). Every source-writing action — applying a draft and
  applying a scaffold — is now stamped to an append-only `work/studio/audit/audit.jsonl` log with
  **who** (the authenticated caller's login id), **what** (apply / scaffold), the **target** (the
  applied path or scaffolded table), and **when**. A new **audit** page (linked from the explorer when
  Studio is writable) lists the trail newest-first, with applied paths linking back to their editor.
  Database-free `StudioService.applyDraft(path, force, actor)` / `scaffoldApply(table, force, actor)`
  record the entry at the single point each write happens (so no caller path can bypass it) and
  `auditEntries(limit)` reads it; the `studio.audit` provider and `GET /_tesseraql/studio/audit`
  endpoint expose it. The Studio routes bind the actor from `principal.loginId`.
- Studio editor: pending-draft overview (Studio backlog D5). A new **drafts** page (linked from the
  explorer when Studio is writable) lists every unsaved draft under `work/studio/drafts` with a link
  to its editor, whether it is a new file or an edit, and whether it conflicts with a source that
  changed underneath it — so edits in flight, and any that need attention, are visible in one place.
  Database-free `StudioService.drafts()`; the `studio.drafts` provider and `GET
  /_tesseraql/studio/drafts` endpoint.
- Studio editor: concurrent-edit conflict detection (Studio backlog D5). Saving a draft now records
  the source it is based on, so applying detects when the source changed underneath it and refuses to
  silently overwrite the other change (no more last-apply-wins): the editor shows a conflict warning
  and requires an explicit **overwrite** confirmation, and the apply endpoint answers `409 Conflict`
  unless `force` is set. Database-free `StudioService.draftConflicts` + `applyDraft(path, force)` (the
  base is a sidecar beside the draft); the `studio.apply` provider and `POST
  /_tesseraql/studio/apply` endpoint take `force`; the source page carries the warning and a
  review-gated force checkbox.
- Studio editor: directory tree and filter in the explorer (Studio backlog C4). The flat route/job
  tables become a single **directory tree** folded from the source paths (folders as nested
  disclosures, each route/job a leaf linking to its source, with a method/`job` badge), and a
  **filter** box narrows it live as you type — a case-insensitive match over each entry's id, source
  path, recipe, and (for a route) HTTP method and URL path, which prunes the tree to matching
  branches. The filter re-renders server-side via htmx (`hx-get` the explorer with `hx-select` on the
  tree), so it needs no bespoke client JS. Database-free `StudioService.explorer(query)`; the tree is
  built in `StudioViews`; the `studio.explorer` provider and `GET /_tesseraql/studio/explorer?q=…`
  endpoint take the query.
- Studio editor: scaffold a table's CRUD slice from the explorer (Studio backlog B3). A new
  **scaffold** page lists the dev datasource's tables (introspected live with the same
  `CatalogIntrospector` the documentation portal's schema view uses); choosing a table **previews**
  the full CRUD slice the generator would produce — list, detail, and edit pages, 2-way SQL, and a
  declarative test suite — reusing the very `TableIntrospector` + `CrudScaffolder` the CLI `scaffold
  crud` command does, so the generated files are byte-identical to the command line. Each previewed
  file is shown syntax-highlighted with the disposition an apply would give it
  (`new`/`unchanged`/`regenerate`/`conflict`). **Create these files** then writes the slice into the
  app home through the scaffold's edit-detection contract (a pristine generated file is regenerated, a
  file you edited or own is skipped and reported unless you force it), and reports which newly written
  routes need a restart to be served (the hot reloader only swaps existing routes). Gated: available
  only when Studio is writable and `tesseraql.studio.scaffold.enabled` is set. Studio stays
  database-free — a new runtime `StudioScaffoldService` owns the introspection and hands the
  `TableSchema` to the database-free `StudioService.scaffoldPreview`/`scaffoldApply`. Backed by the
  `studio.scaffold.tables`/`studio.scaffold.preview`/`studio.scaffold.apply` providers, the `GET
  /_tesseraql/studio/scaffold/tables` + `POST /_tesseraql/studio/scaffold/preview` + `POST
  /_tesseraql/studio/scaffold/apply` JSON endpoints, and the `/_tesseraql/studio/ui/scaffold` page.
  Ties to milestone M7 ("schema → verified CRUD in ten minutes"). See
  [docs/studio-backlog.md](docs/studio-backlog.md).
- Studio editor: create a new route from the explorer (Studio backlog B3). A **New route** form on
  the explorer (when Studio is writable) takes a `web/**/<method>.yml` path and a recipe
  (`query-json`/`query-html`/`command-json`) and saves a parseable starter skeleton as a draft, then
  opens it in the source editor to finish — so creation reuses the existing validate → apply flow
  (the new route needs a restart to be served). Database-free `StudioService.newRouteDraft`; the
  `studio.newRoute` provider and the `/_tesseraql/studio/ui/new` route.
- Studio editor: run a route's or job's declarative tests from the editor (Studio backlog A2). A
  route or job source page gains a **Run tests** action that runs every declarative test case kind
  covering it — `sql` queries **and writes** (an `INSERT … RETURNING` runs and is rolled back),
  `validate` rules (their SQL runs against the sandbox), `contract` cases (through a sandboxed
  identity service built over the same datasources), and the pure, no-DB `notify` and `http-call`
  evaluations — against the dev datasource and shows inline pass/fail with the failure message — no
  edit → apply → restart → CI loop. Gated and sandboxed: enabled only when
  Studio is writable and `tesseraql.studio.testRunner.enabled` is set; every case runs through a
  `SandboxDataSource` — an auto-rollback transaction (commits suppressed, rolled back on close) with
  a statement timeout (`tesseraql.studio.testRunner.queryTimeoutSeconds`, default 5) and a row cap
  (`tesseraql.studio.testRunner.maxRows`, default 1000) — so a case can neither run away nor persist
  a write. Backed by a new
  `StudioTestService` + `SandboxDataSource` reusing the declarative `TestRunner`, the
  `studio.runTests` provider, the `POST /_tesseraql/studio/runTests` JSON endpoint, and the
  `/_tesseraql/studio/ui/run-tests` editor fragment. See
  [docs/studio-backlog.md](docs/studio-backlog.md).
- Studio editor: live data in the rendered preview (Studio backlog A1 "real bound params" × the A2
  sandbox). The route render panel gains a **Use live data** toggle (when the test runner is
  enabled): instead of a hand-authored `sql.rows` fixture, the preview runs the route's main `sql`
  query through the same `SandboxDataSource` (bind params resolved from the sample's `params`/
  `query`) and injects the real `rows`/`rowCount` — so editing a route previews the actual page/JSON
  over live dev data. Studio stays database-free via a `StudioService.RowSource` callback the runtime
  fills with `StudioTestService.liveRows`; `live` flows through the `studio.render` provider, `POST
  /_tesseraql/studio/render`, and the `/_tesseraql/studio/ui/render` fragment.

- Studio editor: rendered preview against sample data (Studio backlog A1). A renderable file
  opened in the editor now renders against a sample model — not just the empty-model "parses" check
  `preview()` gave — and shows the actual output two ways: the generated HTML/text/JSON, hc-code
  syntax-highlighted, and (for HTML) a sandboxed `iframe` visual preview styled with the Hypermedia
  Components stylesheet. Two shapes render: a **template file** (`.html`/`.tpl`) against the sample
  as its template variables, and a **web route** (`web/**/<method>.yml`) against the sample as the
  execution context (`params`, `sql.rows`, …) — a `query-html`/`page` route resolves
  `response.html.model` and renders the route's template; a `query-json` route resolves
  `response.json.body` and pretty-prints it (output-field masking `response.json.fields` is not
  applied in preview). The sample is a YAML/JSON map typed in the editor and prefilled from a
  colocated `<name>.sample.yml` fixture when present (a blank sample falls back to that fixture).
  Backed by `StudioService.render`/`sampleModel`, the `studio.render` provider, the
  `POST /_tesseraql/studio/render` JSON endpoint, and the `/_tesseraql/studio/ui/render` editor
  fragment; the source page CSP gains `frame-src 'self'` to admit the sandboxed preview frame. The
  `.sample.yml` fixture lives beside its file and is ignored by the route loader (only HTTP-method
  `*.yml` files under `web/` are routes). See [docs/studio-backlog.md](docs/studio-backlog.md).
- `tesseraql serve --embedded-db [<data-dir>]` runs a tqlapp with no external database: the CLI
  starts an embedded PostgreSQL and points the runtime's `main` datasource at it. With no directory
  the data is ephemeral; with one it persists across restarts (a single-server option). Because it
  is a real `postgres`, the framework migrations and `ensureSchema` bootstrap run unchanged — no
  new dialect. The platform binary is resolved on demand through the same embedded resolver as
  `tesseraql.modules` (pinned via `zonky.postgres.binaries.version`), so the fat jar is not bloated.
  See [docs/getting-started.md](docs/getting-started.md).

### Changed

- Studio: **the route catalog is a sortable `hc-datagrid`** (platform-UX track I). The docs route
  table sorts by any column (id / method / path / recipe / tests / coverage) — server-driven: each
  header is a link that re-requests sorted, the server sets `aria-sort` on the active column, and the
  kit renders the sort arrow from it. No JS and no `hx-vals='js:'` — it works under the strict CSP
  because the arrow is pure CSS keyed off `aria-sort` and the datagrid's click handler does not
  `preventDefault` a header link. `DocViews.index(…, sort, dir)` does the sort.
- Studio: **the schema table list is a sortable `hc-datagrid`** (platform-UX track I) — each
  datasource's tables sort by name / type / columns / FKs, with the same CSP-clean server-driven
  pattern as the route catalog (`DocViews.schema(…, sort, dir)`).
- Studio: **the audit trail is sortable** (platform-UX track I), composing the new column sort with
  the existing filter and pagination: a sort header link carries the filter `q` and resets the page,
  a page link carries `q` + the sort, and the filter input keeps the sort across an htmx re-filter
  via a static-JSON `hx-vals` (CSP-clean). The whole filtered log is sorted before paging
  (`StudioService.auditPage(query, sort, dir, page, size)`).
- Studio: **the audit trail is paginated with `hc-pagination`** (platform-UX track I). H5 capped the
  page at the newest 200 entries; the whole log is now navigable in 50-entry pages (newest first) via
  an `hc-pagination` nav of plain styled links (no JS, CSP-clean). The H5 filter still searches the
  whole log and composes with paging. `StudioService.auditPage(query, page, size)` returns the page
  slice plus the filtered total.
- Studio: **the route reference's test-result detail uses `hc-tooltip`** instead of a `title=`
  tooltip (platform-UX track I). The pass/fail badge carried its failure message in `title=`, which
  screen-reader and keyboard users can't reach; it now references a sibling `.hc-tooltip` via
  `aria-describedby` (shown on hover + keyboard focus, dismissible with Escape).
- Studio: **adopt the kit's `hc-spinner` and `hc-breadcrumb`** instead of the hand-rolled equivalents
  (platform-UX track I). The shared loading affordance (`tql/shell :: busy`) now renders the CSS-only,
  reduced-motion-aware `hc-spinner` with a contextual label rather than a bare "Working…" text fade,
  and the route/table breadcrumbs use the semantic `hc-breadcrumb` (CSS-injected separators) instead
  of a hand-built cluster with a literal `›`. Both components already ship in Hypermedia Components
  0.1.5 — they were missed earlier because they are CSS-only (absent from the behaviors bundle), so
  no version bump is needed.
- Upgraded Testcontainers 1.20.4 → 2.0.5 (docker-java 3.4.0 → 3.7.1). docker-java 3.4.0 could not
  validate Docker Engine 29's raised API floor (`MinAPIVersion` 1.40), so every Testcontainers IT
  failed with "Could not find a valid Docker environment" on hosts running Docker 29 (e.g. the dev
  container); docker-java 3.7.1 negotiates correctly. The 2.0 module artifacts gained a
  `testcontainers-` prefix and the JDBC container classes moved to per-database packages and dropped
  their self-type generics, so the test deps and imports were migrated accordingly
  (`org.testcontainers.containers.PostgreSQLContainer<>` → `org.testcontainers.postgresql.PostgreSQLContainer`,
  and likewise for MySQL/SQL Server). Test-only change.

## 0.2.0 - 2026-06-16

### Distribution and onboarding

Building an application on TesseraQL no longer requires cloning the framework monorepo
(see [docs/app-developer-distribution.md](docs/app-developer-distribution.md) and
[docs/getting-started.md](docs/getting-started.md)):

- Shared `tesseraql-apptasks` library (`AppPackager`/`AppMigrator`/`IdentityBootstrap`) so the
  Maven plugin and the CLI are thin adapters over one engine.
- CLI command parity with the Maven goals: `lint`, `test` (`--report`), `coverage`, `generate`,
  `schema`, `governance`, `migrate` (apply/info/validate/repair), `identity-schema`, `package`,
  `verify`, plus `modules` (the opt-in driver/codec resolver).
- Embedded module resolver: `tesseraql.modules` plus a committed `modules.lock` (declarative,
  reproducible), resolved via an embedded Maven resolver that honors `~/.m2/settings.xml`.
- Artifacts published to GitHub Packages on release; the BOM version-manages the opt-in JDBC
  drivers (`ojdbc11`, `mssql-jdbc`, `mysql-connector-j`).
- Installable CLI distribution: a self-contained fat jar with `bin/tesseraql`(`.cmd`) launchers
  (`-Pdist`) and per-OS jpackage app images (Linux x64, Windows x64, macOS arm64).
- Scaffold (`tesseraql new`) emits a wrapper POM + the Maven Wrapper, `compose.yaml`, Studio
  config, and a README.
- Proxy / restricted-network support: outbound `HttpClient`s honor the JVM proxy; the CLI bridges
  `HTTP_PROXY`/`HTTPS_PROXY`/`NO_PROXY`; internal mirrors and TLS-intercepting proxies are
  documented in [docs/proxy.md](docs/proxy.md).
- The framework version is single-sourced (`io.tesseraql.core.TesseraqlVersion`).

### Core

- 2-way SQL `%for` directive: an optional `separator ','` (kept inside the directive comment,
  so multi-row `INSERT ... VALUES` templates stay SQL-tool-runnable) and a 0-based
  `<item>_index` loop variable.
- Dialect capability matrix: generated-key retrieval style (`columns` for PostgreSQL/Oracle,
  `auto` for MySQL/SQL Server).
- `TqlException` carries an explicit client-safe `details` payload, rendered into error
  responses (field-level errors, conflict hints) without leaking internals.
- Durable object storage SPI (roadmap Phase 30 slice 1, see [docs/attachments.md](docs/attachments.md)):
  a `BlobStore` (`io.tesseraql.core.blob`) — a sibling of the ephemeral `TempStore` for retained,
  retention-governed objects — with a `FileBlobStore` default that streams to local disk and computes
  a SHA-256 checksum and byte count while writing (no second pass). The surface is the minimal
  portable intersection of S3-compatible stores (put/get/exists/delete plus an optional pre-signed
  GET), so an S3 implementation plugs in unchanged in slice 2. Attachment metadata has its own SPIs,
  `AttachmentStore` and `AttachmentService`, in the same SQL-first managed/app spirit as the workflow
  and org-unit stores.

### Runtime and recipes

- Attachments and object storage — scanning and retention (roadmap Phase 30 slice 3, completing the
  phase, see [docs/attachments.md](docs/attachments.md)): a malware scan-hook SPI and age-based
  retention. `AttachmentScanner` (`tesseraql-core` `io.tesseraql.core.scan`, ServiceLoader-discovered
  via `AttachmentScanners.discover()`, no-op default) is the seam for ClamAV or a cloud scanner — an
  app enables real scanning by adding a scanner module, no config flag. Scanning is synchronous on
  upload: the verdict is recorded as `scan_status`, an `INFECTED` object is never served (the
  download gate refuses any non-clean object with `409`, `TQL-LD-2848`) and is kept or removed per
  `tesseraql.attachments.scan.onInfected` (`quarantine` default / `delete`), and a scanner `ERROR`
  fails the upload closed (`503`, `TQL-LD-2847`). Retention wires into the ch. 44 `RetentionSweeper`:
  when `tesseraql.retention.attachments` is set and the managed store is bound, the cluster-safe
  sweep also deletes attachment metadata past the window and reclaims each blob (best-effort, so a
  racing node or an already-removed blob is harmless). Orphan GC (a blob with no metadata row) is a
  later refinement — it needs a `BlobStore` listing capability the minimal SPI does not yet expose;
  the upload path's best-effort delete-on-failure covers the common case. This completes the
  attachments leg of Milestone M9.
- Attachments and object storage — S3 and S3-compatible storage (roadmap Phase 30 slice 2, see
  [docs/attachments.md](docs/attachments.md)): a new opt-in `tesseraql-s3` leaf module ships an
  `S3BlobStore` on AWS SDK for Java v2 (Apache-2.0, confined to the module), contributed by a
  `BlobStoreProvider` discovered via `ServiceLoader` (the PdfEngine idiom) and selected by
  `tesseraql.object-storage.provider: s3` — so an app moves blobs from local disk to S3 by config
  alone, no DSL change. One module covers AWS S3 and every compatible store (Cloudflare R2, Ceph,
  Backblaze B2) via `endpoint`/`region`/`pathStyle`/`checksumMode` (`when-required` restores
  compatibility with stores that reject the SDK's default request checksums). Egress is
  deny-by-default: a bucket outside `tesseraql.object-storage.allowedBuckets` is refused at runtime
  and flagged by lint `TQL-SEC-4110`; credentials resolve lazily through the SecretResolvers
  (`${secret.*}`), never logged. Uploads buffer off-heap to a temp file (SHA-256 computed while
  writing) then stream to S3 in one `putObject`; `presignGet` issues a short-lived URL. Verified
  against Adobe S3Mock over Testcontainers (MinIO is not used — its server is AGPLv3; roadmap
  decision point 6). The scan-hook and retention remain slice 3.
- Attachments and object storage — attachment core (roadmap Phase 30 slice 1, see
  [docs/attachments.md](docs/attachments.md)): a `kind: attachment` document under `attachments/`
  binds uploaded files to an owning business record and synthesizes three HTTP routes — an off-heap
  multipart upload `POST {basePath}`, a metadata list `GET {basePath}`, and a download
  `GET {basePath}/{attachmentId}`. The upload streams the body off-heap into the `BlobStore` (its
  size and SHA-256 computed while streaming), enforces the declared `limits` (size → `413`, content
  type → `415`), then records a row in the managed `tql_attachment` table; the download loads that row
  scoped to the owning record (an attachment owned by a different record reads as `404`, never leaked)
  and streams the blob with a sanitized `Content-Disposition`. The blob write is non-transactional —
  an upload that fails after the blob is written leaves an orphan the retention sweep reclaims
  (slice 3). Metadata uses the managed/app duality (`tesseraql.attachments.mode`, default `managed`);
  the managed store and the file blob store are provisioned only when the app declares attachments.
  Lint (`TQL-ATTACH-3401..3405`) checks the document's kind, base path, owning record, path-parameter
  binding, and size limit. S3-compatible storage, app-mode 2-way SQL metadata, the scan-hook, and
  retention are later slices.
- Approval workflow — `onBreach.escalate` auto-transition (roadmap Phase 28, see
  [docs/approval-workflow.md](docs/approval-workflow.md)): on a deadline breach the cluster-safe
  sweeper can now auto-fire a named transition **as the system** instead of reassigning — it advances
  the document from the deadline's state, runs the transition's command (with `/* key */` and
  `/* audit.* */` binds, so `audit.user` is `system`), completes the open tasks (so it cannot
  re-fire), and records a history row under the transition id. Works in both managed and app state
  modes; `escalate` takes precedence over `reassign` when both are declared; lint `TQL-WORKFLOW-3107`
  checks the named transition starts from the deadline's state. This completes the phase.
- Approval workflow — reminder notifications (roadmap Phase 28, Phase 20 channels, see
  [docs/approval-workflow.md](docs/approval-workflow.md)): a workflow declares a `notify:` block whose
  `assigned` reminder fires when a transition opens a task and whose `escalated` reminder fires when
  the sweeper reassigns an overdue one. Each is a `NotifySpec` (channel, optional `when` guard,
  `payload` with the resolved `assignee` in scope) enqueued as a `NOTIFICATION` outbox event in the
  same transaction as the task change — so a rolled-back transition never notifies and a committed one
  notifies at-least-once, with the same delivery, retries, and dead-lettering as a route's `notify:`.
- Approval workflow — deadlines, escalation, and delegation (roadmap Phase 28 slice 3, completing the
  phase, see [docs/approval-workflow.md](docs/approval-workflow.md)): a state's `deadlines` set the
  opened task's `due_at`; a cluster-safe sweeper (a timer claimed through `tql_job_claim` at
  `tesseraql.workflow.sweep.interval`, default 60s) reassigns each overdue task to the fallback
  resolver named by `onBreach.reassign` (a 2-way SQL `SELECT` returning the new assignee), clearing
  `due_at` so it escalates exactly once even across nodes and recording an `escalate` history row.
  Delegation is a built-in `POST {basePath}/{key}/delegate/{to}` that reassigns the document's open
  task to a chosen delegate (only a current holder may delegate, else `TQL-WORKFLOW-3203`/403). The
  `onBreach.escalate` auto-transition and Phase 20 reminder notifications remain a refinement.
- Approval workflow — assignee resolution and task inbox (roadmap Phase 28 slice 2, see
  [docs/approval-workflow.md](docs/approval-workflow.md)): a transition's `assign` contract — a 2-way
  SQL `SELECT` returning `assignee`/`candidate_group` rows (consuming the Phase 29 org-unit
  foundation unchanged) — opens a task in the managed `tql_workflow_task` inbox for the resulting
  state, completing the prior state's tasks in the same transaction. Authority is framework-enforced:
  a document with open tasks may only be transitioned by a caller who holds one (the direct assignee
  or a candidate group), else `TQL-WORKFLOW-3203` (403) — the dual of a scope over the task table,
  which an app may still author for its inbox query. The `WorkflowTaskStore`/`JdbcWorkflowTaskStore`
  is provisioned whenever any transition assigns, independent of the state mode (managed instance row
  or app column), so one inbox spans every workflow. Deadlines, escalation, and delegation are
  slice 3.
- Approval workflow — workflow core (roadmap Phase 28 slice 1, see
  [docs/approval-workflow.md](docs/approval-workflow.md)): a SQL-contract state machine driving a
  business document through declared states by transitions, with the IAM managed/SQL realm duality.
  A `kind: workflow` document under `workflow/` declares the `document`, `states` (one `initial`,
  zero or more `terminal`), and `transitions` (`from`/`to`, an optional whitelist-only `guard` over
  `document.*`/`principal.*`, a 2-way SQL `command`); the compiler synthesizes one
  transactional-command route per transition (`POST {basePath}/{key}/{transitionId}`). A transition
  reuses the Phase 18 command engine to, in one transaction, load the document, check the current
  state allows the transition, evaluate the guard, advance the state, run the command, and append an
  immutable history row — so a rejected transition rolls back entirely. `tesseraql.workflow.mode:
  managed` provisions `tql_workflow_instance` / `tql_workflow_history` behind a `WorkflowStore` SPI
  (`tesseraql-core`) with a `JdbcWorkflowStore` impl; `mode: app` (the default) keeps state in the
  business table's `stateColumn` and provisions nothing (per-workflow override allowed). An illegal
  or concurrent transition is `TQL-WORKFLOW-3201` (409), a falsy guard `TQL-WORKFLOW-3202` (422).
  Lint (`TQL-WORKFLOW-31xx`: undeclared/unreachable states, bad guards, missing files, mode
  mismatch) and a `workflow` coverage kind (one item per transition) keep it machine-checkable. The
  task inbox, assignee resolution, deadlines, and escalation are later slices.
- Organizational data scoping — row-level masking (roadmap Phase 29 slice 3, completing the phase,
  see [docs/data-scoping.md](docs/data-scoping.md)): a field is masked in the rows outside the
  caller's scope. The query selects the scope predicate as a per-row flag with the new
  `/*%scope <name> on <alias> as boolean */ (1=1)` directive — rendered as a portable
  `case when … then 1 else 0 end` — and a response `fields` policy keys off it with
  `unmaskWhen: <flag column>`, masking the field when the flag is falsy and stripping the flag column
  from the response. No per-row predicate evaluation in Java. Column-level role masking
  (`FieldPolicy.policy`) is unchanged.
- Organizational data scoping — shared org-unit foundation (roadmap Phase 29 slice 2, see
  [docs/data-scoping.md](docs/data-scoping.md)): a managed org-unit hierarchy that subtree scopes
  (and, later, Phase 28 approval-workflow assignee resolution) build on — one org graph, the IAM
  managed/SQL realm duality. `tesseraql.orgunit.mode: managed` provisions `tql_org_unit` (units +
  `parent_id`) and `tql_org_closure` (the transitive closure, depth 0 = the unit itself); the
  `OrgUnitStore` SPI (`tesseraql-core`) and `JdbcOrgUnitStore` impl maintain it — `upsert`/`delete`
  units then `rebuildClosure()` recomputes the closure from the parent graph in Java, so it is
  dialect-agnostic (no recursive CTE) and a subtree scope stays a plain, portable
  `owner_unit in (select descendant_id from tql_org_closure where ancestor_id in /* my_units */ (…))`.
  `mode: app` (the default) provisions nothing — the app owns its org tables and writes the fragment
  against them. `OrgUnitStore.descendants(...)` is the Java seam Phase 28 reuses. Lint
  (`TQL-SCOPE-3020`) validates the mode.
- Organizational data scoping — scope core (roadmap Phase 29 slice 1, see
  [docs/data-scoping.md](docs/data-scoping.md)): named, reusable row-level predicates derived from
  the request principal, the row-level complement to multi-tenancy. A `kind: scope` document under
  `scope/` declares an ordered list of **match arms** — each a `Policy`-style role/permission/claim
  `when` paired with an effect (`apply: all`, `apply: none`, or a 2-way SQL predicate `file`).
  Multiple matching arms compose **additively (OR)**; matching none is deny-by-default (`1=0`). A
  query opts in with a new 2-way SQL directive, `/*%scope <name> on <alias> */ (1=1)` (sibling to
  `/*%if … */`, in `tesseraql-core`), whose parenthesized dummy keeps the template runnable in a SQL
  tool; at execution a `ScopeResolver` replaces it with the principal-derived predicate,
  parameterized — never by rewriting `WHERE`/`FROM`. Fragments are alias-parameterized with a `$`
  sentinel (`$.region`) the call site qualifies via `on <alias>`, and a scope needing a join is a
  correlated `EXISTS`. The resolver is bound only when an app declares scopes; a directive rendered
  without one fails closed (`TQL-SQL-2106`). Lint (`TQL-SCOPE-3011..3013`) and a `data-scope`
  coverage kind keep it machine-checkable. The shared org-unit foundation and masking integration
  are later slices.
- Messaging and events — Postgres-native event channel (roadmap Phase 27, see
  [docs/messaging.md](docs/messaging.md)): a broker-free publish/subscribe transport built on a
  durable table plus PostgreSQL `LISTEN`/`NOTIFY` — no Kafka, no JMS. A command's `publish:` block
  emits a domain event on the transactional outbox (so a rolled-back command never publishes); a
  relay moves committed `EVENT` events onto a durable `tql_event` log and issues a `NOTIFY`; and a
  `queue-consume` route under `consume/` claims messages with `FOR UPDATE SKIP LOCKED` — woken the
  instant an event is published, swept by a polling backstop — and runs its SQL pipeline,
  deduplicated by an idempotency key in `tql_queue_consumed` so at-least-once delivery is effectively
  exactly-once per business key. `NOTIFY` is only the low-latency signal; the durable table is what
  makes delivery survive, so the `pg-notify` transport runs on a PostgreSQL main datasource, and the
  `OutboxEventSink` relay plus the `publish:`/`consume:` YAML are the seam a later Kafka/JMS leaf
  module plugs into unchanged. Channels are configured centrally
  (`tesseraql.messaging.channels.<name>`), lint covers them (`TQL-SEC-4090..4091`,
  `TQL-YAML-1009..1010`, `TQL-YAML-1106`), and a `queue-consume` coverage kind tracks the consumers
  declarative suites exercise. A second built-in transport, `db-poll`, makes the channel **portable
  across every dialect** (MySQL, SQL Server, Oracle, and PostgreSQL behind a transaction-pooling
  proxy that breaks `LISTEN`): the same durable `tql_event` queue, claimed with each dialect's
  `SKIP LOCKED` equivalent (PostgreSQL/MySQL `LIMIT … FOR UPDATE SKIP LOCKED`, Oracle `ROWNUM`,
  SQL Server `TOP … WITH (UPDLOCK, READPAST)`, mirroring the outbox dispatcher), polled on the
  `backstop` interval instead of woken by `NOTIFY`. Same at-least-once, idempotent delivery — only
  the latency differs; switching `transport:` is the whole change.
- Managed connectors — inbound webhook recipe (roadmap Phase 26, see
  [docs/connectors.md](docs/connectors.md)): a `webhook` route is an HMAC-verified,
  replay-protected POST endpoint in front of a SQL pipeline. The recipe authenticates the signed
  delivery (HMAC over `<timestamp>.<body>`, the scheme the Phase 20 outbound webhook signs with),
  rejects a stale/future timestamp outside the configured tolerance, and rejects a replay — all
  before request binding, so an invalid delivery never writes a row. The verifier is configured
  centrally (`tesseraql.connectors.webhooks.<name>`: secret resolved lazily through the
  SecretResolver SPI, header names, an optional delivery-id header for the replay key, and the
  tolerance), so the route carries no secret; the named verifier must be configured (an unknown
  provider fails the build, since a webhook without a verifier would be unauthenticated). Replay
  protection is a shared JDBC store (`tql_webhook_seen`, the same basis as SAML assertion replay),
  so a delivery is processed at most once on any node sharing the database. A bad signature or
  stale timestamp maps to 401, a replay to 409. Lint (`TQL-SEC-4082..4083`, `TQL-YAML-1008`) and a
  `webhook` coverage kind keep it machine-checkable. `RouteDefinition` gains a `webhook:` block;
  the runtime binds the `WebhookReplayStore`. **Phase 26 (managed connectors) is complete.**
- Managed connectors — polling file triggers (roadmap Phase 26, see
  [docs/connectors.md](docs/connectors.md)): a `file-import` job can be driven by a `poll:`
  trigger instead of an HTTP upload — the runtime watches a local directory or a remote
  SFTP/FTPS server and feeds every file it finds through the job's `import:` pipeline (the same
  per-row 2-way SQL a `file-import` route applies), ingesting each file through the existing
  asynchronous, off-heap, operations-tracked transfer path and moving it to a done/failed
  sub-directory. Reaching a remote host is **deny by default** (`tesseraql.connectors.poll.allowedHosts`,
  exact or `*.wildcard`); credentials come from `tesseraql.connectors.poll.credentials`, resolved
  through the SecretResolver SPI when the consumer starts. The underlying Camel `file`/`sftp`/`ftps`
  consumer stays an implementation detail, not user API. Lint catches a misconfigured poll job
  (`TQL-SEC-4080` off-allow-list host, `TQL-SEC-4081` undeclared credential, `TQL-YAML-1005`
  invalid source, `TQL-YAML-1006` missing import block), a job that targets a non-allow-listed host
  is skipped at startup rather than failing the runtime, and a new `file-poll` coverage kind tracks
  the poll jobs declarative suites exercise. `TriggerSpec` gains a `poll` member beside `schedule`,
  and `JobDefinition` an `import:` block. Adds `camel-file`/`camel-ftp`.
- Managed connectors — outbound HTTP (roadmap Phase 26, see
  [docs/connectors.md](docs/connectors.md)): an `http-call` batch-pipeline step issues one
  synchronous outbound REST request and publishes the response to later steps
  (`step.<id>.status` / `.body` parsed JSON or text / `.headers`), so a job can fetch from an
  API and persist the result, or push database rows to a partner system. It is a job step,
  never a transactional `command-json` step — a synchronous call cannot be rolled back, so a
  command's outbound integration rides the Phase 20 outbox webhook instead. All outbound HTTP
  is governed by `tesseraql.http.outbound`: egress is **deny by default** (a call may only
  target a host in `allowedHosts`, exact or `*.wildcard`), credentials (`bearer`/`basic`/`header`)
  resolve from the SecretResolver SPI at call time so a step never carries a secret, timeouts
  come from config with per-step overrides, and a per-host circuit breaker trips on consecutive
  systemic failures (transport errors and `5xx`) and fails fast for a cooldown. Each call is a
  `tesseraql.http.call` trace span. Lint catches misconfigured egress before it ships
  (`TQL-SEC-4070` off-allow-list host, `TQL-SEC-4071` no absolute url, `TQL-SEC-4072` undeclared
  credential), and a new `http-call` coverage kind tracks the steps declarative suites plan
  (resolving url, query bindings, and the allow-list without a network call). Camel's component
  catalog stays an implementation detail, not user API. `PipelineStep` gains an `http-call`
  member beside `sql` and `notify`.
- Application-declared MCP endpoints (roadmap Phase 24 follow-on, see
  [docs/ai-mcp.md](docs/ai-mcp.md)): an app declares Model Context Protocol tools under
  `mcp/` — a `query-json` or `command-json` definition (with a `description`) exposed over MCP
  instead of HTTP — and the runtime serves them over the Streamable HTTP transport at
  `/_tesseraql/mcp`, so the running business application is AI-enabled. Each tool compiles to an
  internal route running the full pipeline (the tool's own authentication and authorization,
  input validation, 2-way SQL or the transactional command), and the MCP endpoint dispatches a
  `tools/call` to it carrying the request's bearer token — so a tool is secured exactly like a
  route (per-tool `auth`/`policy`; discovery is open; an unauthorized call returns an MCP tool
  error). The advertised input schema is derived from the route's `input:` constraints. The
  governance gate scores and gates tools like routes (a write tool reachable without
  authentication is `advanced`); lint requires a write tool to declare a policy
  (`TQL-MCP-4030`, deny-by-default) and flags unknown recipes / missing descriptions; and a new
  `mcp` coverage kind tracks tools exercised by declarative suites. Disable with
  `tesseraql.mcp.enabled: false`. The transport-agnostic protocol core lives in `tesseraql-mcp`
  (added for the Phase 24 dev-tool server); the runtime reuses its `McpHttpHandler` from a Camel
  route. `AppManifest` gains `tools()` (discovered from `mcp/`).
- Application-declared MCP resources (roadmap Phase 24, see
  [docs/ai-mcp.md](docs/ai-mcp.md)): alongside its tools an app declares read-only Model Context
  Protocol *resources* — context an agent attaches — as a `kind: resource` document under `mcp/`.
  A resource is a `query-json` definition addressed by a stable `uri` (no arguments: its uri is
  the whole address) with an optional `mimeType` (default `application/json`); the compiler builds
  it into a read-only internal route running the full read pipeline (the resource's own
  `auth`/`policy`, tenancy and locale resolution, 2-way SQL), and the runtime answers
  `resources/list` / `resources/read` from it over the same `/_tesseraql/mcp` endpoint, carrying
  the request's bearer token — so a resource is secured exactly like a read route (discovery is
  open; an unauthorized read returns a `resources/read` JSON-RPC error). Lint keeps resources
  read-only and uri-addressed (`TQL-MCP-1003`/`1004`/`1006`, duplicate-uri `TQL-MCP-1007`, missing
  description `TQL-MCP-1005`); the governance gate scores a resource like a read route (never
  `advanced`); and an `mcp-resource` coverage kind tracks resources exercised by declarative
  suites. The protocol core (`tesseraql-mcp`) gains `McpResource` and the `resources/*` methods,
  advertising the resources capability only when some are registered. `AppManifest` gains
  `resources()` (discovered from `mcp/` by `kind`).
- Application-declared MCP Apps UI (roadmap Phase 24, see [docs/ai-mcp.md](docs/ai-mcp.md)): a tool
  can hand back interactive UI instead of only JSON — the [MCP Apps
  extension](https://modelcontextprotocol.io/community/seps/1865-mcp-apps-interactive-user-interfaces-for-mcp)
  (SEP-1865). An app declares a UI resource as a `kind: ui` document under `mcp/` — a `query-html` /
  `page` definition addressed by a stable `ui://` uri, with optional `ui:` rendering hints
  (`prefersBorder`, content-security-policy domains) — and a `kind: tool` document links to one via a
  `ui:` field. The compiler builds the UI resource into a read-only internal route that
  server-renders an `hc-*` fragment through the existing template pipeline (UI work follows the
  blessed `hc-*` patterns, mandatory rule 11), and the runtime serves it over the same
  `/_tesseraql/mcp` endpoint: `resources/list` / `resources/read` answer with the rendered fragment
  tagged `text/html;profile=mcp-app` and its `_meta.ui`, a linking tool advertises
  `_meta.ui.resourceUri`, and `initialize` negotiates the
  `capabilities.extensions["io.modelcontextprotocol/ui"]` extension when the app serves any UI
  resource. Security is per-resource (the bearer token rides into the route; an unauthorized read is
  a `resources/read` JSON-RPC error). Lint keeps a UI resource HTML-rendering and uri-addressed
  (`TQL-MCP-1008`/`1009`/`1011`), warns on a missing description (`TQL-MCP-1010`), rejects a dangling
  tool link (`TQL-MCP-1012`), and folds UI uris into the duplicate-uri check (`TQL-MCP-1007`); the
  governance gate scores a UI resource like a read route (never `advanced`); and an `mcp-ui` coverage
  kind tracks UI resources exercised by declarative suites. The protocol core (`tesseraql-mcp`)
  carries an opaque `_meta` on `McpTool`/`McpResource` and negotiates extensions in `initialize`.
  `AppManifest` gains `uiResources()` (discovered from `mcp/` by `kind`).
- Mounted-app MCP (roadmap Phase 24 mounted-app tools, see [docs/ai-mcp.md](docs/ai-mcp.md)): the
  runtime serves the MCP tools, resources, and UI resources declared by mounted and bundled system
  apps (design ch. 32) — not only the main app — from the one `/_tesseraql/mcp` endpoint. Each app's
  `direct:mcp.*` routes compile as before; the runtime registers every hosted app's MCP surface
  together and negotiates the MCP Apps UI extension when any hosted app serves a `ui://` resource.
  Security stays per-route (the bearer token rides into the declaring app's route; mounted apps share
  the main app's config, so policies and the JWT verifier resolve the same way). The startup
  route-conflict check now spans the MCP surface too: a tool name, resource uri, or UI uri shared by
  two apps (resources and UI resources share one uri namespace) is rejected with a clear error rather
  than failing as a raw duplicate-route-id error. The endpoint is wired whenever any hosted app
  declares an MCP surface, still governed by the single `tesseraql.mcp.enabled` flag.
- Internationalization (roadmap Phase 22, see
  [docs/internationalization.md](docs/internationalization.md)): per-app message catalogs
  (`messages/<locale>.yml`, nested maps flattened to dotted keys, layered over framework
  built-ins shipped in English and Japanese); per-request locale resolution after
  authentication — configured preference sources (`principal.*` claims, `query.*`
  parameters), then `Accept-Language` (RFC 4647 lookup against `tesseraql.i18n.locales`),
  then `tesseraql.i18n.defaultLocale` — published as the `request.locale` format source;
  `#{key}` message lookup in templates with the shell's `lang` following `#locale`;
  localized validation and error messages (the declared key rides as `messageKey` /
  `data-message-key`, `message` carries the resolved text, constraint violations fall back
  to `tql.constraint.<code>`, the conflict hint is the `tql.conflict.stale` key, and the
  top-level message localizes the status phrase); input-constraint rejections become
  field-scoped errors with `tql.input.<code>` keys; and locale-aware input parsing —
  `date`/`datetime`/`number` inputs with an optional `format` pattern parse with the request
  locale through the file-transfer column machinery. Breaking: a field error's `message` is
  now display text (the key moved to `messageKey`), and `Templates.render` without a locale
  renders English instead of `Locale.ROOT`.
- A client-side message catalog for Hypermedia Components:
  `/assets/_tesseraql/messages.js?locale=<tag>` serves an ES module merging the app's
  catalog over the kit's strings via `setMessages`, loaded by the shell before behaviors
  install (hc adoption Theme 6, folded into Phase 22).
- Hypermedia Components 0.1.1 (the upstream answer to the Phase 22 feedback issues
  #216–#219): the kit's i18n catalog is now a shared singleton across dist bundles, so
  `setMessages` works from any entry; the client catalog module imports the kit's official
  `dist/locales/ja.js` pack instead of a framework-maintained translation copy (the
  hand-kept catalog is gone — packs are completeness-checked upstream); field-error items
  carry `data-message-params`, so client-side catalog overrides interpolate the violation's
  values (`{min}`, custom SQL-rule columns) after a swap; and the kit documents the blessed
  date-field pattern the Phase 23 scaffolds will emit.
- Hypermedia Components 0.1.0 (from 0.0.1-alpha.0). htmx error fragments now follow the
  kit's documented field-errors contract — `hc-alert` with `data-variant="error"`,
  `role="alert"`, `data-hc-field-errors`, `hc-alert__error` items carrying
  `data-message-key` (was `data-message`) — so the kit's auto-installed
  `installFieldErrors` behavior distributes violations next to their inputs with ARIA
  wiring and no app JS. Breaking markup change: the invented
  `hc-alert-error`/`hc-alert-message`/`hc-field-errors`/`hc-field-error`/`hc-alert-hint`
  classes are gone. The system bootstrap now swaps 4xx field-errors fragments for htmx
  callers (htmx 2 leaves error responses unswapped by default).
- Printable documents (roadmap Phase 21, see
  [docs/printable-documents.md](docs/printable-documents.md)): the optional `tesseraql-pdf`
  module adds a `pdf` codec behind the file-codec SPI — `format: pdf` on
  `query-export`/`file-export` renders an app-authored XHTML print template (or a built-in
  plain grid) through the standard template engine and converts it to PDF with page-oriented
  CSS (`@page` size/margins, `counter(page)`/`counter(pages)` margin boxes, repeating table
  headers). Fonts under the app home's `fonts/` directory embed automatically under their own
  family names, CJK included; template resource resolution is confined to the app home and
  never fetches the network. Output is normalized — fixed producer, no timestamps or XMP, a
  seeded trailer `/ID` — so identical data yields byte-identical documents (design ch. 48).
  Rendering goes through openhtmltopdf, adopted at the ch. 50 decision point after
  prototyping an Apache PDFBox alternative behind the module's `PdfEngine` SPI; the SPI (and
  `tesseraql.pdf.engine`) remains the seam for drop-in replacement, and the LGPL dependency
  stays confined to the opt-in module - apps that never print do not install it.
- Notifications (roadmap Phase 20, see [docs/notifications.md](docs/notifications.md)): a
  `notify:` block on `command-json` routes and a `notify:` pipeline step on batch jobs send
  through configured channels — SMTP mail (camel-mail) with the body and subject rendered by
  the standard template engine (app-home-confined templates, credentials resolved at send
  time through the SecretResolver SPI), and outbound webhooks signed with HMAC-SHA256 over
  `timestamp.body` (`X-TesseraQL-Signature`/`X-TesseraQL-Timestamp`). Notifications enqueue
  in the command's transaction and ride the outbox; each publishes `notify.<id>.eventId`.
- Outbox retries and dead-letters: `FAILED` events retry on later dispatch polls and
  dead-letter at `tesseraql.outbox.dispatch.maxAttempts` (default 10). The operations
  console gains an outbox delivery-log screen and API (`GET /_tesseraql/ops/outbox`,
  `POST /_tesseraql/ops/outbox/{id}/redeliver`), and dead letters raise `TQL-OPS-9006`.
- Operations alerts reuse the notification channels: with
  `tesseraql.notifications.alerts.channel` configured, failed job executions notify as
  `ops.jobFailure` and newly raised dashboard alerts as `ops.alert`.
- Declarative validation for `command-json` (roadmap Phase 19, see
  [docs/declarative-validation.md](docs/declarative-validation.md)): a `validate:` block of
  cross-field rules in the whitelist-only core expression language plus validation SQL
  (SELECTs whose returned rows are the violations — uniqueness, existence, balance checks)
  executed inside the command's transaction, before any step writes. Violations answer a
  field-scoped `422` (`TQL-FIELD-4220`) with a stable error model — rule ids, field paths,
  rule codes, message keys — as JSON or as an inline `hc-alert` fragment for htmx. Lint
  checks the block statically (`TQL-YAML-1003`, `TQL-FIELD-2003`, `TQL-SQL-2101`/`2103`).
- Transactional write depth for `command-json` (roadmap Phase 18, see
  [docs/transactional-writes.md](docs/transactional-writes.md)): an ordered `steps:` list
  executes in a single transaction; later steps bind values produced by earlier ones,
  including generated keys (`keys:`, published as `steps.<name>.keys.<column>`).
- Canonical audit binds `/* audit.user */` and `/* audit.now */`, resolved from the
  principal and one clock reading per command.
- Declared row-count expectations (`expect: { rows: 1, onMismatch: conflict }`) turn silent
  lost updates into `409 Conflict` (`TQL-SQL-4092`) with a usable conflict hint; lint nudges
  the version-predicate/expectation pairing on UPDATEs (`TQL-SQL-2104`/`2105`).
- Constraint-violation mapping (`errors.constraints`): unique/foreign-key SQLState failures
  map to field-level error payloads, rendered as JSON or as an inline `hc-alert` fragment
  for htmx requests. Outbox commands now classify constraint failures like the standard
  pipeline (a NOT NULL violation answers 400, not 500).
- System apps recomposed on Hypermedia Components 0.1.0 primitives: `hc-field` form stanzas
  (label association, automatic required marks), a single status→variant mapping rendered
  through `hc-status`/`data-fill` (the `status-*` classes and their hand-kept dark-theme
  overrides are gone), `hc-empty` empty states, `hc-chips`/`hc-badge` counters, the `kv`
  table variant, `hc-item` sidebar navigation with an `aria-current` marker set by the
  bootstrap, and `hc-spacer`/`hc-cluster` page headers. The app stylesheet shrinks from
  40 lines to the card heading scale and the Studio source editor. The blessed htmx
  patterns — confirmed actions (`data-hc-confirm` × `hx-trigger="hc:confirmed"`), live data
  regions, busy indicators, inline field errors — are documented in
  [docs/hypermedia-ui.md](docs/hypermedia-ui.md).
- Document-number sequences as a managed SQL contract (`sequence:` steps backed by
  `tql_doc_sequence`, V2 framework migration): gapless allocation under the sequence row's
  lock, riding the command transaction.
- Authentication completion — RS256/JWKS and API keys (roadmap Phase 25, see
  [docs/authentication.md](docs/authentication.md)), both behind the existing authentication
  step and `Principal` model, JDK-only (no JOSE dependency):
  - **RS256 bearer validation.** `tesseraql.security.jwt.algorithm: RS256` verifies tokens with
    `SHA256withRSA` against a static `publicKey` (PEM, X.509 certificate, or JWK JSON) or a
    `jwksUri`. The JWKS key set is cached and refreshed by `kid`; an unknown `kid` (a rotated-in
    key) triggers at most one refetch per `jwks.refreshFloor`, so random-`kid` tokens cannot
    flood the JWKS endpoint, and an unknown `kid` that survives a permitted refetch fails closed.
    The expected algorithm is bound from configuration and checked against the token header before
    any key is consulted, so an `alg: none` or RS256/HS256-confusion token is rejected. A
    configurable `clockSkew` leeway applies to `exp` and the now-honored `nbf`.
  - **API keys for service callers.** A route declares `auth: apiKey`; the key is presented in a
    configured header (default `X-API-Key`) or as `Authorization: ApiKey <key>`. Clients are
    declared under `tesseraql.security.apiKeys.clients` with a stored hex SHA-256 of the key
    (never the raw key, resolvable via the secret SPI), an explicit subject/tenant/roles/
    permissions, and an enabled flag; the presented key is hashed and compared in constant time,
    deny-by-default, and the matched client's principal flows through the same authorization
    policies with its tenant bound from the key, not the request.
  - **Machine-checkable.** Lint adds `TQL-SEC-4040..4043` (RS256 key-source and algorithm-
    confusion rules) and `TQL-SEC-4044..4046` (an `auth: apiKey` route needs API-key config; a
    client needs a `secretHash`; a client granting nothing is warned); a new `api-key` coverage
    kind tracks API-key-authenticated routes, gatable via `coverage.thresholds.api-key`.
  - **Breaking change.** `SecurityConfig.JwtConfig` gains `algorithm`, `publicKey`, `jwksUri`,
    `jwks`, and `clockSkew` components, and `SecurityConfig` gains an `apiKeys` component
    (a two-arg constructor keeps the no-API-key case). HS256 with a `secret` is unchanged.
- Authentication completion — OIDC relying party (roadmap Phase 25, see
  [docs/authentication.md](docs/authentication.md)): a new opt-in leaf module `tesseraql-oidc`
  self-installs via the `RuntimeExtension` SPI (like SAML/SCIM) when on the classpath and
  `tesseraql.oidc.enabled` is true, serving the authorization-code + PKCE flow at
  `/_tesseraql/oidc/{login,callback,logout}`. The provider endpoints are discovered lazily (so app
  boot does not depend on the OP); `/login` records a single-use `state`/`nonce`/PKCE verifier in
  `tql_oidc_state` and redirects with an S256 `code_challenge`; `/callback` consumes the state
  (rejecting a forged, replayed, or `error=` response), exchanges the code (`client_secret_basic`
  or public PKCE), validates the ID token by reusing the RS256/JWKS verifier (signature, `iss` from
  discovery, `exp`/`nbf`) plus OIDC `aud`/`nonce` checks, links or provisions the principal via the
  identity contracts, and issues the standard browser session. JDK-only — no external OIDC/JOSE
  dependency. Ships an `oidc` coverage kind, config lint (`TQL-SEC-4050..4053`), a Studio **OIDC
  provider** IAM admin wizard, and a `TqlDomain.OIDC` error domain.
- Authentication completion — mutual TLS (roadmap Phase 25, see
  [docs/authentication.md](docs/authentication.md)): a route declares `auth: mtls` to authenticate
  a service caller by an X.509 client certificate that a TLS-terminating edge (reverse proxy,
  ingress, or mesh sidecar) forwards in a configured header (URL-encoded PEM). The runtime parses
  the certificate (JDK only — no third-party PKI dependency), checks its validity window against an
  optional `clockSkew`, optionally PKIX-validates it against a `trustBundle` CA bundle as
  defense-in-depth (revocation left to the edge), and matches its identity — exact subject DN
  (order/case-insensitive RDNs), a SAN value, or its DER SHA-256 fingerprint — against declared
  clients deny-by-default, resolving to an explicit principal so existing policies apply (`401` on
  no match / expired / malformed / missing, `403` on policy failure). A certificate is public, so
  matching is a lookup, not a secret compare; it is never logged. Ships an `mtls` coverage kind and
  config lint (`TQL-SEC-4060..4065`, including a warning when no `trustBundle` is set). **Phase 25
  is complete** (RS256/JWKS, API keys, OIDC, and mTLS).

### Developer experience

- Hypermedia Components 0.1.2 (the upstream answer to the Phase 23 feedback issues
  #244/#245/#246). Adopted across the framework and the scaffolds:
  - **CSRF on by default** for scaffolded mutations. State-changing browser routes declare
    `csrf: true`; the framework shell publishes the session token as
    `<meta name="csrf-token">` (browser authentication stashes it, the HTML renderer injects
    the reserved `_csrf` model variable), and the kit's auto-installed `installCsrfHeader`
    behavior attaches the `X-CSRF-Token` header to every htmx request. The no-JS path carries
    a hidden `_csrf` form field; the `csrf` step accepts the header or the field, and the
    request binder treats `_csrf` as reserved so it passes the mass-assignment guard.
  - **The mutating-form recipe** in scaffolded create/edit forms: an htmx post with inline
    field errors, a success redirect (the redirect renderer answers htmx callers `204` +
    `HX-Redirect` and no-JS callers a plain `303 Location`), a double-submit guard and busy
    spinner, the confirmed-destructive delete variant, and a no-JS fallback — see
    [docs/hypermedia-ui.md](docs/hypermedia-ui.md).
  - The kit's field-errors fix (same-name groups resolve to the visible control) corrects the
    ARIA wiring of the boolean checkbox the scaffolds already emit; the boolean field pattern
    is now blessed upstream.
- Scaffolding and project generation (roadmap Phase 23, see
  [docs/scaffolding.md](docs/scaffolding.md)): `tesseraql new <app>` generates a runnable
  skeleton — config, a Flyway migration whose starter table follows the Phase 18 write
  conventions, the shared nav template, a home page, a query-json search route, and a smoke
  suite covering both branches of its 2-way SQL. `tesseraql scaffold crud --table <t>`
  introspects the table over plain JDBC (the app's main datasource or `--jdbc-url`) and
  generates its CRUD slice: a list page with htmx live search over a table fragment, create
  and edit forms in Hypermedia Components markup (hc-field stanzas, the blessed
  `hc-datepicker` date fields, confirmed deletes), 2-way SQL with canonical audit binds and
  the optimistic-locking pairing (version predicate + `expect.rows`, `409` on stale edits),
  unique-index constraint mappings, and a declarative suite with data-independent
  expectations. Every bind reads the coerced `params.*` input view, so browser form posts
  and path parameters hit typed columns as typed parameters.
- Regeneration is idempotent and detects user edits (design ch. 22.20): each generated file
  carries a `tesseraql-scaffold-checksum` comment over its own content; pristine files
  regenerate, edited files are skipped and reported (exit 1), unmarked files are never
  touched, and `--force` overrides both. No ledger outside the files themselves.
- The example gallery gains `examples/scaffold-demo-app`, built exclusively by the two
  commands and dogfooded in CI: a Maven-plugin integration test regenerates it from the
  migration applied to PostgreSQL and asserts the committed tree is byte-identical, lints
  it, and runs its suites (100% branch coverage on the generated search templates); a
  runtime integration test drives the full CRUD flow over HTTP, including the stale-version
  `409` and the duplicate-key field error.
- AI-assisted development over MCP (roadmap Phase 24, see [docs/ai-mcp.md](docs/ai-mcp.md)):
  `tesseraql mcp --app <dir>` serves the framework's developer surfaces as Model Context
  Protocol tools, so an agent connected only over MCP scaffolds a table-backed route and
  iterates until lint, tests, and coverage pass with no direct filesystem access. Read tools
  (`manifest_summary`, `source_read`, `schema_introspect`, `lint`, `test`, `ops_status`) and
  gated write tools (`scaffold_crud` through the checksum-aware writer; `draft_save` /
  `draft_preview` / `draft_apply` through Studio's draft/apply, so an edit only lands if it
  compiles) each reuse the same service the CLI and Maven plugin use, all confined to the app
  home (design ch. 20.2). Two transports: stdio (the default — an agent launches the process;
  stdout is reserved for protocol frames), or `--transport http` for a shared development
  server whose Streamable HTTP endpoint reuses the app's `tesseraql.security.jwt` bearer
  verification and refuses to bind off-loopback without auth unless `--insecure`;
  `--read-only` drops the write tools. The protocol core is a new dependency-light module,
  `tesseraql-mcp` (JSON-RPC dispatch, the tool model, and the stdio and HTTP transports),
  reusable beyond the dev tool. New error domain `MCP` in the `TQL-*` taxonomy.
- The lint engine and the declarative test/coverage runner moved out of the Maven plugin into
  libraries so non-Maven callers (the MCP server) reuse them: `AppLinter`/`LintFinding` are
  now in `tesseraql-yaml` (`io.tesseraql.yaml.lint`), and `AppTestRunner` (with
  `DriverManagerDataSource` and `CoverageThresholdResolver`) in `tesseraql-report`
  (`io.tesseraql.report`). The Maven goals are unchanged.

### Quality and supply chain

- Lint fix: dotted policy ids (`users.read`) now resolve as literal keys of the
  `tesseraql.security.policies` map, so `TQL-SEC-4030` no longer fires for every defined
  policy.
- Declarative suites gain `messages:` cases (roadmap Phase 22): a case resolves keys of the
  app's message catalogs (exact tag, then bare language, like the runtime) and asserts on
  the texts as rows (`key`/`locale`/`text`). A new `message` coverage kind declares every
  shipped catalog by its language tag and counts it covered when a messages case reads it,
  gated via `coverage.thresholds.message`. Lint checks the catalogs statically:
  malformed files or non-BCP-47 names raise `TQL-YAML-1007`, a declared locale without a
  catalog warns `TQL-YAML-1103`, translation gaps against the default locale warn
  `TQL-YAML-1008`, and a validation-rule or constraint-mapping message key with no
  default-locale text warns `TQL-FIELD-2005`.
- A `document` coverage kind (roadmap Phase 21) declares every route exporting a printable
  document (`format: pdf`) and counts it covered when a suite case exercises one of its SQL
  artifacts, gated via `coverage.thresholds.document`. Lint checks pdf exports
  statically: workbook-only options raise `TQL-YAML-1005`, a non-`.html` or missing template
  raises `TQL-YAML-1006`.
- Declarative suites gain `notify:` cases (roadmap Phase 20): a case evaluates a route's
  `notify:` block or a job's notify steps against its params — guards and payload
  expressions run exactly as at runtime — and asserts on the fired notifications as rows,
  without touching SMTP or HTTP. A new `notification` coverage kind declares every route
  notification as `<routeId>.<notifyId>` and every job notify step as `<jobId>.<stepId>`,
  gated via `coverage.thresholds.notification`. Lint checks the declarations
  statically (`TQL-YAML-1004`, `TQL-FIELD-2004`, `TQL-SQL-2101`, `TQL-YAML-1102`).
- Declarative suites gain `validate:` cases (roadmap Phase 19): a case evaluates a route's
  validation rules — SQL rules against the test database, expression rules against the
  case's params — and asserts on the violations as rows, recording SQL coverage along the
  way. A new `validation` coverage kind declares every rule as `<routeId>.<ruleId>`, tracks
  the rules the suites evaluated, and gates via `coverage.thresholds.validation`.

## 0.1.0 - 2026-06-11

First public release: the complete framework, built and
verified per feature against live databases.

### Core

- 2-way SQL engine: bind comments with dummy values, `%if`/`%elseif`/`%else`, IN-list
  expansion, orderBy whitelists, a dependency-free expression evaluator, source maps, and
  coverage traces. Every SQL file stays executable in plain SQL tools.
- Simple YAML route/job model with manifest loading, app-home path confinement, hierarchical
  config with `${VAR:default}` resolution, and a pluggable SecretResolver SPI (`env`, `file`).

### Runtime and recipes

- Camel Main runtime (`tesseraql serve`) and a Spring Boot adapter; route recipes
  `query-json`, `command-json`, `query-html`, `page`, `query-export`, `file-import`,
  `file-export`, plus batch jobs (`batch-tasklet`, `batch-pipeline`) with quartz/timer
  triggers. Unknown recipes fail compilation.
- Multi-app hosting: mounted apps (ops-console, studio, iam-admin, `.tqlapp` packages or
  hash-pinned URL fetch) compile like the main app with their own migrations, scheduled jobs,
  outbox attribution, and per-app operations scope.
- Large-data: streaming SQL with dialect profiles, off-heap spooling (TempStore SPI),
  materialization guards, backpressure lanes with virtual-thread policies.
- Asynchronous CSV/Excel file transfers: YAML column mapping with per-login-user locale and
  time-zone formats, multipart and raw uploads streamed off-heap, all-or-nothing or skip
  error modes, exactly-once download hooks, and synchronous `query-export` downloads through
  the same codecs. Excel via the optional `tesseraql-excel` module (fastexcel + jxls).
- Live route reload from Studio with rollback: a broken edit never takes a serving endpoint
  down.

### Security, identity, federation

- Deny-by-default policy engine (roles/permissions/claims), JWT bearer and JDBC-backed
  session auth, CSRF, field-level authorization and mass-assignment guards, data
  classification and masking, CSP headers.
- Managed identity schema (`tql_*`) with Identity SQL Contracts, identity packs for all four
  databases, an admin UI, and seeded initial administrators (no default credentials).
- SAML SP: metadata, signed AuthnRequests, assertion validation, replay protection
  (single-use InResponseTo, assertion-id cache), signed HTTP-Redirect, IdP-initiated SLO,
  optional link-or-provision of local users.
- SCIM: inbound Users/Groups endpoints over app-authored contract SQL, PATCH normalization,
  and outbox-driven outbound provisioning.

### Operations

- Job repository with app-scoped claims across nodes, outbox dispatch with per-app
  attribution, idempotency store, retention sweeps.
- Operations console and `/_tesseraql/ops` API: batch dashboard, trace trees with app
  attribution, slow SQL, lanes, pinning diagnostics, alerts, file transfers - all scoped to
  the caller's `ops.app.<name>` grants (deny by default).
- Deployment: multi-stage container image, Kamal 2 + Cloudflare Tunnel reference setup,
  unauthenticated `/_tesseraql/health`.

### Quality and supply chain

- Declarative test suites (`tests/**.yml`) with SQL line/branch coverage and route, security,
  assertion, IAM-contract, SAML, and SCIM coverage kinds, gated per `coverage.thresholds.*`.
- Query Plan Guard (static/explain/analyze) with dialect plan inspectors and baselines.
- Reports: JUnit XML, HTML, JSON, SARIF, Cobertura, SonarQube generic coverage, Allure.
- Maven plugin goals: `lint`, `test`, `coverage`, `generate` (OpenAPI + htmx contract),
  `package-app`, `migrate`, `identity-schema`, `release-evidence`, `verify-evidence`,
  `governance`.
- Supply chain: Ed25519-signed release evidence, CycloneDX SBOM with license data,
  signature-verified plugin jars in isolated classloaders, hash-pinned `.tqlapp` packages,
  byte-stable generated artifacts.

### Databases

- PostgreSQL 16, MySQL 8, Oracle (23ai), and SQL Server 2022: dialect-aware SQL file
  resolution, label normalization, pagination/claim variants, Flyway migrations per
  component, datasource, and vendor - verified by live integration tests.
