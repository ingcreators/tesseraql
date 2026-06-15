package io.tesseraql.core.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Parsing and rendering of the scope directive (roadmap Phase 29). */
class ScopeDirectiveTest {

    /** A stub resolver returning a fixed predicate sub-template and bindings. */
    private static ScopeResolver resolver(String predicate, Map<String, Object> bindings) {
        return (name, alias, context) -> new ScopeResolver.Resolved(
                Sql2WayParser.parse(predicate), bindings);
    }

    @Test
    void splicesResolvedPredicateAndParameterizesItsBinds() {
        String sql = "select * from orders o\n"
                + "where status = /* status */ 'x'\n"
                + "  and /*%scope orders_scope on o */ (1=1)\n"
                + "order by o.id";
        ScopeResolver resolver = resolver("(o.dept in /* depts */ ('z'))",
                Map.of("depts", List.of("D1", "D2")));

        BoundSql bound = SqlRenderer.render(Sql2WayParser.parse(sql), Map.of("status", "OPEN"),
                resolver, Map.of());

        assertThat(bound.sql()).contains("status = ?");
        assertThat(bound.sql()).contains("and (o.dept in (?, ?))");
        assertThat(bound.parameters()).extracting(BoundParameter::value)
                .containsExactly("OPEN", "D1", "D2");
    }

    @Test
    void parsesScopeWithoutAlias() {
        List<SqlNode> nodes = Sql2WayParser.parse("where /*%scope s */ (1=1)");
        assertThat(nodes).anySatisfy(node -> {
            assertThat(node).isInstanceOf(SqlNode.Scope.class);
            SqlNode.Scope scope = (SqlNode.Scope) node;
            assertThat(scope.name()).isEqualTo("s");
            assertThat(scope.alias()).isNull();
        });
    }

    @Test
    void scopeDirectiveRequiresParenthesizedDummy() {
        assertThatThrownBy(() -> Sql2WayParser.parse("where /*%scope s */ true"))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("parenthesized dummy");
    }

    @Test
    void scopeAliasMustBeIdentifier() {
        assertThatThrownBy(() -> Sql2WayParser.parse("where /*%scope s on 1bad */ (1=1)"))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("SQL identifier");
    }

    @Test
    void renderingAScopeWithoutAResolverFailsLoudly() {
        List<SqlNode> nodes = Sql2WayParser.parse("where /*%scope s */ (1=1)");
        assertThatThrownBy(() -> SqlRenderer.render(nodes, Map.of()))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("No scope resolver");
    }
}
