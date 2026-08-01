package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The data browser's datasource dimension (docs/analytics-experience.md track 1): a duckdb
 * datasource lists tables and views across every catalog visible on the connection as
 * qualified {@code catalog.schema.table} names, browse/export address them, and an undeclared
 * datasource is refused. One real DuckDB connection stands in for the pool (each pooled
 * connection is its own in-memory engine, so the fixture must reuse the seeded one).
 */
class StudioDataServiceTest {

    static Connection engine;
    static StudioDataService service;

    @BeforeAll
    static void seedEngine() throws Exception {
        engine = DriverManager.getConnection("jdbc:duckdb:");
        try (Statement statement = engine.createStatement()) {
            // A second in-memory catalog stands in for an attached datasource or lake.
            statement.execute("ATTACH ':memory:' AS lake");
            statement.execute("CREATE TABLE stock (item VARCHAR, qty INT)");
            statement.execute("INSERT INTO stock VALUES ('widget', 3), ('gadget', 7)");
            statement.execute("CREATE VIEW low_stock AS SELECT * FROM stock WHERE qty < 5");
            statement.execute("CREATE TABLE lake.price_summary (category VARCHAR, total INT)");
            statement.execute("INSERT INTO lake.price_summary VALUES ('tools', 42)");
        }
        DataSource dataSource = keepAlive(engine);
        service = new StudioDataService(name -> dataSource, List.of("main", "analytics"),
                true, false, 5, 1000);
    }

    @AfterAll
    static void closeEngine() throws Exception {
        engine.close();
    }

    @Test
    void listsTablesAndViewsAcrossCatalogsQualified() {
        List<String> tables = service.tables("analytics");

        assertThat(tables).contains("memory.main.stock", "memory.main.low_stock",
                "lake.main.price_summary");
        assertThat(tables).noneMatch(table -> table.startsWith("system.")
                || table.startsWith("temp.") || table.contains(".information_schema.")
                || table.contains(".pg_catalog."));
    }

    @Test
    void browsesAQualifiedTableInAnAttachedCatalog() {
        StudioDataService.DataPage page = service.browse("analytics",
                "lake.main.price_summary", 0, null, null, "and", List.of());

        assertThat(page.table()).isEqualTo("lake.main.price_summary");
        assertThat(page.columns()).containsExactly("category", "total");
        assertThat(page.rows()).containsExactly(java.util.Arrays.asList("tools", "42"));
    }

    @Test
    void filtersAndSortsWithBoundValues() {
        StudioDataService.DataPage page = service.browse("analytics", "memory.main.stock", 0,
                "item", "asc", "and",
                List.of(new StudioDataService.FilterCond("qty", "gt", "4")));

        assertThat(page.rows()).containsExactly(java.util.Arrays.asList("gadget", "7"));
    }

    @Test
    void exportsTheQualifiedViewAsCsv() {
        String csv = service.exportCsv("analytics", "memory.main.low_stock", "item", "asc",
                "and", List.of());

        assertThat(csv).startsWith("item,qty").contains("widget,3").doesNotContain("gadget");
    }

    @Test
    void refusesAnUndeclaredDatasourceAndAnUnknownTable() {
        assertThatThrownBy(() -> service.tables("nope"))
                .hasMessageContaining("No such datasource: nope");
        assertThatThrownBy(() -> service.browse("analytics", "memory.main.absent", 0, null,
                null, "and", List.of()))
                .hasMessageContaining("No such table");
        // A qualified name is resolved by membership, never parsed — a crafted "name" that
        // does not appear in the listing is refused before any SQL exists.
        assertThatThrownBy(() -> service.browse("analytics",
                "memory.main.stock; drop table stock", 0, null, null, "and", List.of()))
                .hasMessageContaining("No such table");
    }

    /**
     * Wraps the one seeded connection as a DataSource whose connections ignore close():
     * the service's try-with-resources must not tear down the shared in-memory engine.
     */
    private static DataSource keepAlive(Connection connection) {
        Connection uncloseable = (Connection) Proxy.newProxyInstance(
                StudioDataServiceTest.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("close".equals(method.getName())) {
                        return null;
                    }
                    try {
                        return method.invoke(connection, args);
                    } catch (java.lang.reflect.InvocationTargetException ex) {
                        throw ex.getCause();
                    }
                });
        return (DataSource) Proxy.newProxyInstance(
                StudioDataServiceTest.class.getClassLoader(), new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    if ("getConnection".equals(method.getName())
                            && (args == null || args.length == 0)) {
                        return uncloseable;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
