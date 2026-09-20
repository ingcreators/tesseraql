package io.tesseraql.operations.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * The shared per-table version reader and writer (docs/caching.md decision 5): it raises
 * only the tables a catalog or a held source reads, reads the whole table at most once per
 * interval, and falls back to the TTL — never to a failure — when the row set cannot be
 * reached.
 */
class TableVersionsTest {

    private final FakeJdbc jdbc = new FakeJdbc();
    private final AtomicLong clock = new AtomicLong(1_000_000L);

    private TableVersions versions(String... stamped) {
        return new TableVersions(name -> jdbc.dataSource(), Set.of(stamped), clock::get);
    }

    @Test
    void nothingIsReadOrRaisedUntilTheTableExists() {
        TableVersions versions = versions("orders");
        assertThat(versions.stamped()).isFalse();
        assertThat(versions.versionOf(List.of("orders"))).isZero();
        versions.bump(List.of("orders"));
        assertThat(jdbc.prepared).isEmpty();
    }

    @Test
    void aBumpRaisesOnlyTheTablesThisRuntimeStamps() {
        TableVersions versions = versions("orders", "customers");
        versions.ensureSchema();
        assertThat(versions.stamped()).isTrue();
        versions.bump(List.of("orders", "anything_else"));
        // The update ran for the stamped table and, answering no row, inserted it; the table
        // no catalog and no held source reads got no row at all.
        assertThat(jdbc.prepared).filteredOn(sql -> sql.startsWith("update tql_catalog_version"))
                .hasSize(1);
        assertThat(jdbc.prepared).filteredOn(sql -> sql.startsWith("insert into"
                + " tql_catalog_version")).hasSize(1);
        assertThat(jdbc.bound).containsExactly("orders", "orders");
    }

    @Test
    void theVersionTableIsReadAtMostOncePerInterval() {
        TableVersions versions = versions("orders");
        versions.ensureSchema();
        versions.versionOf(List.of("orders"));
        versions.versionOf(List.of("orders"));
        clock.addAndGet(TableVersions.STAMP_INTERVAL_MILLIS - 1);
        versions.versionOf(List.of("orders"));
        assertThat(jdbc.selects()).as("three reads inside one interval, one query").isEqualTo(1);
        clock.addAndGet(1);
        versions.versionOf(List.of("orders"));
        assertThat(jdbc.selects()).isEqualTo(2);
        // A bump on this node re-reads at once, so its own next check is not interval-old.
        versions.bump(List.of("orders"));
        versions.versionOf(List.of("orders"));
        assertThat(jdbc.selects()).isEqualTo(3);
    }

    @Test
    void aReadThatFailsFallsBackToTheTtlAndTriesAgainNextInterval() {
        TableVersions versions = versions("orders");
        versions.ensureSchema();
        jdbc.failSelects = true;
        assertThat(versions.versionOf(List.of("orders"))).isZero();
        assertThat(versions.versionOf(List.of("orders"))).isZero();
        assertThat(jdbc.selects()).isEqualTo(1);
        clock.addAndGet(TableVersions.STAMP_INTERVAL_MILLIS);
        versions.versionOf(List.of("orders"));
        assertThat(jdbc.selects()).isEqualTo(2);
        assertThat(versions.status()).isEmpty();
    }

    /** A JDBC stack that records what was prepared and bound and answers empty results. */
    private static final class FakeJdbc implements InvocationHandler {

        private final List<String> prepared = new ArrayList<>();
        private final List<String> bound = new ArrayList<>();
        private boolean failSelects;

        private DataSource dataSource() {
            return proxy(DataSource.class);
        }

        private long selects() {
            return prepared.stream().filter(sql -> sql.startsWith("select table_name")).count();
        }

        private <T> T proxy(Class<T> type) {
            return type.cast(Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{type}, this));
        }

        @Override
        public Object invoke(Object instance, Method method, Object[] args) throws SQLException {
            return switch (method.getName()) {
                case "getConnection" -> proxy(Connection.class);
                case "getMetaData" -> proxy(DatabaseMetaData.class);
                case "getDatabaseProductName" -> "PostgreSQL";
                case "createStatement" -> proxy(Statement.class);
                case "prepareStatement" -> prepare(String.valueOf(args[0]));
                case "setString" -> {
                    bound.add(String.valueOf(args[1]));
                    yield null;
                }
                case "executeQuery" -> proxy(ResultSet.class);
                case "next" -> Boolean.FALSE;
                case "toString" -> "fake";
                case "hashCode" -> System.identityHashCode(instance);
                case "equals" -> instance == args[0];
                default -> defaultValue(method.getReturnType());
            };
        }

        private PreparedStatement prepare(String sql) throws SQLException {
            prepared.add(sql);
            if (failSelects && sql.startsWith("select table_name")) {
                throw new SQLException("connection refused", "08001");
            }
            return proxy(PreparedStatement.class);
        }

        private static Object defaultValue(Class<?> type) {
            if (!type.isPrimitive()) {
                return null;
            }
            return type == boolean.class ? Boolean.FALSE : 0;
        }
    }
}
