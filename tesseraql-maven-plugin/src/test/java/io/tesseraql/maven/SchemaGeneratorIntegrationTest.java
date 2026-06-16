package io.tesseraql.maven;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.report.docs.SchemaDoc;
import io.tesseraql.report.docs.SchemaGenerator;
import io.tesseraql.yaml.scaffold.CatalogSchema;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Integration test for the schema layer (documentation portal v3): introspects a real PostgreSQL
 * catalog and asserts {@link SchemaGenerator} captures the tables, views, columns, primary keys,
 * foreign keys, and unique indexes, sorted deterministically.
 */
@Testcontainers
class SchemaGeneratorIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static DataSource dataSource;

    @BeforeAll
    static void seed() throws Exception {
        dataSource = dataSource();
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("create table customers (id bigserial primary key, "
                    + "email varchar(320) not null unique, name varchar(200))");
            statement.execute("create table orders (id bigserial primary key, "
                    + "customer_id bigint not null references customers(id), "
                    + "total numeric(12,2) default 0)");
            statement.execute("create view active_customers as "
                    + "select id, email from customers where name is not null");
        }
    }

    @Test
    void introspectsTablesColumnsKeysForeignKeysAndIndexes() {
        SchemaDoc schema = new SchemaGenerator()
                .generate(Map.of("main", dataSource), "2026-06-15T12:00:00Z");

        CatalogSchema catalog = schema.datasources().get("main");
        assertThat(catalog).isNotNull();
        // Tables sort by name; the view is captured alongside the tables.
        assertThat(catalog.tables()).extracting(CatalogSchema.Table::name)
                .containsExactly("active_customers", "customers", "orders");

        CatalogSchema.Table customers = table(catalog, "customers");
        assertThat(customers.type()).isEqualTo("TABLE");
        assertThat(customers.primaryKey()).containsExactly("id");
        assertThat(customers.columns()).extracting(CatalogSchema.Column::name)
                .containsExactly("id", "email", "name");
        assertThat(column(customers, "id").autoincrement()).isTrue();
        assertThat(column(customers, "name").nullable()).isTrue();
        // The PK-backing index is dropped; the email UNIQUE constraint surfaces as a unique index.
        assertThat(customers.uniqueIndexes())
                .anySatisfy(index -> assertThat(index.columns()).containsExactly("email"))
                .noneSatisfy(index -> assertThat(index.columns()).containsExactly("id"));

        CatalogSchema.Table orders = table(catalog, "orders");
        assertThat(orders.foreignKeys()).hasSize(1);
        CatalogSchema.ForeignKey fk = orders.foreignKeys().get(0);
        assertThat(fk.columns()).containsExactly("customer_id");
        assertThat(fk.refTable()).isEqualTo("customers");
        assertThat(fk.refColumns()).containsExactly("id");

        assertThat(table(catalog, "active_customers").type()).isEqualTo("VIEW");

        assertThat(new SchemaGenerator().toJson(schema))
                .contains("\"customers\"").contains("\"refTable\"");
    }

    private static CatalogSchema.Table table(CatalogSchema catalog, String name) {
        return catalog.tables().stream().filter(table -> table.name().equals(name))
                .findFirst().orElseThrow();
    }

    private static CatalogSchema.Column column(CatalogSchema.Table table, String name) {
        return table.columns().stream().filter(column -> column.name().equals(name))
                .findFirst().orElseThrow();
    }

    private static DataSource dataSource() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(POSTGRES.getJdbcUrl());
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        return ds;
    }
}
