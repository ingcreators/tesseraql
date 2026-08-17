# Hosting several applications

One TesseraQL runtime serves one application, plus the framework's own surfaces — Studio, the
[operations console](ops-console.md), IAM Admin, the account pages and the sign-in pages. To
run several applications on one machine, start them together with `tesseraql host`. Each gets
its own runtime, and one port fronts them all.

```sh
tesseraql host --stack /srv/tesseraql/apps --port 8080
```

Every application the directory holds starts in its own runtime: its own Camel context, its own
datasource set, its own Studio, its own traces. A gateway on the given port routes each request
to the right one.

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

## The install root

An install root holds `catalog.json` — the list of installed applications, their versions,
and their tenant entitlements — beside one unpacked tree
per application version. Baking that directory into a container image gives every node
identical bytes with nothing to download at boot.

The runtime does not fetch application packages itself. Getting bytes onto a host is a
deployment concern with better tools than a runtime fetcher. There is no `install` verb on the
CLI today: a deployment either ships the directory or drives `AppInstaller` from its own
tooling.

## One address per application

Every application is addressed as `/apps/<appId>/` on one origin, and the stack shares one
sign-in across them: the runtimes are told the prefix they serve under, so each answers at the
addresses it emits ([base-path.md](base-path.md)), and the session cookie is issued at the
origin root so one sign-in reaches every application.

An earlier `--mode isolated` gave each application its own hostname and no shared session. It is
gone: a stack is defined by sharing an origin and a sign-in, and a mode that undid both was a
second deployment shape to reason about, document and test — which
[stack-architecture.md](https://github.com/ingcreators/tesseraql/blob/main/docs/stack-architecture.md)
Decision 12 removes so that development and production have one topology between them. An
application that must not share a session with its neighbours gets its own stack.

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

Each runtime is separate: a separate Camel context, URL space, Studio, trace buffer and
configuration. A route in one application cannot collide with a route in another, and Studio
shows the application whose runtime serves it.

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

The [operations console](ops-console.md) is per application, like Studio: it reports on the
runtime that serves it, and its queries stay scoped to that application even when several
share a business database. `ops.app.<name>` is the permission to open an application's
console.

An operator running a stack therefore has one console per application rather than one screen
listing every application's jobs. Cross-application monitoring belongs to the
[metrics exposition](deployment.md#metrics-prometheus), which already labels job runs by
application.

## Next

- [deployment.md](deployment.md) — shipping an application, bootstrap, migrations, TLS.
- [ops-console.md](ops-console.md) — what each application's console reports.
- [admission.md](admission.md) — the bar an application must clear before someone else runs it.
