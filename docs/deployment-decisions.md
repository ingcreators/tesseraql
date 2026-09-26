# Deployment decisions: a new application cannot reach production on its development secret, declares its time and language, and its production profile turns operations on

> **Status: designed 2026-09-26, against main `5126f20c6` (0.19.0-SNAPSHOT).** The maintainer
> asked which settings an application's owner should decide but the generated YAML does not
> show. Every one of the 276 keys the framework reads (the configuration reference) was compared
> with what `tesseraql new` writes, and each candidate's default was read in the code (rows 1-9).
> Three groups came out. The development JWT secret reaches production unguarded. The time zone
> and the language are the JVM's unless declared, and the container image's JVM runs in UTC. And
> retention, metrics and alerting are all off unless declared. The maintainer chose every
> recommendation, and asked in the same breath for `main`'s `minimumIdle` to start at 1. Asked
> where, they chose the development configuration only (decision 4).
>
> There are three slices:
>
> **S1 (the development secret).** A generated production or staging profile takes the JWT
> secret from `JWT_SECRET` with no fallback. Under any named profile, the runtime refuses the
> scaffold's published development secret. **Shipped, #1474**, as designed. The literal is now
> `AppScaffolder.DEVELOPMENT_JWT_SECRET`, templated into the base configuration and read by the
> refusal, so the two cannot drift. The generated application boots under `prod` with the secret
> supplied, and refuses with `TQL-YAML-1101` without it. A revert probe that dropped the refusal's
> call turned its wiring test red.
>
> **S2 (time and language).** The generated configuration declares the export zone and locale,
> the zone access conditions are judged in, and the default language. A lint warns when an export
> formats dates in the JVM's zone.
>
> **S3 (operations, and the development pool).** The generated production and staging profiles
> turn metrics and the retention sweep on and name the choices left to the owner. The base
> configuration's `main` keeps one idle connection, and the profiles keep theirs fixed.

## What is true today

| # | Subject | Finding |
| --- | --- | --- |
| 1 | The JWT secret | The generated `tesseraql.yml` declares `secret: ${JWT_SECRET:dev-only-secret-change-me-in-production}` (`AppScaffolder`). The generated `prod.yml` and `staging.yml` do not override it. No lint (`JwtConfigRules`) and no boot check reads the value. So a production deployment that forgets `JWT_SECRET` validates bearer tokens with a secret published in the framework's own template, and anyone can mint a token its policies accept. The eight example applications carry the same fallback, and 56 Java sources, most of them tests, use the literal. |
| 2 | Placeholders | A placeholder resolves from the environment, then from a configuration key of the same name, then from its fallback. With none of these, the key's reader refuses with `TQL-YAML-1101`, naming the placeholder (`AppConfig.resolvePlaceholder`). |
| 3 | Named profiles | `TESSERAQL_ENV` (or `-Dtesseraql.env`) selects `config/env/<profile>.yml`, and the Helm chart sets it from `profile` (`_helpers.tpl`). No test and no example runs under one, except the generated application's own profile test. The kind proof's probe application runs under `kind` and declares no JWT settings. |
| 4 | Export dates | `tesseraql.files.timezone` and `tesseraql.files.locale` default to nothing (`FileDefaults`), and a typed `date` or `datetime` column is then rendered in `ZoneId.systemDefault()` with `Locale.getDefault()` (`ColumnValues`). The zone applies only to typed columns (`ExportDeclarations`, `COLUMN_TYPES`), and nothing warns when it is left to the JVM. |
| 5 | Access-condition hours | A role grant's `hours` condition (docs/access-governance.md structural decision 8) is judged in `tesseraql.security.conditions.zone`, and absent, in the JVM's (`ConditionZone`). The conditions are grant data an administrator sets in IAM Admin, not YAML, so no lint can see whether an application uses them. |
| 6 | The container's zone | The runtime images start from `eclipse-temurin:25-jre` and set no `TZ` (`deploy/Dockerfile*`), so the JVM's zone is UTC. A developer's machine is usually local time. **The same export renders differently in development and in production.** |
| 7 | Language | `tesseraql.i18n.defaultLocale` defaults to `en` (`TesseraqlRuntime`, `I18nRules`). |
| 8 | Operations off by default | `tesseraql.metrics.enabled` (false), `tesseraql.retention.sweep` (unset: the outbox, job history and attachments are never swept), `tesseraql.transfers.retentionDays` (0: produced files are kept forever), `tesseraql.notifications.alerts.channel` (unset: no operations alert pages anyone), `tesseraql.audit.routes.enabled` and `tesseraql.logging.accessLog` (both false). The inbox (90 days) and consumed poll files (30 days) have retention defaults. The scrape requires a bearer holding the `ops.metrics.view` policy unless `metrics.unauthenticated` is set. |
| 9 | `main`'s pool | The generated base configuration sizes `main` at `${db.main.maximumPoolSize:10}` with no `minimumIdle`, so it holds 10 connections from boot. The generated profiles override `maximumPoolSize` and `connectionTimeoutMillis`, but not `minimumIdle`, so a base value would reach production through the merge. capacity-defaults.md decision 9 refused lowering `minimumIdle` as a framework default, for the burst it costs. |

