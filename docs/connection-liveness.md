# Connection liveness: a PostgreSQL connection says whose it is, a vanished database host is noticed in about a minute, and the server's side is documented

> **Status: designed 2026-09-26, against main `7a8af23b2` (0.19.0-SNAPSHOT).** The maintainer
> asked whether HikariCP's other defaults are appropriate, including whether anything keeps
> "zombie" connections from being left behind. Reading the jars answered it. HikariCP's defaults
> are sound: a connection that dies while it waits in the pool is found before anyone uses it.
> The gaps are one layer down, in the PostgreSQL driver's defaults and in the server's. They
> chose every recommendation.
>
> There are two slices:
>
> **S1 (whose connection).** Every PostgreSQL connection TesseraQL opens carries an
> `application_name` naming the application and the pool, so `pg_stat_activity` can tell them
> apart. **Shipped, #1469**, as designed. The label and the encoding live in core
> (`PostgresProperties`), so the runtime's pools and the CLI's `DriverManagerDataSource` share
> them. The server accepted a Japanese application name in its percent-encoded form. A revert
> probe that dropped the naming from the common pool builder turned three cases red.
>
> **S2 (a vanished peer).** Every such connection runs TCP keepalive with TesseraQL's own
> timings, so a statement whose database host has gone fails in about a minute instead of
> waiting forever. [deployment.md](deployment.md) documents the server settings that end the
> backends a vanished TesseraQL node leaves behind. **Shipped, #1470**, as designed, and with it
> the campaign is complete. The test reads the driver's own socket for a pooled connection and for
> a tool's, and a revert probe that stopped naming the factory turned both red. deployment.md's
> pool-keys table now states HikariCP's actual defaults (10 min, 30 min, 2 min) instead of
> "Hikari's".
>
> **One recommendation changed while designing.** In conversation, S2 was a network timeout per
> statement plus the driver's `tcpKeepAlive`, whose timings are the operating system's (two hours
> idle on Linux) until someone tunes them. Reading the driver showed that it builds its socket
> through a factory, and a factory can set the timings per socket. That needs no operating
> system tuning, and it never cuts a statement that is long and silent but alive. The network
> timeout is refused below (decision 4).

## What is true today

| # | Subject | Finding |
| --- | --- | --- |
| 1 | HikariCP's defaults | HikariCP 7.1.0, read with `javap`: `connectionTimeout` 30 s (TesseraQL declares its own 30 s, `DataSources.java`), `validationTimeout` 5 s, `idleTimeout` 10 min, `maxLifetime` 30 min, `keepaliveTime` 2 min, `leakDetectionThreshold` off. A connection idle for more than 500 ms is validated when borrowed, an idle one is pinged every `keepaliveTime`, and every connection is retired at `maxLifetime`. A connection returned with uncommitted work is rolled back. **So a connection that dies while it waits in the pool is found before anyone uses it.** |
| 2 | The PostgreSQL driver's defaults | pgjdbc 42.7.13, read from `PGProperty`: `connectTimeout` 10 s, `socketTimeout` 0 (a read waits forever), `loginTimeout` 0, `tcpKeepAlive` false, `cancelSignalTimeout` 10 s, `ApplicationName` "PostgreSQL JDBC Driver". The driver calls `setKeepAlive(tcpKeepAlive)` on every socket it opens (`ConnectionFactoryImpl`, `PGStream`), so keepalive is off unless the property turns it on, whatever else set it. |
| 3 | What TesseraQL passes the driver | Nothing. The only data-source property any pool sets is DuckDB's (`DuckDbDatasources.java:323`). A parameter in the JDBC URL overrides the same property passed alongside it (`Driver.parseURL`, checked), so anything a `jdbcUrl` declares wins over what TesseraQL adds. |
| 4 | Statement timeouts | A statement's bound is JDBC `setQueryTimeout`, 30 s by default (`tesseraql.sql.timeoutSeconds`, `SqlStatement.java:50`). The driver enforces it by opening a second connection to send a cancel request. When the database host is gone, the cancel cannot reach it (`cancelSignalTimeout`), and the original read goes on waiting. The stores that keep framework state (sessions, the outbox, the job repository, transfers) issue their statements with no timeout. |
| 5 | A vanished database host, from TesseraQL's side | The host crashes, or the network between drops packets without a word, while a statement runs. The thread reading the socket then waits forever if the host had acknowledged the request, because there is no keepalive and no socket timeout. If it had not, it waits until Linux gives up retransmitting (`tcp_retries2`, about 15 minutes). Meanwhile the request holds its admission permit and the pool its connection. **A pool that meets a few such incidents shrinks until the process restarts.** |
| 6 | A vanished TesseraQL node, from the server's side | A node that dies without closing its pools (a `SIGKILL`, power loss, a partition) leaves each backend it held running until the server notices. With the operating system's keepalive defaults (Linux: two hours idle, then nine probes 75 s apart), that takes more than two hours. Each backend keeps a connection slot, and one that was inside a transaction keeps its locks. A graceful stop closes every pool (`TesseraqlRuntime.close`), so nothing is left behind then. |
| 7 | Nothing says whose a connection is | `application_name` is the driver's "PostgreSQL JDBC Driver" for every pool of every application. So `pg_stat_activity` cannot say which application, pool or role a backend serves, and an operator cannot pick out a dead node's leftovers to end them. PostgreSQL takes `application_name` as printable ASCII of at most 63 bytes and replaces anything else. An application's name may be Unicode ([router-unicode-names.md](router-unicode-names.md)). |
| 8 | Keepalive timings per socket | The JDK's `jdk.net.ExtendedSocketOptions` `TCP_KEEPIDLE`, `TCP_KEEPINTERVAL` and `TCP_KEEPCOUNT` set them on one socket. Checked on JDK 25 on Linux, they can be set before the socket connects. The driver creates its socket unconnected through the `SocketFactory` its `socketFactory` property names, then connects it with `connectTimeout` (`PGStream.createSocket`). It loads that class driver-first, trying a `Properties`, a `String` and a no-argument constructor (`ObjectFactory`). The jlinked images already carry `jdk.net` (`jpackage.yml`, held by `check-image-modules.sh`). |
| 9 | Where connections are opened | Every runtime and host pool goes through `DataSources`: an application's pools, main's role pools, the tenant pools, the stack framework pool and the migration pools. The CLI's and the Maven plugin's one-shot commands go through core's `DriverManagerDataSource`. Among them is `tesseraql job run`, which an external scheduler may keep running for hours (`JobCommand.java:426`). |

