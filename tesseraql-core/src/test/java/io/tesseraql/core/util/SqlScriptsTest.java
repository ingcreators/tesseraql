package io.tesseraql.core.util;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

/**
 * The already-exists tolerance of {@link SqlScripts}, measured on a live H2 — the one dialect
 * whose duplicate-object signal the class had read from the wrong field: H2 reports its
 * duplicate-column and duplicate-index codes 42121/42111 through {@code getErrorCode()} under
 * the generic states 42S21/42S11, and the class compared the SQLState, so every
 * {@code ensureSchema} column add failed a second boot on H2 (docs/audit-low-leads.md slice 4).
 *
 * <p>A bare {@code create table} is not tolerated anywhere the vendor takes {@code if not
 * exists}: the second case pins that a script must spell it, rather than lean on a tolerance
 * that MySQL (1050) and H2 (42101) never had.
 */
class SqlScriptsTest {

    private static DataSource h2(String name) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
        return dataSource;
    }

    @Test
    void aColumnAddAndAnIndexApplyTwiceOnH2() throws SQLException {
        DataSource dataSource = h2("sqlscripts-reboot");
        String script = """
                create table if not exists tql_probe (id varchar(64) primary key);
                alter table tql_probe add note varchar(400);
                create index idx_tql_probe_note on tql_probe (note);
                """;
        SqlScripts.applyScript(dataSource, script);
        assertThatCode(() -> SqlScripts.applyScript(dataSource, script))
                .as("a second boot re-applies the script: the duplicate column and index are"
                        + " tolerated")
                .doesNotThrowAnyException();
    }

    /**
     * Two replicas booting together on a fresh PostgreSQL (docs/deployment-maturity.md, S5):
     * both pass the {@code IF NOT EXISTS} check, both create, and the loser is told a unique
     * violation on {@code pg_type_typname_nsp_index} — state 23505, not the duplicate-table
     * state the class tolerates — and one of two pods died at boot on it. A unique violation
     * on a create is the other replica having won; on anything else it is data, and fails.
     */
    @Test
    void aUniqueViolationOnACreateIsTheOtherReplicaHavingWon() {
        assertThatCode(() -> SqlScripts.applyScript(refusing("23505"),
                "create table if not exists tql_probe (id varchar(64) primary key);"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> SqlScripts.applyScript(refusing("23505"),
                "insert into tql_probe (id) values ('x');"))
                .isInstanceOf(SQLException.class);
    }

    /** A datasource whose every statement fails with the given SQLState. */
    private static DataSource refusing(String state) {
        java.sql.Statement statement = proxy(java.sql.Statement.class, (method, args) -> {
            if ("execute".equals(method.getName())) {
                throw new SQLException("duplicate key value violates unique constraint", state);
            }
            return null;
        });
        java.sql.Connection connection = proxy(java.sql.Connection.class,
                (method, args) -> "createStatement".equals(method.getName()) ? statement : null);
        return proxy(DataSource.class,
                (method, args) -> "getConnection".equals(method.getName()) ? connection : null);
    }

    private static <T> T proxy(Class<T> type, Answer answer) {
        return type.cast(java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(),
                new Class<?>[]{type}, (proxied, method, args) -> answer.answer(method, args)));
    }

    @FunctionalInterface
    private interface Answer {
        Object answer(java.lang.reflect.Method method, Object[] args) throws Throwable;
    }

    @Test
    void aBareCreateTableIsNotToleratedTwice() throws SQLException {
        DataSource dataSource = h2("sqlscripts-bare");
        String script = "create table tql_bare (id varchar(64) primary key);";
        SqlScripts.applyScript(dataSource, script);
        assertThatThrownBy(() -> SqlScripts.applyScript(dataSource, script))
                .as("a table create spells IF NOT EXISTS; the duplicate-table error is not"
                        + " in the tolerated set")
                .isInstanceOf(SQLException.class);
    }
}
