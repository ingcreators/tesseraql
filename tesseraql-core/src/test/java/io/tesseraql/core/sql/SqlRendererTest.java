package io.tesseraql.core.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SqlRendererTest {

    @Test
    void scalarBindReplacesDummyWithPlaceholder() {
        String sql = "select * from users where name = /* name */ 'dummy'";
        BoundSql bound = SqlRenderer.render(sql, Map.of("name", "sato"));

        assertThat(bound.sql()).isEqualTo("select * from users where name = ?");
        assertThat(bound.parameters()).hasSize(1);
        assertThat(bound.parameters().get(0).expression()).isEqualTo("name");
        assertThat(bound.parameters().get(0).value()).isEqualTo("sato");
    }

    @Test
    void conditionalFragmentIncludedWhenTrue() {
        String sql = """
                select * from users
                where 1 = 1
                /*%if q != null && q != "" */
                  and name like /* q */ '%x%'
                /*%end*/
                """;
        BoundSql included = SqlRenderer.render(sql, Map.of("q", "sato"));
        assertThat(included.sql()).contains("and name like ?");
        assertThat(included.parameters()).extracting(BoundParameter::value).containsExactly("sato");

        BoundSql excluded = SqlRenderer.render(sql, Collections.singletonMap("q", null));
        assertThat(excluded.sql()).doesNotContain("like");
        assertThat(excluded.parameters()).isEmpty();
    }

    @Test
    void elseifAndElseBranches() {
        String sql = """
                select * from t
                /*%if kind == "a" */ where a = 1
                /*%elseif kind == "b" */ where b = 2
                /*%else */ where c = 3
                /*%end*/""";
        assertThat(SqlRenderer.render(sql, Map.of("kind", "a")).sql()).contains("a = 1");
        assertThat(SqlRenderer.render(sql, Map.of("kind", "b")).sql()).contains("b = 2");
        assertThat(SqlRenderer.render(sql, Map.of("kind", "z")).sql()).contains("c = 3");
    }

    @Test
    void inListExpansion() {
        String sql = "select * from users where id in /* ids */ (1, 2, 3)";
        BoundSql bound = SqlRenderer.render(sql, Map.of("ids", List.of(10, 20, 30)));

        assertThat(bound.sql()).isEqualTo("select * from users where id in (?, ?, ?)");
        assertThat(bound.parameters()).extracting(BoundParameter::value).containsExactly(10, 20,
                30);
    }

    @Test
    void emptyInListRendersNullPredicate() {
        String sql = "select * from users where id in /* ids */ (1)";
        BoundSql bound = SqlRenderer.render(sql, Map.of("ids", List.of()));

        assertThat(bound.sql()).isEqualTo("select * from users where id in (null)");
        assertThat(bound.parameters()).isEmpty();
    }

    @Test
    void forLoopRepeatsBody() {
        String sql = "/*%for id : ids */ /* id */ 0 /*%end*/";
        BoundSql bound = SqlRenderer.render(sql, Map.of("ids", List.of(1, 2)));

        assertThat(bound.parameters()).extracting(BoundParameter::value).containsExactly(1, 2);
        assertThat(bound.sql().chars().filter(c -> c == '?').count()).isEqualTo(2);
    }

    @Test
    void variantDiffersByBranchDecision() {
        String sql = """
                select * from t where 1=1
                /*%if q != null */ and q = /* q */ '' /*%end*/""";
        SqlVariant withQ = SqlRenderer.render(sql, Map.of("q", "a")).variant();
        SqlVariant withoutQ = SqlRenderer.render(sql, Collections.singletonMap("q", null))
                .variant();

        assertThat(withQ.hash()).isNotEqualTo(withoutQ.hash());
    }

    @Test
    void coverageRecordsBranchAndLines() {
        String sql = """
                select 1
                /*%if q != null */ and q = /* q */ '' /*%end*/""";
        CoverageTrace trace = SqlRenderer.render(sql, Map.of("q", "a")).coverageTrace();

        assertThat(trace.branches()).containsExactly(new CoverageTrace.Branch(2, true));
        assertThat(trace.coveredLines()).contains(1, 2);
    }

    @Test
    void sourceMapTracksSourceLines() {
        String sql = "select 1\nfrom dual";
        SourceMap map = SqlRenderer.render(sql, Map.of()).sourceMap();
        assertThat(map.sourceLineAt(0)).isEqualTo(1);
        assertThat(map.sourceLineAt(sql.indexOf("from"))).isEqualTo(2);
    }

    @Test
    void nonCollectionForListBindFails() {
        String sql = "select * from t where id in /* ids */ (1)";
        assertThatThrownBy(() -> SqlRenderer.render(sql, Map.of("ids", "not-a-list")))
                .isInstanceOf(TqlException.class);
    }
}
