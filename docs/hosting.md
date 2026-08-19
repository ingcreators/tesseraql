# Hosting several applications

One TesseraQL runtime serves one application; the framework's own surfaces — the sign-in and
account pages, the portal, the [operations console](ops-console.md) and IAM Admin — are the
stack's, served once at the origin scope. To run several applications on one machine, start
them together with `tesseraql host`. Each gets its own runtime, and one port fronts them all.
Studio exists only in development: a hosted production stack mounts no Studio at all, and no
configuration changes that ([studio-shell.md](studio-shell.md)) — changes reach a host through
`deploy`, never an editor.

```sh
tesseraql host --stack /srv/tesseraql/apps --port 8080
```

Every application the directory holds starts in its own runtime: its own Camel context, its own
datasource set, its own traces. A gateway on the given port routes each request to the right
one.

## What `--stack` accepts

Two shapes, and the directory's contents decide which it is
([cli-surface.md](https://github.com/ingcreators/tesseraql/blob/main/docs/cli-surface.md)):
an **install root**, which holds `catalog.json`, or a **folder of application homes**, which
holds none. They host identically — the second is how a stack runs from source trees without
being packaged first.

A stack is a directory that **holds** applications, never an application home itself, so an
application home is refused — the refusal prints the narrowing that would have worked.
`--app-name <name>` serves one member of the stack, at the same address it has beside its
neighbours: narrowing is a filter, not a second deployment shape, so the application emits the
same URLs either way.

## The stack's own settings — `tesseraql-stack.yml`

A stack may declare settings of its own in `tesseraql-stack.yml`, in the directory `--stack`
names — always that name, always that place. The file is read through the same configuration
machinery applications use, so `${ENV_VAR:default}` and `${secret.…}` resolve in it.

What belongs there is what fails silently when it diverges between applications or between
development and production:

```yaml
framework:
  datasource:            # one sign-in across the stack rides this connection
    jdbcUrl: jdbc:postgresql://${DB_HOST:localhost}:5432/stack
    username: ${secret.env.STACK_DB_USER}
    password: ${secret.env.STACK_DB_PASSWORD}
externalOrigin: https://apps.example.com
root:
  redirect: orders       # /  ->  /orders; omitted, / lands on the portal
```

When the stack supplies `framework.datasource`, the host builds one pool and every application's
framework state — sessions, tokens, preferences — rides it, so one sign-in carries by
construction. An application that *explicitly* declares `tesseraql.framework.datasource` in that
arrangement is refused (`TQL-APP-4212`) rather than silently repointed.

The host also migrates the framework's `security` schema **once**, before any application starts;
each hosted runtime then validates it and refuses to start on a mismatch (`TQL-APP-4214`). A
runtime pointed at a framework database the host never migrated therefore fails loudly at boot,
instead of producing a stack where signing in silently does not carry.

Every part of the file is optional, and absence is checked rather than trusted: when the stack
supplies no framework datasource and more than one application runs, the host compares each
application's own resolved framework coordinate and refuses to start on disagreement
(`TQL-APP-4211`), naming each application — because the alternative is a stack where signing in
silently does not carry. The gateway's `--port`, `--http2` and `--trusted-proxies` stay flags: a
wrong value there fails loudly at bind or handshake, which is exactly what the file exists to
avoid needing.

`tesseraql new` writes the file beside the application it creates — all guidance comments, which
is enough: the file is also the stack's marker, and development commands find the stack by it.

## The install root

An install root holds `catalog.json` — the list of installed applications, their versions,
and their tenant entitlements — beside one unpacked tree
per application version. Baking that directory into a container image gives every node
identical bytes with nothing to download at boot.

The runtime does not fetch application packages itself. Getting bytes onto a host is a
deployment concern with better tools than a runtime fetcher. A deployment either ships the
directory, or copies a `.tqlapp` package to the host and runs `tesseraql deploy` (below); the
`AppInstaller`/`AppUpgrader` library stays for tooling that wants to drive the same lifecycle
itself.

### Modules are resolved before the host starts

An application's `tesseraql.modules` (drivers and the pdf/excel/s3 codecs) are resolved into its
`work/modules` **before** hosting, because resolution reaches Maven repositories and a production
host boots offline. A package ships the declaration and `modules.lock`, not the jars, so the
operator runs the resolve once per install:

```sh
tesseraql modules resolve --stack /opt/tesseraql/apps   # every member, or --app for one
```

The host refuses to start an application whose declared modules were never resolved
(`TQL-APP-4216`), or whose `work/modules` disagrees with its `modules.lock` (`TQL-APP-4217`) —
running it silently without the functions, codecs and drivers it declared is the failure mode
those refusals replace. Each hosted runtime then loads its own `work/modules` on its own
classloader: module visibility equals runtime scope, so two applications can carry the same
driver at different versions, and a custom expression function is visible exactly to the
application that declared it. Changing a member's module set is a redeploy of that member, not a
live edit.

## Deploying one application

Deploying is writing files. `catalog.json` names each member's active version, and
`.upgrade/<name>.json` names a staged candidate and its traffic weight. A running host watches
the install root and converges to what the files say, replacing exactly that member's runtime
while the stack keeps serving
([stack-architecture.md](https://github.com/ingcreators/tesseraql/blob/main/docs/stack-architecture.md)
Decision 29). `tesseraql deploy` is the pen:

```sh
tesseraql deploy ./orders-2.1.0.tqlapp --stack /opt/tesseraql/apps        # replace
tesseraql deploy ./orders-2.1.0.tqlapp --stack ... --canary --weight 10   # stage a canary
tesseraql deploy weight orders 50 --stack ...                             # move the ramp
tesseraql deploy promote orders --stack ...                               # candidate goes active
tesseraql deploy rollback orders --stack ...                              # canary or last upgrade
tesseraql deploy status orders --stack ...                                # read back both files
```

The package is a local path — getting bytes onto the host stays your deployment's concern — and
`--sha256 <hex>` verifies it before anything is written. The command works with no host running:
the state is written, and the next `host` start converges to it. `--stack` must be an install
root; a workspace of source trees has no version ledger and is refused (`TQL-UPGRADE-4092`),
because it deploys by restarting the stack.

**Or deploy remotely, with a grant instead of install-root access.** The stack's origin serves
an authenticated deploy endpoint (`POST /_tesseraql/deploy`), and `deploy --url` is its pen:

```sh
export TESSERAQL_TOKEN=$(tesseraql token --url https://stack.example.com --login ci | tail -1)
tesseraql deploy ./orders-2.1.0.tqlapp --url https://stack.example.com
```

The endpoint checks the caller's `tql.app.deploy.<name>` grant against the **package's declared
name** — never a request parameter, so a token scoped to `orders` cannot deploy `billing` by
renaming anything — runs the same preflight, and writes the same intent on its own install
root; a refused deploy answers as the response and writes nothing. This is how a pipeline
deploys only the applications it manages, with a scoped short-lived token and no login to the
host machine. The same endpoint has a browser face: the ops console's **Deploy** page
(`/_tesseraql/ops/console/deploy`), shown to any signed-in holder of a `tql.app.deploy` grant,
uploads a `.tqlapp` through the same checks ([ops console](ops-console.md#deploy)). It needs
the stack file to carry the token issuer:

```yaml
# tesseraql-stack.yml
security:
  jwt:
    secret: ${secret.env.STACK_JWT_SECRET}
    audience: https://stack.example.com
    rolesClaim: roles
    permissionsClaim: permissions
  token:
    enabled: true
```

The subtree configures the *stack surface runtime* — the origin's token page, the
`/_tesseraql/token` exchange, and the deploy endpoint's bearer validation ride it; each member
keeps its own declared JWT configuration. The authority is an operational guardrail, not
isolation between distrusting teams: those get separate stacks, which is what a stack means.
Install-root access on the host machine remains stack-root; the endpoint adds a narrower door,
it does not narrow the wide one.

The host replaces without a gap. The new version's runtime starts beside the old one, with the
boot guards re-run for it alone: modules resolved, framework-datasource agreement, and the
framework-schema validation. It migrates its own business schema, and it must answer
`/_tesseraql/health/ready` before any traffic moves. Then the address swaps to it, and the old
runtime drains under its own declared `tesseraql.shutdown.timeout` — long-lived streams are cut
at the force timeout, and their clients reconnect onto the new version. In-flight batch runs are
asked to stop cooperatively at drain start: a run between steps stops with an exact resume
point, a chunk step stops at its next committed checkpoint, and a run in its final step
completes. Rerunning a stopped run goes through the operator's existing rerun, deliberately not
automatically. **A failed replace is a no-op**: refused admission, a failed start, or a failed
probe leaves the old runtime serving, and the refusal lands in the status file.

Two windows to know about. First, a staged canary's `--weight` gates **HTTP traffic only**: the
candidate's jobs, pollers and outbox work from the moment it starts, claim-arbitrated against
the old version's, so a 10% canary's background participation is not 10%. The overlap also means
migrations must stay expand/contract — the deploy window's contract, see
[deployment.md](deployment.md). Second, a replaced runtime answers on an ephemeral internal port
even when it declares `server.port`; the declared port returns at the next stack start. The
relay follows the slot either way — a declared port only matters for reaching an application
beside the gateway.

The host reports each attempt in `.upgrade/<name>.status.json`: applied, or refused with the
refusal's own message. One file, one writer — the CLI writes intent, the host writes outcome.
`deploy --wait` (and `promote --wait`) tails that file so a pipeline gets a synchronous exit
code, and `deploy status` renders both sides. Membership stays start-time: a new name in the
catalogue, or one removed, is the stack changing shape and waits for the next stack start (the
host logs the owed restart). Previous versions stay on disk — they are rollback's working
material.

Install-root write access is deploy authority over the whole stack: `catalog.json` is one file,
so no permission arrangement scopes it per application. Hold the per-team line where teams
already differ — each application's repository and its CD pipeline — with `tesseraql deploy` as
the pipeline's tool.

## One address per application

Every application is addressed as `/<name>/` on one origin, and the stack shares one
sign-in across them. The address is derived from `tesseraql.app.name`, always, so an install or an
upgrade can never move it. The runtimes are told the prefix they serve under, so each answers at
the addresses it emits ([base-path.md](base-path.md)), and the session cookie is issued at the
origin root so one sign-in reaches every application.

An earlier `--mode isolated` gave each application its own hostname and no shared session. It is
gone: a stack is defined by sharing an origin and a sign-in, and a mode that undid both was a
second deployment shape to reason about, document and test — which
[stack-architecture.md](https://github.com/ingcreators/tesseraql/blob/main/docs/stack-architecture.md)
Decision 12 removes so that development and production have one topology between them. An
application that must not share a session with its neighbours gets its own stack.

## The root, the portal, and the origin scope

The origin root does exactly one thing: it answers `307`. By default it redirects to the
stack's portal at `/_tesseraql/portal` — sign in, and the portal lists the applications you may
reach, each at its `/<name>` address. A stack whose users should land in one main application
declares that in `tesseraql-stack.yml` instead:

```yaml
root:
  redirect: orders       # /  ->  /orders
```

The redirect is temporary on purpose. A permanent one would be cached by browsers past the
configuration change that retires it. Naming an application the stack does not hold refuses the
start (`TQL-APP-4215`), listing the names it does hold.

The portal, the stack's sign-in, the account pages and [IAM Admin](iam-admin.md) are served by
the stack's own runtime, which `host` and `dev` start beside your applications. It answers the
origin-scope `/_tesseraql/*` and `/assets/*` paths; `/_tesseraql/health/live` and
`/_tesseraql/health/ready` stay the gateway's own answer, so a load balancer's probe never
depends on it. These surfaces answer **once, at the origin**: a hosted member serves no sign-in,
account or IAM Admin copy of its own. An unauthenticated browser on a member page is bounced to
`/_tesseraql/login?redirect=<the prefixed page>` and returned there after signing in, and member
pages link the account chip and IAM Admin origin-absolute.

Reach into a member is a grant, not a URL. After authentication, a principal without
`tql.app.use.<name>` is refused by that member on every route — `auth: public` routes excepted,
service callers included — and the portal's tiles filter by the same atom beside tenant
entitlement. Seed `tql.app.use` grants (or a `tql.app.use.*` baseline role) before your users
sign in; the bootstrap administrator's default permissions carry the wildcard.

## The gateway routes, the ingress protects

The gateway is a route to the application that answers, not a guard in front of it. It fronts
applications the operator installed, on one machine, behind whatever reverse proxy the deployment
already runs — and that reverse proxy is where the protections belong:

| Concern | Belongs to |
| --- | --- |
| Request body limits | the ingress, and each application's own declared limits |
| Rate limiting | the ingress |
| TLS termination | the ingress |
| Which application answers | the gateway |
| Tenant entitlement at the door | the gateway, as a convenience filter — the application's own tenancy resolution is authoritative |
| Overwriting headers a caller must not set for itself | the ingress |

The gateway imposes **no body limit of its own** in either direction. It used to: 10 MB inbound and
64 MB outbound, which capped every attachment and import at a number the gateway picked and
truncated large exports mid-download. An application keeps whatever limits it declares, and a
deployment that wants a limit in front of every application sets one at the ingress, where it can be
tuned per route and per client.

**Forwarded headers pass through by default.** The gateway does not strip the mTLS forwarded header an
application declares, because it cannot tell a caller's copy from the edge's without knowing which
sources are trusted — and stripping it unconditionally destroyed the edge's own value, which made
[mTLS authentication](authentication.md#mutual-tls-client-certificates) unusable behind the gateway. The trust contract
is the edge's, and unchanged: **the edge must overwrite or strip the `forwardedHeader` on every
inbound request, and the runtime must not be reachable except through that edge**. A gateway
reachable from anywhere but the edge is a deployment error, and no amount of header filtering one
hop later repairs it.

### HTTP/2

The gateway speaks HTTP/1.1 by default. `--http2` serves and forwards cleartext HTTP/2 (h2c):

```sh
tesseraql host --stack /srv/tesseraql/apps --port 8080 --http2
```

**One switch moves both hops** — the client's connection to the gateway and the gateway's
connection to each application — because enabling it at one end alone breaks request framing. An
application that does not offer h2c answers the upgrade over HTTP/1.1 and is reached exactly as
before, so turning this on cannot make an application unreachable. HTTP/1.1 clients keep working
against an h2c gateway: the upgrade is offered, not required.

**Naming your edge adds a second line of defence.** `--trusted-proxies` takes the addresses whose
forwarded headers are the edge's rather than a caller's:

```sh
tesseraql host --stack /srv/tesseraql/apps --port 8080 \
  --trusted-proxies 10.0.0.0/8,192.168.1.5
```

With it set, an application's `mtls.forwardedHeader` is stripped from every request arriving from
anywhere else, so a caller reaching the gateway around the edge cannot present the assertion the
application is configured to believe. The comparison is against the **peer of the connection**,
never a header: a caller can write `X-Forwarded-For`, and cannot write the socket it connected
from. CIDR blocks and bare addresses are both accepted, IPv4 and IPv6.

Leaving it empty strips nothing, and that is deliberate. Reading "no edge named" as "trust nobody,
strip from everyone" is the unconditional strip that made mTLS unusable behind a gateway, and it
would be the default again. `X-Tenant-Id` is never stripped either way, because the application's
own tenancy resolution is the authority on it.

Responses pass through unchanged: framing, chunked bodies, event streams, `Location` values and
cookie attributes all reach the client as the application wrote them. An event stream arrives frame
by frame rather than in bursts, which is what the ops console, Studio's preview and the MCP
transport depend on.

## What isolation gives you, and what it does not

Each runtime is separate: a separate Camel context, URL space, trace buffer and
configuration. A route in one application cannot collide with a route in another, and in
development each application's workshop edits exactly its own runtime's source.

**Data isolation is enabled, not guaranteed.** The framework does not verify that co-hosted
applications reach different data, and does not claim to. An application declares the shape of
its datasource; the operator installing it supplies the connection, and a stack shares one by
design.

Nor is this a security boundary. The applications share a JVM, which offers no in-process
mechanism to confine bytecode. This is why the [admission profile](admission.md) requires a
shared application to be declarative-only: while isolation is arranged rather than enforced,
"the framework is the sandbox" is the only ground on which an application nobody reviewed can
be run.

## Operating a host

The [operations console](ops-console.md) is the stack's, at the origin scope:
`/_tesseraql/ops/console` is one shell whose sidebar is an application switcher — the
caller's `tql.ops.view.<name>` grants applied to the member list, deny by default, with a
staged canary as a second entry. Selecting an application delegates that application's pages
over loopback to its own runtime with the caller's session, so authorization stays at the
member. `tql.ops.view.<name>` is the authority to see an application's operational data and
`tql.ops.run.<name>` the authority to act on it — run and cancel jobs, redeliver outbox and
dead-lettered events — granted separately, so an on-call reader is not an acting pen. The
wildcard is a terminal `*` (`tql.ops.view.*`).

One console per stack is a topology rule, like the derived address: a hosted member serves no
console of its own. Cross-application aggregate views beyond the switcher and the overview's
per-member cards belong to the [metrics exposition](deployment.md#metrics-prometheus), which
already labels job runs by application.

## Windows Server

A Windows deployment does not use the container image. It runs the **`tesseraql-host`
distribution** — `tesseraql-host-<version>-windows-x86_64.zip` on each release — which carries
the host and the operator commands (`host`, `deploy`, `migrate`, `identity-schema`, `job`,
`token`, `verify`, `admission`, `routes`, `duckdb`) with a bundled Java runtime, and none of the
development tooling: no `dev`, no Studio, no embedded database. Development on Windows uses the
developer CLI (`scoop install tesseraql`); a server runs this artifact.

Install and supervise it as a Windows service:

1. Unzip under a program directory, e.g. `C:\Program Files\tesseraql`.
2. The zip includes `tesseraql-host-service.xml`, a service definition for
   [WinSW](https://github.com/winsw/winsw). Download `WinSW-x64.exe`, place it beside the XML
   as `tesseraql-host-service.exe`, and adjust the stack directory, port, and the environment
   values your applications' `config/application.yml` placeholders expect.
3. `tesseraql-host-service.exe install`, then `net start tesseraql-host`.

Stopping the service is the ordered drain, not a kill: the wrapper's stop reaches the same
shutdown path `SIGTERM` reaches in a container, so in-flight work finishes before the process
exits. The service log (under `logs\` beside the definition) records `Stack stopping` when the
drain begins — the stop path is asserted by CI on every release build.

Everything else on this page is platform-neutral. The install root, the catalog, `deploy` with
canary, promote and rollback work identically, because activating a version is an atomic
catalog-pointer switch and a placed version directory is never modified — no upgrade ever
replaces a file the running service holds open.

## Next

- [deployment.md](deployment.md) — shipping an application, bootstrap, migrations, TLS.
- [ops-console.md](ops-console.md) — what each application's console reports.
- [admission.md](admission.md) — the bar an application must clear before someone else runs it.
