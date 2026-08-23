package io.tesseraql.runtime;

import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The bundled managed Group contract set against PostgreSQL (docs/contract-sql-execution.md
 * structural decision 6, slice 6) — the ungated half of the portability claim; MySQL, Oracle and
 * SQL Server run the same {@link DialectScimGroupChecks} in the gated dialect suites.
 */
@Testcontainers
class ScimGroupPackIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Test
    void bundledGroupSetRoundTripsOnPostgres() throws Exception {
        org.postgresql.ds.PGSimpleDataSource dataSource = new org.postgresql.ds.PGSimpleDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());

        DialectScimGroupChecks.bundledGroupSetRoundTrip(dataSource, "postgres");
    }
}
