# Scaffolded-config consumer audit

> **Status: design, with the initial audit executed (2026-07-24).** This document defines the
> guard that makes "emitted but never read" configuration unrepresentable, and records the
> audit that motivated it. Two prior instances shipped and were caught late — the kind-keyed
> `security.defaults` ([route-defaults.md](route-defaults.md)) and
> `tesseraql.camel.components` ([component-guard.md](component-guard.md)); the audit below
> found **six more**.

The failure class: `tesseraql new` writes a config key into every application, the docs and
comments describe it as meaningful, and no framework code reads it. The user believes they
configured something — a security posture, a pool size, an environment profile — and the
runtime silently holds a different one. For security keys that is a false sense of protection;
for operational keys it is a debugging trap.

## The audit (every key the scaffolder emits, vs. its consumer)

Consumed and healthy (17 paths): `server.port`, `db.main.url/username/password` (via
placeholder into `datasources.main.*`), `app.name`, `app.home` (Spring embedding path),
`datasources.main.jdbcUrl/username/password`, `identity.defaultRealm`,
`identity.realms.local.type/datasource`, `studio.enabled/readOnly`,
`security.defaults.routes`, `security.responseHeaders`, `security.jwt.*` (4),
`security.policies`.

**Unconsumed** — nothing in any `src/main` tree reads them:

| Key | What the user reasonably believes | Reality | Disposition |
| --- | --- | --- | --- |
| `db.main.maximumPoolSize` | "I sized the connection pool" | `DataSources` reads `datasources.main.maximumPoolSize`, but the scaffold never maps the `db.main` value through — the `10` is dead and the pool runs on driver defaults | **Wire** (one mapping line in the scaffolded `datasources` block) |
| `tesseraql.runtime.profile` (`${TESSERAQL_PROFILE:local}`) | "this switches my environment profile" | The real mechanism is `TESSERAQL_ENV`/`tesseraql.env` + `config/env/<profile>.yml` (`ManifestLoader.activeProfile`); `TESSERAQL_PROFILE` is read by nothing — an actively misleading twin | **Retire** from the scaffold; the emitted comment points at the `TESSERAQL_ENV` overlays instead |
| `tesseraql.camel.components.allowed/denied` | "dangerous components are locked out" | No consumer | **Wire** — [component-guard.md](component-guard.md) |
| `tesseraql.app.work` (`${TESSERAQL_WORK_HOME:…}`) | "I can relocate the work dir" | Every consumer hardcodes `home/work`; neither the key nor the env var is read | **Wire** (the work-dir resolution honors it; the `AppConfig` javadoc already documents the placeholder as a contract) |
| `tesseraql.runtime.engine: camel` | engine selection | Camel is the only engine, hardwired | **Retire** from the scaffold |
| `tesseraql.java.baseline` / `.compatibility` | toolchain declaration something checks | Read by no goal in the Maven plugin or CLI | **Retire** (the wrapper POM already carries the toolchain truth) |
| `tesseraql.datasources.main.type: hikari` | datasource kind selection | Behavior keys off `dialect`/`jdbcUrl` (`DuckDbDatasources.isDuckDb`); `type` is inert | **Retire** from the scaffold |

Dispositions follow one rule: **wire it when the promise is worth keeping, retire it outright
when it is not** (pre-1.0, no shims) — never leave a key that promises and does nothing.

## The guard: a scaffold⇄consumer drift test

The audit must not be a one-time cleanup. The mechanism mirrors the
`AppLinter.knownAuthModes()` drift-test pattern (one source of truth, everything else tested
against it):

1. **A consumer registry in code.** `ScaffoldedConfigKeys` maps every key path the scaffold
   templates emit to its consuming class:
   `"tesseraql.security.jwt.secret" → SecurityConfigFactory.class`. Placeholder-transitive
   keys (`db.main.url`) register the key they feed.
2. **The drift test parses the actual templates.** A test in the scaffolder's module renders
   `AppScaffolder`'s config templates, walks every leaf key path, and asserts each is present
   in the registry — a newly scaffolded key without a declared consumer fails the build with
   the message "wire it or don't emit it".
3. **The registry is honest, not decorative.** A second assertion samples each registered
   consumer class for a literal reference to its path segment, so the registry cannot rot into
   the same lie it guards against.

The registry is also where the [component guard](component-guard.md) and future config
features declare themselves, so the table above never needs to be re-derived by hand.

## Out of scope

- **The reverse direction** — consumed keys the scaffold does not emit
  (`http.outbound.allowedHosts`, `notifications.*`, `workflow.mode`, `i18n.*`, …). Those are
  documented feature opt-ins, not promises the scaffold made; the Studio config viewer and the
  cookbook own their discoverability.
- **Non-config scaffold output** — route/template emission is covered by the dogfood
  byte-for-byte test already.

## Open questions

1. Should `TESSERAQL_PROFILE` be aliased to `TESSERAQL_ENV` for one release instead of
   retired, given an unknown number of external apps may have copied the scaffolded comment?
   Leaning no — pre-1.0, and the variable never did anything, so an alias would be the first
   version that does.
2. Does `app.work` wiring extend to the CLI commands that hardcode `home/work`
   (package/test/generate/coverage), or only the runtime? Leaning: everywhere, in one shared
   resolver — a half-honored relocation key is another instance of the class this document
   exists to end.
