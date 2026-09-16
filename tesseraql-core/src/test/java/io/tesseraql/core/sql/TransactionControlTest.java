package io.tesseraql.core.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.expr.ExpressionFunctions;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A 2-way SQL file never owns its transaction (docs/audit-low-leads.md G17): a
 * transaction-control statement at the start of any statement is refused at parse, and nothing
 * that is not one — a block, a comment, a literal, a column — is.
 */
class TransactionControlTest {

    @Test
    void aTrailingCommitIsFoundWithItsLine() {
        String sql = "insert into items (name, qty)\nvalues (/* name */'x', /* qty */1);\ncommit;\n";

        assertThat(TransactionControl.find(sql)).hasValueSatisfying(found -> {
            assertThat(found.statement()).isEqualTo("commit");
            assertThat(found.line()).isEqualTo(3);
        });
    }

    @Test
    void everyTransactionControlSpellingIsFound() {
        assertThat(TransactionControl.find("COMMIT WORK")).map(TransactionControl.Found::statement)
                .hasValue("COMMIT");
        assertThat(TransactionControl.find("select 1;\nrollback to savepoint s"))
                .map(TransactionControl.Found::line).hasValue(2);
        assertThat(TransactionControl.find("start transaction; select 1"))
                .map(TransactionControl.Found::statement).hasValue("start transaction");
        assertThat(TransactionControl.find("set transaction isolation level serializable"))
                .map(TransactionControl.Found::statement).hasValue("set transaction");
        assertThat(TransactionControl.find("begin;\nupdate t set a = 1"))
                .map(TransactionControl.Found::statement).hasValue("begin");
        assertThat(TransactionControl.find("BEGIN TRANSACTION\nupdate t set a = 1"))
                .map(TransactionControl.Found::statement).hasValue("BEGIN TRANSACTION");
        assertThat(TransactionControl.find("begin tran update t set a = 1"))
                .map(TransactionControl.Found::statement).hasValue("begin tran");
        assertThat(TransactionControl.find("select 1;\n  \n-- done\nbegin"))
                .map(TransactionControl.Found::line).hasValue(4);
    }

    @Test
    void aBlockACommentALiteralAndAColumnAreNotTransactionControl() {
        // A PL/SQL or T-SQL block starts with BEGIN and continues with a statement.
        assertThat(TransactionControl.find("begin\n  update t set a = 1;\nend;")).isEmpty();
        assertThat(TransactionControl.find("begin try\n select 1\nend try")).isEmpty();
        // Comments, string literals, quoted identifiers and dollar-quoted bodies are opaque.
        assertThat(TransactionControl.find("select 1 -- commit later\nfrom t")).isEmpty();
        assertThat(TransactionControl.find("/* commit */ select 1")).isEmpty();
        assertThat(TransactionControl.find("select 'a; commit;' from t")).isEmpty();
        assertThat(TransactionControl.find("select \"x;\" from t; select 1")).isEmpty();
        assertThat(TransactionControl.find("do $$ begin update t set a = 1; commit; end $$"))
                .isEmpty();
        assertThat(TransactionControl.find("do $b$ begin null; end $b$; select $1")).isEmpty();
        // A column or a value spelled like the keyword is never a statement's first word.
        assertThat(
                TransactionControl.find("select commit, rollback from audit where kind = 'begin'"))
                .isEmpty();
        assertThat(TransactionControl.find("update t set committed = 1\n/*%if q != null */ where"
                + " q = /* q */'x' /*%end*/")).isEmpty();
        assertThat(TransactionControl.find("")).isEmpty();
        assertThat(TransactionControl.find(";;\n")).isEmpty();
    }

    @Test
    void theParserRefusesTheFileForEverySurface() {
        String sql = "update items set qty = /* qty */1 where id = /* id */1;\ncommit;\n";

        assertThatThrownBy(() -> Sql2WayParser.parse(sql, ExpressionFunctions.builtInsOnly()))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-SQL-2123")
                .hasMessageContaining("line 2 (commit)")
                .satisfies(ex -> assertThat(((TqlException) ex).line()).hasValue(2));
        assertThatThrownBy(() -> SqlRenderer.render(sql, Map.of("qty", 1, "id", 1)))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-SQL-2123");
        // The same statements without the terminator render as they always did.
        assertThat(SqlRenderer.render("update items set qty = /* qty */1 where id = /* id */1;",
                Map.of("qty", 1, "id", 1)).sql()).startsWith("update items");
    }
}
