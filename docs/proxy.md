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
tesseraql serve --app .
```

This sets `http(s).proxyHost`/`proxyPort` (and `proxyUser`/`proxyPassword` when present) and
converts `NO_PROXY` to `http.nonProxyHosts`. **Precedence:** an explicitly set system property is
never overwritten, so `-Dhttp.proxyHost=…` (or `settings.xml`) takes priority over the env bridge.

Pass JVM options to the launcher via `TESSERAQL_JAVA_OPTS`:

```sh
TESSERAQL_JAVA_OPTS="-Dhttps.proxyHost=proxy.example.com -Dhttps.proxyPort=3129" tesseraql serve --app .
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
  tesseraql serve --app .
```

## Air-gapped / offline

Commit `modules.lock` and pre-seed the module cache (or use an internal mirror). After the first
fetch, `tesseraql modules resolve --offline` and `serve` resolve reproducibly with no outbound
calls. Resolve modules at build/CI time and bake the cache into the image so a production `serve`
performs no module-resolution outbound.
