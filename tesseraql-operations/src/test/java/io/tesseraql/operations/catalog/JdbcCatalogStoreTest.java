package io.tesseraql.operations.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.catalog.CatalogStore;
import io.tesseraql.core.catalog.CodeCatalog;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.i18n.I18nSettings;
import io.tesseraql.yaml.model.CatalogSpec;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * A catalog that has never loaded (docs/lookups.md, decision 14 as built; docs/audit-low-leads.md
 * unfiled 19). Before this, the store loaded every catalog on any route's first request, so
 * one table missing on one environment answered {@code TQL-APP-4206} on every route of the app,
 * re-ran the failing query per request, and logged nothing at the default level.
 *
 * <p>The JDBC stack is a proxy that answers an empty result for any table but the one it
 * refuses, and counts what it was asked to prepare — the count is how "once per interval, not
 * once per request" is asserted.
 */
class JdbcCatalogStoreTest {

    private static final String REFUSED_TABLE = "no_such_table";

    private final FakeJdbc jdbc = new FakeJdbc();
    private final AtomicLong clock = new AtomicLong(1_000_000L);

    private JdbcCatalogStore store() {
        return new JdbcCatalogStore(Map.of(
                "healthy", spec("code_master"),
                "broken", spec(REFUSED_TABLE)),
                name -> jdbc.dataSource(), "postgresql", null, I18nSettings.defaults(),
                clock::get);
    }

    @Test
    void aCatalogLoadsOnTheReadThatAsksForItAndNotBefore() {
        JdbcCatalogStore store = store();

        Map<String, CodeCatalog> codes = store.catalogs("en");

        // Publishing the object loads nothing; the read of one catalog loads that one.
        assertThat(jdbc.prepared).isEmpty();
        assertThat(codes.get("healthy")).isNotNull();
        assertThat(jdbc.prepared).hasSize(1).allSatisfy(sql -> assertThat(sql)
                .contains("code_master").doesNotContain(REFUSED_TABLE));
        // Memoized for the request: a page's twentieth coded column is a map lookup.
        assertThat(codes.get("healthy")).isSameAs(codes.get("healthy"));
        assertThat(jdbc.prepared).hasSize(1);
        assertThat(codes.get("undeclared")).isNull();
        assertThat(codes.keySet()).containsExactlyInAnyOrder("healthy", "broken");
    }

    @Test
    void aCatalogThatCannotLoadFailsItsReaderOnlyAndIsHeldForTheInterval() {
        JdbcCatalogStore store = store();
        Map<String, CodeCatalog> codes = store.catalogs("en");

        // The broken catalog's reader is refused, coded, naming the catalog and the cause.
        assertThatThrownBy(() -> codes.get("broken"))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("Catalog 'broken'")
                .hasMessageContaining("has never loaded")
                .hasMessageContaining(REFUSED_TABLE)
                .satisfies(ex -> assertThat(((TqlException) ex).code().toString())
                        .isEqualTo("TQL-APP-4206"));
        // The healthy one beside it still serves, on the same published object.
        assertThat(codes.get("healthy")).isNotNull();
        assertThat(jdbc.preparedFor(REFUSED_TABLE)).isEqualTo(1);

        // A second read within the interval — on a new request's object — is refused again
        // without asking the database: the refusal is held like a load would be.
        assertThatThrownBy(() -> store.catalogs("en").get("broken"))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("has never loaded");
        assertThat(jdbc.preparedFor(REFUSED_TABLE)).isEqualTo(1);

        // After the interval the load is tried once more.
        clock.addAndGet(5_000L);
        assertThatThrownBy(() -> store.catalogs("en").get("broken"))
                .isInstanceOf(TqlException.class);
        assertThat(jdbc.preparedFor(REFUSED_TABLE)).isEqualTo(2);
    }

    @Test
    void theOperationsRowCarriesTheFailureAgainstANeverLoadedHold() {
        JdbcCatalogStore store = store();
        assertThatThrownBy(() -> store.catalogs("en").get("broken"))
                .isInstanceOf(TqlException.class);

        CatalogStore.Status broken = store.status().stream()
                .filter(status -> status.name().equals("broken")).findFirst().orElseThrow();

        // Never loaded — no count, no languages, no load time — AND the error it left, so the
        // status page is not blank about the catalog every reader is refused on.
        assertThat(broken.codes()).isEqualTo(-1);
        assertThat(broken.languages()).isEmpty();
        assertThat(broken.loadedAt()).isNull();
        assertThat(broken.lastError()).contains(REFUSED_TABLE);
    }

    @Test
    void anInvalidationDropsTheHeldRefusalSoTheNextReadTriesAgain() {
        JdbcCatalogStore store = store();
        assertThatThrownBy(() -> store.catalogs("en").get("broken"))
                .isInstanceOf(TqlException.class);
        assertThat(jdbc.preparedFor(REFUSED_TABLE)).isEqualTo(1);

        // The maintenance write that created the table names it; the next reader loads.
        store.invalidate(List.of(REFUSED_TABLE));
        assertThatThrownBy(() -> store.catalogs("en").get("broken"))
                .isInstanceOf(TqlException.class);
        assertThat(jdbc.preparedFor(REFUSED_TABLE)).isEqualTo(2);
    }

    private static CatalogSpec spec(String table) {
        return new CatalogSpec(table, null, null, null, "code",
                new CatalogSpec.LabelSource("name", null), null, null, null, null, null);
    }

    /** A JDBC stack that answers an empty result for every table but the one it refuses. */
    private static final class FakeJdbc implements InvocationHandler {

        private final List<String> prepared = new ArrayList<>();

        private DataSource dataSource() {
            return proxy(DataSource.class);
        }

        private long preparedFor(String table) {
            return prepared.stream().filter(sql -> sql.contains(table)).count();
        }

        private <T> T proxy(Class<T> type) {
            return type.cast(Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{type}, this));
        }

        @Override
        public Object invoke(Object instance, Method method, Object[] args) throws SQLException {
            return switch (method.getName()) {
                case "getConnection" -> proxy(Connection.class);
                case "prepareStatement" -> prepare(String.valueOf(args[0]));
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
            if (sql.contains(REFUSED_TABLE)) {
                throw new SQLException("relation \"" + REFUSED_TABLE + "\" does not exist",
                        "42P01");
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