## The decisions

### 1 — Production cannot start on the development secret

- **The generated profiles take the secret with no fallback.** `prod.yml` and `staging.yml`
  declare `tesseraql.security.jwt.secret: ${JWT_SECRET}`. A profile started without `JWT_SECRET`
  refuses with `TQL-YAML-1101`, naming it (row 2). The base configuration keeps its fallback, so
  `tesseraql dev` needs nothing.
- **The published secret is refused under any named profile.** When a profile is active and
  `tesseraql.security.jwt.secret` resolves to `dev-only-secret-change-me-in-production`, the
  runtime refuses to start with `TQL-SEC-4154`. The message names the key and `JWT_SECRET`. This
  covers the applications generated before this design, whose profiles inherit the fallback. A
  named profile is the one signal every deployment path carries and no development path sets
  (row 3).
- **No lint.** A lint runs without a profile, and there every development configuration carries
  the fallback on purpose.

### 2 — A new application declares its time and language

The generated `tesseraql.yml` declares four keys, with a comment saying why:

```yaml
  # Time and language are this application's to decide. Undeclared, exports and access
  # conditions follow the JVM's zone and locale: the developer's in development and UTC in the
  # container image, so one export would differ between the two. Set your business's, e.g.
  # Asia/Tokyo and ja.
  files:
    timezone: UTC
    locale: en
  security:
    conditions:
      zone: UTC
  i18n:
    defaultLocale: en
```

- **The values are explicit, not detected.** Development and production then agree. The gallery
  application, regenerated byte for byte, stays the same on every machine.
- **A lint warns (`TQL-YAML-1116`)** when an export reaches a column typed `date` or `datetime`
  and neither the export's own `timezone:` nor `tesseraql.files.timezone` is declared. The
  message names the export and says what the JVM's zone is in the image. It applies to routes,
  a list view's exports and a job's export steps, wherever `ExportDeclarations` already judges
  them.
- **Access conditions get no lint,** because the conditions are data (row 5). The declared key
  is the answer for new applications, and the documentation says what an undeclared one means.

### 3 — The production profile turns operations on, and names the owner's choices

The generated `prod.yml` and `staging.yml` gain:

```yaml
  metrics:
    enabled: true              # /_tesseraql/metrics, for a bearer holding ops.metrics.view
  retention:
    sweep: 1h                  # the outbox after 30 days and job history after 90
                               # (tesseraql.retention.outbox / .jobs to change them)
  security:
    policies:
      ops.metrics.view:
        anyOf:
          - role: OPS
  # The owner's to decide: how long produced files are kept, and where alerts go.
  # transfers:
  #   retentionDays: 30
  # notifications:
  #   alerts:
  #     channel: ops-mail      # a channel declared under tesseraql.notifications.channels
```

- **On:** metrics, behind the gate a member's scrape already has, and the sweep, with the
  framework's own retention periods. Neither depends on the business.
- **Named, not set:** file retention and the alert channel depend on the business and on a
  channel that must exist first. The route audit log and the access log stay off, and
  [deployment.md](deployment.md) names them.

### 4 — `main` keeps one idle connection in development, and stays fixed in production

The maintainer's choice, among three: the development configuration only.

