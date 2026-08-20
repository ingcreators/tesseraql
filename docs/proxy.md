# Proxy and restricted networks

TesseraQL works behind a corporate proxy, an internal Maven mirror, and a TLS-intercepting proxy.
This guide covers the build/resolution-time and runtime outbound paths.

## What honors what

| Outbound path | Honors |
| --- | --- |
| Embedded module resolver (`tesseraql modules`, `serve`) | `~/.m2/settings.xml` `<proxies>`/`<mirrors>`/`<servers>`, then JVM proxy properties / the env bridge |
| Runtime HTTP clients (OIDC, HTTP-call, webhooks) | JVM proxy properties via `ProxySelector.getDefault()` (the env bridge feeds these) |
| `mvnw` (Maven download) | `MVNW_REPOURL` and `~/.m2/settings.xml` |
| S3 (AWS SDK) | `https.proxyHost` system properties |

## Proxy by environment variables

The CLI bridges the container/CI-standard variables to the JVM proxy system properties (which the
JDK does not read on its own), at startup:

```sh
export HTTPS_PROXY=http://user:pass@proxy.example.com:3129
export HTTP_PROXY=http://proxy.example.com:3128
export NO_PROXY=.internal.example.com,localhost
tesseraql dev
```

This sets `http(s).proxyHost`/`proxyPort` (and `proxyUser`/`proxyPassword` when present) and
converts `NO_PROXY` to `http.nonProxyHosts`. **Precedence:** an explicitly set system property is
never overwritten, so `-Dhttp.proxyHost=…` (or `settings.xml`) takes priority over the env bridge.

Pass JVM options to the launcher via `TESSERAQL_JAVA_OPTS`:

```sh
TESSERAQL_JAVA_OPTS="-Dhttps.proxyHost=proxy.example.com -Dhttps.proxyPort=3129" tesseraql dev
```

## Internal Maven mirror / repository

Module resolution and the Maven path read `~/.m2/settings.xml`. Point them at an internal
Nexus/Artifactory with a `<mirror>`:

```xml
<settings>
  <mirrors>
    <mirror>
      <id>internal</id>
      <mirrorOf>*</mirrorOf>
      <url>https://nexus.example.com/repository/maven-public/</url>
    </mirror>
  </mirrors>
  <proxies>
    <proxy>
      <id>corp</id><active>true</active><protocol>https</protocol>
      <host>proxy.example.com</host><port>3129</port>
    </proxy>
  </proxies>
</settings>
```

Point the Maven Wrapper's own download at the mirror with `MVNW_REPOURL`:

```sh
export MVNW_REPOURL=https://nexus.example.com/repository/maven-public
./mvnw verify
```

## TLS-intercepting proxy (corporate root CA)

A proxy that intercepts TLS presents a corporate root CA the JVM must trust. This is independent of
the proxy host/port settings above and applies to every outbound path. Either import the CA into the
JDK truststore, or point the JVM at a custom truststore:

```sh
TESSERAQL_JAVA_OPTS="-Djavax.net.ssl.trustStore=/etc/ssl/corp.jks -Djavax.net.ssl.trustStorePassword=…" \
  tesseraql dev
```

## Air-gapped / offline

Everything a disconnected machine needs is a Maven coordinate, so one command collects it. On a
connected machine, fetch a **bag** — a portable local repository:

```sh
tesseraql modules fetch --stack /path/to/stack --into ./tesseraql-bag \
    --platform linux-amd64,windows-amd64
```

It resolves each member's `tesseraql.modules` closure exactly as `modules.lock` pins it (an
application with no lock is refused: the lock is what says which closure was reviewed), imports the
BOM so a later resolve of an unversioned coordinate works, and adds the embedded PostgreSQL binary
for each `--platform` you name — the one artifact whose coordinate depends on the target machine
rather than on the application. `bag.json` at the root records what was collected, from which
application, with a SHA-256 each.

Carry the directory across, then point the disconnected side at it:

```sh
tesseraql modules resolve --stack /opt/tesseraql/apps --repo ./tesseraql-bag --offline
tesseraql package --app ./orders --repo ./tesseraql-bag --offline
tesseraql dev --embedded-db --repo ./tesseraql-bag
```

`--repo` sets the local repository (the same thing `-Dmaven.repo.local` does), and `--offline`
means nothing leaves the machine. An internal mirror configured in `~/.m2/settings.xml` remains
the alternative when the disconnected side can reach one.

A **production host** needs none of this: it has no resolver, and `tesseraql package` carries an
application's locked closure inside the `.tqlapp`. The bag is for developer machines, CI runners
that build packages offline, and operators preparing an image.

## Next

- [getting-started.md](getting-started.md) — the installation this configures.
- [deployment.md](deployment.md) — outbound paths at runtime.
