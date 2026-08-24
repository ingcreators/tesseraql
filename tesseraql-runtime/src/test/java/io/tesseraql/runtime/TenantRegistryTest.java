package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.yaml.config.AppConfig;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class TenantRegistryTest {

    @Test
    void resolvesStaticTenantList() {
        AppConfig config = new AppConfig(Map.of(
                "tenancy", Map.of("tenants", List.of("acme", "globex"))));
        TenantDataSources pools = TenantDataSources.load(new AppConfig(Map.of()));

        assertThat(TenantRegistry.tenantIds(config, null, pools))
                .containsExactly("acme", "globex");
    }

    /**
     * {@code tenancy.registry.sql} is user-declared SQL, so it rides the statement primitive:
     * bounded by {@code tesseraql.sql.timeoutSeconds} and prepared, where it used to run on a
     * raw unbounded {@code createStatement}.
     */
    @Test
    void registrySqlRunsBoundedThroughThePrimitive() {
        AppConfig config = new AppConfig(Map.of(
                "tenancy", Map.of("registry", Map.of("sql", "select tenant_id from tenants")),
                "tesseraql", Map.of("sql", Map.of("timeoutSeconds", "7"))));
        TenantDataSources pools = TenantDataSources.load(new AppConfig(Map.of()));
        List<String> calls = new ArrayList<>();

        assertThat(TenantRegistry.tenantIds(config, oneRowDataSource(calls, "acme"), pools))
                .containsExactly("acme");
        assertThat(calls).contains("prepareStatement", "setQueryTimeout(7)");
    }

    /** A single-column, single-row JDBC surface that records the calls this test pins. */
    private static DataSource oneRowDataSource(List<String> calls, String value) {
        return (DataSource) Proxy.newProxyInstance(TenantRegistryTest.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, new Object() {
                    private boolean rowRead;

                    @SuppressWarnings("unused")
                    Object handle(Object proxy, Method method, Object[] args) {
                        return switch (method.getName()) {
                            case "getConnection" -> proxy(Connection.class);
                            case "prepareStatement" -> {
                                calls.add("prepareStatement");
                                yield proxy(PreparedStatement.class);
                            }
                            case "setQueryTimeout" -> {
                                calls.add("setQueryTimeout(" + args[0] + ")");
                                yield null;
                            }
                            case "executeQuery" -> proxy(ResultSet.class);
                            case "next" -> !rowRead && (rowRead = true);
                            case "getString" -> value;
                            default -> method.getReturnType() == boolean.class
                                    ? false
                                    : method.getReturnType() == int.class ? 0 : null;
                        };
                    }

                    private Object proxy(Class<?> face) {
                        return Proxy.newProxyInstance(TenantRegistryTest.class.getClassLoader(),
                                new Class<?>[]{face}, this::handle);
                    }
                }::handle);
    }

    @Test
    void emptyWhenNoSourceConfigured() {
        AppConfig config = new AppConfig(Map.of("tenancy", Map.of("enabled", "true")));
        TenantDataSources pools = TenantDataSources.load(new AppConfig(Map.of()));

        assertThat(TenantRegistry.tenantIds(config, null, pools)).isEmpty();
    }
}
