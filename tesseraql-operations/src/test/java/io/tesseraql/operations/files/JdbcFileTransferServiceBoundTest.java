package io.tesseraql.operations.files;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * The transfer service bounded the statements it prepared through {@code prepare}/
 * {@code prepareExtraction} but its own {@code tql_file_transfer} bookkeeping statements
 * bypassed {@code applyTimeout} entirely (docs/contract-sql-execution.md slice 2). This pins a
 * representative bookkeeping statement; reverting the {@code applyTimeout} calls turns it red.
 */
class JdbcFileTransferServiceBoundTest {

    @Test
    void boundsTheBookkeepingStatements() {
        FakeJdbc jdbc = new FakeJdbc();
        JdbcFileTransferService service = new JdbcFileTransferService(null, null,
                jdbc.dataSource(), null).sqlTimeoutSeconds(7);

        service.recent(5);

        assertThat(jdbc.calls).contains("setQueryTimeout(7)");
    }

    /** A JDBC stack that records what was asked of it and answers an empty result. */
    private static final class FakeJdbc implements InvocationHandler {

        private final List<String> calls = new ArrayList<>();

        private DataSource dataSource() {
            return proxy(DataSource.class);
        }

        private <T> T proxy(Class<T> type) {
            return type.cast(Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{type}, this));
        }

        @Override
        public Object invoke(Object instance, Method method, Object[] args) {
            calls.add(method.getName() + (args == null || args.length == 0
                    ? ""
                    : "(" + args[0] + ")"));
            return switch (method.getName()) {
                case "getConnection" -> proxy(Connection.class);
                case "getMetaData" -> proxy(DatabaseMetaData.class);
                case "getDatabaseProductName" -> "PostgreSQL";
                case "prepareStatement" -> proxy(PreparedStatement.class);
                case "executeQuery" -> proxy(ResultSet.class);
                case "executeUpdate" -> 1;
                default -> defaultValue(method.getReturnType());
            };
        }

        private static Object defaultValue(Class<?> type) {
            if (!type.isPrimitive()) {
                return null;
            }
            return type == boolean.class ? Boolean.FALSE : 0;
        }
    }
}