## The decisions

### 1 — Every PostgreSQL connection says whose it is

When a URL is `jdbc:postgresql:` and declares no `ApplicationName`, TesseraQL sets one:

| Opened by | `application_name` |
| --- | --- |
| An application's pool | `tesseraql/<app>/<pool>`, where `<pool>` is the HikariCP pool name without its `tesseraql-` prefix: `main`, `main-jobs`, `main-transfers`, `tenant-acme`, `tenant-acme-jobs`, or a named datasource's name |
| A pool the stack owns | `tesseraql/<pool>`: `tesseraql/stack-framework`, `tesseraql/stack-framework-migration` |
| `tesseraql job run` | `tesseraql/<app>/job-run` |
| Any other CLI or Maven plugin command | `tesseraql/tool` |

- **ASCII, and at most 63 bytes.** A character outside printable ASCII is percent-encoded as
  UTF-8, so `受注` arrives as `%E5%8F%97%E6%B3%A8`. That is the same wire form the front door
  routes the name by. A longer value is cut at 63 bytes, never inside an escape. An application
  name cannot contain `/`, so the segments stay readable.
- **A URL's own `ApplicationName` wins** (row 3).
- **What it buys.** `select application_name, client_addr, state, state_change from
  pg_stat_activity where application_name like 'tesseraql/%'` lists what each node holds. A dead
  node's leftovers are the rows from its address.

### 2 — A vanished peer is noticed in about a minute

For a `jdbc:postgresql:` URL, TesseraQL sets `tcpKeepAlive=true` and a `socketFactory` of its own,
`io.tesseraql.core.jdbc.KeepaliveSocketFactory`, unless the URL declares them. The factory gives
each socket `TCP_KEEPIDLE` 30 s, `TCP_KEEPINTERVAL` 10 s and `TCP_KEEPCOUNT` 3. After 30 s with
no traffic, the kernel probes the peer. If three probes 10 s apart go unanswered, it resets the
socket. The blocked read then throws, HikariCP evicts the connection, and the request fails with
an error instead of waiting forever.

- **A long statement is never cut.** The database host's kernel answers the probes while the
  backend computes, so a statement that is long and silent but alive runs to its own bound. A
  job's aggregation or a large sort before the first row are examples. Only a peer that has gone
  stops answering.
- **Idle and in use alike.** HikariCP's own `keepaliveTime` stays. The probes also keep a NAT or
  load-balancer mapping alive between its pings.
- **Where the JDK cannot set the timings on the platform** (the socket does not list the three
  options as supported), the factory turns keepalive on and the operating system's timings apply.
  A warning names the platform once.
- **A URL that names its own `socketFactory`,** a cloud provider's connector for example, keeps
  it. `tcpKeepAlive=true` still applies, with the operating system's timings. A URL can also say
  `tcpKeepAlive=false`.
