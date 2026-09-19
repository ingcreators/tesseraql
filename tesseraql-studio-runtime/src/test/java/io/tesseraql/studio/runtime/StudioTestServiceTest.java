package io.tesseraql.studio.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.core.expr.ExpressionFunctions;
import io.tesseraql.yaml.manifest.ManifestLoader;
import io.tesseraql.yaml.model.RouteDefinition;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The live preview runs each source once and publishes it under its own name
 * (docs/unified-sources.md decision 10; docs/audit-low-leads.md unfiled 38). Before this the
 * main binding ran twice per preview — once under the retired {@code sql} key, once under
 * {@code main} — and a template reading {@code sql.rows} kept working on a spelling every
 * served route had stopped publishing.
 */
class StudioTestServiceTest {

    @Test
    void theMainSourceRunsOnceAndIsPublishedUnderItsOwnNameOnly(@TempDir Path dir)
            throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: preview-test
                """);
        Path route = Files.createDirectories(dir.resolve("web/api/items"));
        Files.writeString(route.resolve("get.yml"), """
                version: tesseraql/v1
                id: items.list
                kind: route
                recipe: query-json
                security:
                  auth: public
                sources:
                  main:
                    sql:
                      file: items.sql
                response:
                  json:
                    body:
                      items: main.rows
                """);
        Files.writeString(route.resolve("items.sql"), "select 1 as id\n");
        RouteDefinition definition = new ManifestLoader().load(dir).routes().get(0).definition();
        FakeJdbc jdbc = new FakeJdbc();
        StudioTestService service = new StudioTestService(name -> jdbc.dataSource(), dir, null,
                "postgres", true, 5, 100, ExpressionFunctions.builtInsOnly());

        Map<String, Object> live = service.liveRows(definition, route, Map.of());

        assertThat(live).containsOnlyKeys("main");
        assertThat(jdbc.prepared).containsExactly("select 1 as id\n");
    }

    /** A JDBC stack that records the statements it prepares and answers an empty result. */
    private static final class FakeJdbc implements InvocationHandler {

        private final List<String> prepared = new ArrayList<>();

        private DataSource dataSource() {
            return proxy(DataSource.class);
        }

        private <T> T proxy(Class<T> type) {
            return type.cast(Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{type}, this));
        }

        @Override
        public Object invoke(Object instance, Method method, Object[] args) {
            return switch (method.getName()) {
                case "getConnection" -> proxy(Connection.class);
                case "prepareStatement" -> {
                    prepared.add(String.valueOf(args[0]));
                    yield proxy(PreparedStatement.class);
                }
                case "executeQuery" -> proxy(ResultSet.class);
                case "getMetaData" -> proxy(ResultSetMetaData.class);
                case "next" -> Boolean.FALSE;
                case "toString" -> "fake";
                case "hashCode" -> System.identityHashCode(instance);
                case "equals" -> instance == args[0];
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
