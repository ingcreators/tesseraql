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
