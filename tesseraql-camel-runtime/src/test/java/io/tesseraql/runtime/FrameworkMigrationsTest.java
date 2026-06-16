package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class FrameworkMigrationsTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0");

    private static DataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return dataSource;
    }

    @Test
    void migratesEveryFrameworkComponentWithItsOwnHistoryAndStaysIdempotent() throws Exception {
        DataSource dataSource = dataSource();
        FrameworkMigrations.migrate(dataSource);
        FrameworkMigrations.migrate(dataSource);

        assertThat(tables(dataSource))
                .contains("tql_session", "tql_job_execution", "tql_step_execution",
                        "tql_job_claim", "tql_idempotency_record", "tql_outbox_event",
                        "tql_doc_sequence",
                        "tql_schema_history__security", "tql_schema_history__operations");
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet history = statement.executeQuery(
                        "select count(*) from tql_schema_history__operations"
                                + " where version in ('1', '2') and success")) {
            history.next();
            // V1 framework tables and the V2 document-sequence migration, each applied once.
            assertThat(history.getInt(1)).isEqualTo(2);
        }
    }

    @Test
    void migratesOnMySqlWithTheCommonScriptsOnly() throws Exception {
        com.mysql.cj.jdbc.MysqlDataSource dataSource = new com.mysql.cj.jdbc.MysqlDataSource();
        dataSource.setUrl(MYSQL.getJdbcUrl());
        dataSource.setUser(MYSQL.getUsername());
        dataSource.setPassword(MYSQL.getPassword());

        FrameworkMigrations.migrate(dataSource);
        FrameworkMigrations.migrate(dataSource);

        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet history = statement.executeQuery(
                        "select count(*) from tql_schema_history__operations"
                                + " where version = '1' and success")) {
            history.next();
            assertThat(history.getInt(1)).isEqualTo(1);
        }
    }

    private static List<String> tables(DataSource dataSource) throws Exception {
        List<String> names = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet tables = statement.executeQuery(
                        "select table_name from information_schema.tables"
                                + " where table_schema = 'public'")) {
            while (tables.next()) {
                names.add(tables.getString(1));
            }
        }
        return names;
    }
}
