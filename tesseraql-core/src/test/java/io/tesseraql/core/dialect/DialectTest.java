package io.tesseraql.core.dialect;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DialectTest {

    @Test
    void inferredFromJdbcUrl() {
        assertThat(Dialect.fromJdbcUrl("jdbc:postgresql://h/db")).contains(Dialect.POSTGRES);
        assertThat(Dialect.fromJdbcUrl("jdbc:mysql://h/db")).contains(Dialect.MYSQL);
        assertThat(Dialect.fromJdbcUrl("jdbc:mariadb://h/db")).contains(Dialect.MYSQL);
        assertThat(Dialect.fromJdbcUrl("jdbc:unknown://h")).isEmpty();
    }

    @Test
    void resolvedById() {
        assertThat(Dialect.fromId("mysql")).contains(Dialect.MYSQL);
        assertThat(Dialect.fromId("nope")).isEmpty();
    }

    @Test
    void generatedKeyRetrievalPerDialect() {
        // PostgreSQL and Oracle honor requested key columns (RETURNING / RETURNING INTO);
        // MySQL and SQL Server only return the auto-increment / identity value.
        assertThat(Dialect.POSTGRES.capabilities().generatedKeyColumns()).isTrue();
        assertThat(Dialect.ORACLE.capabilities().generatedKeyColumns()).isTrue();
        assertThat(Dialect.MYSQL.capabilities().generatedKeyColumns()).isFalse();
        assertThat(Dialect.SQLSERVER.capabilities().generatedKeyColumns()).isFalse();
    }

    @Test
    void prefersDialectSpecificSqlFile(@TempDir Path dir) throws Exception {
        Path base = dir.resolve("search.sql");
        Path mysql = dir.resolve("search.mysql.sql");
        Files.writeString(base, "select 1");
        Files.writeString(mysql, "select 2");

        assertThat(DialectSqlResolver.resolve(base, "mysql")).isEqualTo(mysql);
        assertThat(DialectSqlResolver.resolve(base, "postgres")).isEqualTo(base); // no variant
        assertThat(DialectSqlResolver.resolve(base, null)).isEqualTo(base);
    }
}