- **The base configuration** gives `main` `minimumIdle: 1`. A development stack of several
  applications on one database, embedded or not, then holds one connection per application while
  idle, where it held ten. HikariCP retires the rest after its `idleTimeout` (10 minutes).
- **The generated profiles** declare `minimumIdle: ${db.main.maximumPoolSize:10}`, so production
  and staging stay fixed-size. capacity-defaults.md decision 9's reason, a burst paying for
  connection setup, holds there (row 9).
- **The framework's default is unchanged,** so an application that declares nothing keeps a
  fixed pool.

### 5 — What this design refuses, each with its trigger

| Refused | Why | Trigger |
| --- | --- | --- |
| A lint of the development secret | A lint runs without a profile, where the fallback is intended (decision 1) | None |
| Refusing the development secret outside named profiles (under every `host`) | Tests and examples start hosts on it by design. A named profile is what marks a deployment | A deployment path that runs without a profile |
| Detecting the zone or locale when `new` runs | The generated files would differ by machine, and the gallery is byte-compared | None |
| A lint of access-condition zones | The conditions are grant data, not YAML (row 5) | Conditions declared in YAML |
| Turning the route audit log, the access log or file retention on in the profile | They depend on the business: compliance, log volume, how long users need their files | None |
| `minimumIdle` 1 in production, or as the framework default | The maintainer chose development only | capacity-defaults.md decision 9's trigger |

### 6 — Docs, CHANGELOG, codes

- **S1:** [deployment.md](deployment.md)'s environment-profiles section says the profile takes
  the secret from `JWT_SECRET`. The CHANGELOG entry goes under Changed.
- **S2:** [deployment.md](deployment.md) says what an undeclared zone means, for exports and for
  access conditions. `ScaffoldedConfigKeys` registers the four keys, and the editor schema
  describes them. The CHANGELOG entry goes under Added.
- **S3:** [deployment.md](deployment.md)'s profile section lists what the profile turns on and
  what it leaves to the owner. The CHANGELOG entry goes under Added.
- The error-code reference is regenerated for `TQL-SEC-4154` and `TQL-YAML-1116`.

## What this breaks

1.0 has not shipped, so no migration steps follow. This records what changes and why.

- **An application started under a named profile on the development JWT secret refuses to
  start** (S1). Set `JWT_SECRET` or declare a secret.
- **A newly generated application exports in UTC and English until its owner says otherwise**
  (S2). One generated before keeps the JVM's zone and is warned about typed dates.
- **A generated application's development pool holds one idle connection** (S3). A burst in
  development pays for connection setup.

## The slices

### S1 — the development secret (S)

Decision 1.

- **Tests:**
  - The generated profiles declare `${JWT_SECRET}` with no fallback.
  - The generated application boots under `prod` with `JWT_SECRET` supplied, and refuses with
    `TQL-YAML-1101` naming it without.
  - Under a named profile, a configuration whose secret resolves to the development literal
    refuses with `TQL-SEC-4154`. Without a profile, it boots.
  - A revert probe that drops the refusal turns its test red.
- **Docs:** as decision 6 lists for S1.

### S2 — time and language (M)

Decision 2.

- **Tests:**
  - The skeleton declares the four keys, and the gallery is regenerated.
  - The lint warns for a route export and a job export step with a `datetime` column and no
    zone. It is silent when either zone is declared, and when no column is typed.
  - A revert probe that drops the warning turns its test red.
- **Docs:** as decision 6 lists for S2.

### S3 — operations, and the development pool (S)

Decisions 3 and 4.

- **Tests:**
  - The generated profiles carry the operations block.
  - The generated application boots under `prod` with metrics on: the scrape refuses a request
    with no bearer and answers one holding `OPS`.
  - `main` is fixed at 10 under `prod`, and holds `minimumIdle` 1 without a profile.
  - The gallery is regenerated.
- **Docs:** as decision 6 lists for S3.

## Error codes

| Code | Slice | Meaning |
| --- | --- | --- |
| `TQL-SEC-4154` | S1 | A named profile is active and the JWT secret is the scaffold's published development secret; the runtime refuses to start |
| `TQL-YAML-1116` | S2 | An export formats a `date` or `datetime` column with no declared zone, so the JVM's applies; a lint warning |
