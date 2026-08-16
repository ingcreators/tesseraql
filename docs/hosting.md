# Hosting several applications

One TesseraQL runtime serves one application, plus the framework's own surfaces — Studio, the
[operations console](ops-console.md), IAM Admin, the account pages and the sign-in pages. To
run several applications on one machine, start them together with `tesseraql host`. Each gets
its own runtime, and one port fronts them all.

```sh
tesseraql host --install-root /srv/tesseraql/apps --port 8080 --mode suite
```

Every application installed under the install root starts in its own runtime: its own Camel
context, its own datasource set, its own Studio, its own traces. A gateway on the given port
routes each request to the right one.

## The install root

The install root holds `catalog.json` — the list of installed applications, their versions,
their tenant entitlements and, for isolated mode, their hostnames — beside one unpacked tree
per application version. Baking that directory into a container image gives every node
identical bytes with nothing to download at boot.

The runtime does not fetch application packages itself. Getting bytes onto a host is a
deployment concern with better tools than a runtime fetcher. There is no `install` verb on the
CLI today: a deployment either ships the directory or drives `AppInstaller` from its own
tooling.

## The two modes

The mode is a deployment choice, and it decides three things at once. They cannot be mixed:
sharing a session across applications requires a cookie that reaches all of them, and that is
the same decision as sharing an origin.

| | `--mode suite` | `--mode isolated` |
| --- | --- | --- |
| For | related applications of one organization | unrelated applications, or applications from different authors |
| Address | `/apps/<appId>/` on one origin | one hostname per application |
| Sign-in | shared: one session across the suite | per host, never shared |
| Framework database | shared | per application |
| Business database | shared | per application |

**Suite mode** puts every application on one origin under its own path prefix. The runtimes
are told the prefix they serve under, so each answers at the addresses it emits
([base-path.md](base-path.md)); the session cookie is issued at the origin root, so one
sign-in reaches every application in the suite.

**Isolated mode** gives each application its own hostname, declared when it is installed. A
session established on one hostname does not authenticate against another. An application with
no hostname fails the start rather than being catalogued, started, and left unreachable.

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

**Forwarded headers pass through.** The gateway does not strip the mTLS forwarded header an
application declares, because it cannot tell a caller's copy from the edge's without knowing which
sources are trusted — and stripping it unconditionally destroyed the edge's own value, which made
[mTLS authentication](authentication.md#mutual-tls-client-certificates) unusable behind the gateway. The trust contract
is the edge's, and it is the same one that has always applied: **the edge must overwrite or strip
the `forwardedHeader` on every inbound request, and the runtime must not be reachable except through
that edge.** A gateway reachable from anywhere but the edge is a deployment error, and no amount of
header filtering one hop later repairs it.

Responses pass through unchanged: framing, chunked bodies, event streams, `Location` values and
cookie attributes all reach the client as the application wrote them. An event stream arrives frame
by frame rather than in bursts, which is what the ops console, Studio's preview and the MCP
transport depend on.

## What isolation gives you, and what it does not

Each runtime is separate: a separate Camel context, URL space, Studio, trace buffer and
configuration. A route in one application cannot collide with a route in another, and Studio
shows the application whose runtime serves it.

**Data isolation is enabled, not guaranteed.** The framework does not verify that co-hosted
applications reach different data, and does not claim to. An application declares the shape of
its datasource; the operator installing it supplies the connection. Under suite mode they
share one by design.

Nor is this a security boundary. The applications share a JVM, which offers no in-process
mechanism to confine bytecode. This is why the [admission profile](admission.md) requires a
shared application to be declarative-only: while isolation is arranged rather than enforced,
"the framework is the sandbox" is the only ground on which an application nobody reviewed can
be run.

## Operating a host

The [operations console](ops-console.md) is per application, like Studio: it reports on the
runtime that serves it, and its queries stay scoped to that application even when several
share a business database. `ops.app.<name>` is the permission to open an application's
console.

An operator running a suite therefore has one console per application rather than one screen
listing every application's jobs. Cross-application monitoring belongs to the
[metrics exposition](deployment.md#metrics-prometheus), which already labels job runs by
application.

## Next

- [deployment.md](deployment.md) — shipping an application, bootstrap, migrations, TLS.
- [ops-console.md](ops-console.md) — what each application's console reports.
- [admission.md](admission.md) — the bar an application must clear before someone else runs it.
