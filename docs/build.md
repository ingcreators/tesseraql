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
./mvnw -pl tesseraql-coverage-core test -Dtesseraql.dialect.its=true \
  -Dtest='OraclePlanGuardIntegrationTest,SqlServerPlanGuardIntegrationTest'
```
