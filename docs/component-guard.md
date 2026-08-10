# Camel component guard

> **Status: shipped.** `ComponentPolicy` (baseline + narrowing config) is enforced at
> registration time by `ComponentGuard` via the context lifecycle strategy; a refused component
> fails boot with `TQL-SEC-4138`, re-allow attempts are linted (`TQL-SEC-4139`), and the
> framework floor is the `FRAMEWORK_FLOOR` set plus the `tesseraql-*` namespace, drift-checked
> by the runtime integration suites (the guard immediately caught the floor missing
> `tesseraql-iam` during implementation). The `bean` open question resolved empirically: the
> full IT suite passes with it baseline-denied. A poll-triggered job's declared `transport:` (sftp/ftp/…) is the app's
> structured component intent, so a narrowing `allowed:` list never restates it — the deny
> sets still win, so a job cannot resurrect a baseline-denied component (the sftp poll suite
> caught this interplay during implementation). The scaffold now emits guidance instead of the
> dead lists. User-facing docs: security-hardening.md "Camel component guard".

The scaffolder writes an allow/deny list into every new app:

```yaml
tesseraql:
  camel:
    components:
      allowed: [direct, platform-http, timer, quartz, file, log,
                tesseraql-sql, tesseraql-auth, tesseraql-html, smtp]
      denied: [exec, script, groovy, class]
```

Nothing consumes it. A security control that exists only as YAML is worse than none: it
documents a posture the runtime does not hold. Pre-1.0 the choice is wire it or delete it
outright — this design wires it, because the surface it guards is real.

## Threat model: who could reach a component?

Application YAML cannot. Recipes construct every endpoint URI (`direct:`, `timer:`, `quartz:`,
`file:`, `platform-http:`, the `tesseraql-*` components); no route, job, notification, or
consumer document carries a raw Camel URI, and AGENTS rule 4 keeps raw Camel DSL out of the
standard surface. The guard is **defense in depth** against the paths that bypass the YAML
surface:

- **Classpath drift** — a dependency upgrade or a new opt-in module puts `camel-exec` (or any
  scripting component) on the classpath; Camel's auto-discovery happily registers it.
- **Plugins and modules** — a plugin JAR can register components directly with the
  CamelContext; [admission](admission.md) reviews an app's YAML tree, not a JAR's classpath.
- **Framework regressions** — a future recipe or connector wiring a component the posture never
  intended.

## The design

1. **Built-in denied baseline, config or not.** `exec`, `script`, `groovy`, `class`,
   `language`, and `bean` (invocation-by-name) are refused **by default** — an app with no
   `camel.components` config still holds the posture. The baseline lives in code
   (`ComponentGuard.BASELINE_DENIED`), exposed like `AppLinter.knownAuthModes()` so docs and
   the shipped config comment stay drift-tested against one source.
2. **Startup guard, resolution-time enforcement.** The runtime installs a guarding component
   resolver on the CamelContext: resolving a denied component — or, when `allowed:` is
   declared, any component outside it — fails app boot with a coded error (`TQL-SEC-*`,
   assigned from the registry). Resolution-time (not registry-scan-time) catches lazily added
   components, including a plugin registering one mid-boot.
3. **Config narrows, never widens.** `denied:` entries add to the baseline; an `allowed:` list
   restricts further. Config cannot re-allow a baseline-denied component — the guard warns and
   ignores the attempt (`TQL-SEC-*` lint, warning), mirroring how a route cannot loosen a
   response-header default silently.
4. **The framework's own set is allowed implicitly.** The components the recipes require
   (`direct`, `platform-http`, `tesseraql-*`, …) are the guard's built-in floor; an `allowed:`
   list that omits them does not brick the app, it is linted (`TQL-SEC-*`, warning) and the
   floor applies. Exposed as `ComponentGuard.frameworkComponents()` for the same drift-testing.
5. **Scaffolder emission shrinks.** With a secure baseline in code, the scaffolded config no
   longer needs to restate the deny list; it keeps a commented pointer for the day an app needs
   to *narrow* `allowed:`. This follows the campaign's arc: gallery and bundled apps stopped
   restating headers and auth the framework can default — the deny list is the same shape one
   level down.

## Lint

Same registry discipline as the rest of the SEC family (numbers assigned at implementation):
unknown component name in either list (warning — typo'd security config must be visible),
config attempting to re-allow a baseline-denied component (warning), `allowed:` omitting a
framework-required component (warning, floor applies).

## Out of scope

- Per-route or per-app component grants for the framework's own surfaces. They run on the
  application's CamelContext; the guard is context-wide. A hosted app needing a component the host denies is
  a deployment conversation, not a config merge.
- Endpoint-parameter policing (e.g. `file:` path constraints). The duckdb/file-transfer work
  already fences the data-path surfaces; this guard is about which components exist at all.

## Open questions

1. Should the boot failure list the resolving call site (route id / plugin name) when known?
   Leaning yes — a denied resolution with no provenance is hard to act on.
2. ~~Is `bean` too disruptive for the baseline (framework internals may resolve it)?~~
   **Closed: no** (2026-08-08, the contract-sweep decision-closure wave): a repo sweep found no
   framework-internal `bean:` endpoint resolution (registry binds do not pass the guard), so
   `bean` stays in the baseline-denied set as shipped.
