# Build

```bash
mvn -B -ntp verify
```

TesseraQL 1.x build policy:

- baseline: Java 21
- compatibility: Java 25
- Maven multi-module
- Maven plugin first
- Gradle plugin later

## Test reports and coverage

The `tesseraql:test` and `tesseraql:coverage` goals run the app's declarative suites and write
reports under `target/tesseraql-reports/`:

- `junit/TEST-tesseraql.xml`, `tesseraql-result.json`, `index.html` — test results
  (JUnit XML / JSON / HTML)
- `allure-results/*-result.json` — Allure 2 result files (`allure generate` ready)
- `coverage/sql-coverage.json` — SQL line/branch coverage plus the item-coverage kinds
- `coverage/cobertura.xml`, `coverage/sonarqube.xml` — Cobertura and SonarQube generic
  coverage exports for CI publishers
- `coverage/coverage.sarif` — coverage gaps as SARIF for code scanning

Coverage kinds beyond SQL line/branch (design ch. 14): `assertion` (cases that assert),
`iam-contract` (standard identity contracts exercised), `route` / `security` (manifest routes —
and the subset declaring `security:` — whose SQL artifacts the suites exercise), `saml` (the
identity contracts the SAML user-link login path executes, when linking is enabled), and `scim`
(the contract SQL files wired via `tesseraql.scim.*`). Kinds with nothing declared report 1.0.

The `coverage` goal gates on `coverage.thresholds.*` percentages from the app config (or the
`-Dtesseraql.sqlLineThreshold` / `-Dtesseraql.sqlBranchThreshold` defaults):

```yaml
coverage:
  thresholds:
    sqlLine: 80
    sqlBranch: 80
    route: 90        # any kind name gates that kind; absent kinds are not gated
    security: 100
```

## Dialect integration tests

PostgreSQL and MySQL integration tests run on every `./mvnw verify`. The Oracle and SQL Server
plan-guard tests use large container images and are opt-in:

```bash
# Query Plan Guard against real Oracle / SQL Server
./mvnw -pl tesseraql-coverage-core test -Dtesseraql.dialect.its=true \
  -Dtest='OraclePlanGuardIntegrationTest,SqlServerPlanGuardIntegrationTest'

# Full runtime portability (framework migrations, stores, identity pack, a served route)
./mvnw -pl tesseraql-camel-runtime test -Dtesseraql.dialect.its=true \
  -Dtest='OraclePortabilityIntegrationTest,SqlServerPortabilityIntegrationTest'
```