- **The timings are TesseraQL's, not keys.** 30 s, 10 s and 3 probes give about a minute to
  detection, and cost one probe per connection per idle half-minute. The URL is the escape
  hatch.
- **The limit.** Data the client sent that the host never acknowledged is governed by TCP
  retransmission, not keepalive: up to about 15 minutes on Linux. Java exposes no
  `TCP_USER_TIMEOUT` to bound it (decision 4).

### 3 — The server's side is documented, not set

[deployment.md](deployment.md) gains a section on dead connections, with the server settings
that end what a vanished node leaves behind:

| Setting | What it does | A starting value |
| --- | --- | --- |
| `tcp_keepalives_idle`, `tcp_keepalives_interval`, `tcp_keepalives_count` | The server probes an idle client and ends the backend of one that is gone, freeing its slot and its locks | 60 s, 10 s, 6: about two minutes |
| `idle_in_transaction_session_timeout` | Ends a session that sits inside a transaction doing nothing. TesseraQL does not idle inside a transaction, so any value longer than the longest gap between two statements of one transaction is safe | 10 min |
| `client_connection_check_interval` | PostgreSQL 14 and later, on Linux: a query whose client has gone is cancelled rather than run to completion | 10 s |

It also gives the `pg_stat_activity` query from decision 1 and `pg_terminate_backend`. Behind a
pooler such as PgBouncer, both keepalives run between their own two ends, and the pooler has its
own settings. TesseraQL does not set these per session: they are the database administrator's
parameters, and a managed database may refuse them.

### 4 — What this design refuses, each with its trigger

| Refused | Why | Trigger |
| --- | --- | --- |
| A network timeout per statement, or a default `socketTimeout` | It cuts a statement that is silent but alive at a fixed number. A per-statement timeout would have to know every statement's bound, including the stores' untimed ones. Keepalive tells silent from gone | Hangs observed on a platform where per-socket keepalive is unavailable |
| Bounding unacknowledged data (`TCP_USER_TIMEOUT`) | Java exposes no such option | A JDK that exposes it |
| Setting the server's parameters per session (`options=-c ...`) | They are the database administrator's, and would override theirs | A deployment with no access to the database's configuration asking for it |
| The same for other databases' drivers | Each has its own properties for both, and the driver the distributions bundle is PostgreSQL's | A deployment on another database reporting a hang or unidentifiable sessions |
| Leak detection on by default | A debugging aid whose log volume is the operator's decision ([deployment.md](deployment.md)) | None |
| The node's identity in `application_name` | `client_addr` identifies it, and the 63 bytes are better spent on the application and the pool | A pooler hiding `client_addr` in a deployment that needs it |

### 5 — Docs, CHANGELOG

- **S1:** [deployment.md](deployment.md)'s connection-pool section names the `application_name`
  format. CHANGELOG under Added.
- **S2:** [deployment.md](deployment.md) gains the dead-connections section (decision 3), and its
  pool-keys table says what `keepaliveTimeMillis` covers and what the TCP probes cover. CHANGELOG
  under Added.

## What this breaks

1.0 has not shipped, so no migration steps follow. This records what changes and why.

- **`pg_stat_activity` shows `tesseraql/...` where it showed "PostgreSQL JDBC Driver".** A
  monitoring query that filtered on the old name changes with it.
- **A statement whose database host vanished now fails in about a minute,** where it waited
  forever.
- **Each idle connection exchanges a TCP probe every 30 s.**

## The slices

### S1 — whose connection (S)

Decision 1.

- **Tests:**
  - Against Testcontainers PostgreSQL, `pg_stat_activity` shows `tesseraql/<app>/main` for a
    runtime's pool, and the role, tenant and stack pools' names.
  - A URL's own `ApplicationName` wins.
  - `DriverManagerDataSource` labels a job run and a tool.
  - A unit test covers the encoding: a non-ASCII name is percent-encoded, and a long one is cut
    at 63 bytes without splitting an escape.
  - A revert probe that stops setting the property turns the integration test red.
- **Docs:** as decision 5 lists for S1.

### S2 — a vanished peer (M)

Decisions 2 and 3.

- **Tests:**
  - The factory's sockets carry the three timings where the platform supports them, and
    keepalive alone where it does not.
  - A pooled connection's own socket, read through the driver, has keepalive on with a 30 s idle
    time. So does a `DriverManagerDataSource` connection.
  - A URL naming its own `socketFactory` keeps it.
  - A revert probe that drops the factory turns the pooled-socket assertion red.
  - A vanished host is not simulated. In this environment the test's peer is Docker's userland
    proxy, whose kernel answers the probes, so the test holds the configuration and the kernel
    holds the behaviour.
- **Docs:** as decision 5 lists for S2.
