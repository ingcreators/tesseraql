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
