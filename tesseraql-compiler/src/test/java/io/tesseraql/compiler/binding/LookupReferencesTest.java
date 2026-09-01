package io.tesseraql.compiler.binding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.model.InputField;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The keyed-fetch machinery behind {@code lookup:} fields (docs/reference-lookup.md). */
class LookupReferencesTest {

    @Test
    void stripsATrailingTopLevelOrderBy() {
        assertThat(LookupReferences.stripTrailingOrderBy(
                "select id, code, name from customers order by name, id"))
                .isEqualTo("select id, code, name from customers");
    }

    @Test
    void keepsAnOrderByInsideASubqueryOrWindow() {
        String windowed = "select id, row_number() over (order by id) as rn from customers";
        assertThat(LookupReferences.stripTrailingOrderBy(windowed)).isEqualTo(windowed);
        assertThat(LookupReferences.stripTrailingOrderBy(
                "select * from (select id from t order by id) s where s.id > 1"))
                .isEqualTo("select * from (select id from t order by id) s where s.id > 1");
    }

    @Test
    void keepsOrderByInsideStringsAndRemarks() {
        String literal = "select 'order by name' as note from t";
        assertThat(LookupReferences.stripTrailingOrderBy(literal)).isEqualTo(literal);
        String remark = "select id from t -- order by id\nwhere id = 1";
        assertThat(LookupReferences.stripTrailingOrderBy(remark)).isEqualTo(remark);
    }

    @Test
    void stripsOnlyTheClauseNotAnIdentifierTail() {
        String reorder = "select reorder_point from stock";
        assertThat(LookupReferences.stripTrailingOrderBy(reorder)).isEqualTo(reorder);
    }

    @Test
    void refusesANonIdentifierColumnAtConstruction() {
        assertThatThrownBy(() -> new LookupReferences.Compiled("customer_id",
                new InputField.LookupSpec("/api/customers", "code; drop table x", "name"),
                List.of(), "customers.sql", Map.of(), "main", "postgresql"))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("not a legal column name");
    }

    @Test
    void aMissingDeclaredColumnIsARenderTimeRefusal() {
        LookupReferences.Compiled compiled = new LookupReferences.Compiled("customer_id",
                new InputField.LookupSpec("/api/customers", "customer_code", "name"),
                List.of(), "customers.sql", Map.of(), "main", "postgresql");
        assertThat(LookupReferences.column(compiled,
                Map.of("CUSTOMER_CODE", "C-1"), "customer_code"))
                .isEqualTo("C-1");
        assertThatThrownBy(() -> LookupReferences.column(compiled,
                Map.of("customer_code", "C-1"), "name"))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-VIEW-3329");
    }
}
