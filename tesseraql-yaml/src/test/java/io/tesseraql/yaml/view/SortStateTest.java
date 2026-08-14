package io.tesseraql.yaml.view;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The one header contract every sortable grid reads (studio tables, audit trail, view: lists). */
class SortStateTest {

    private static final List<String> COLUMNS = List.of("at", "actor", "action");

    @Test
    void aRequestNamingNothingOpensOnTheGridsOwnDefault() {
        SortState state = SortState.of(null, null, COLUMNS, "at", true);

        assertThat(state.key()).isEqualTo("at");
        assertThat(state.direction()).isEqualTo("desc");
        assertThat(state.ariaSort("at")).isEqualTo("descending");
        assertThat(state.ariaSort("actor")).isEqualTo("none");
    }

    @Test
    void theActiveColumnFlipsAndEveryOtherColumnStartsAscending() {
        SortState state = SortState.of("actor", "asc", COLUMNS, "at", true);

        assertThat(state.nextDirection("actor")).isEqualTo("desc");
        assertThat(state.nextDirection("action")).isEqualTo("asc");
        assertThat(state.nextDirection("at")).isEqualTo("asc");
    }

    /** A stale or hand-edited link must not sort by a column the page does not have. */
    @Test
    void aColumnTheGridCannotSortByIsTreatedAsNamingNone() {
        SortState state = SortState.of("secretColumn", "asc", COLUMNS, "at", true);

        assertThat(state.key()).isEqualTo("at");
        assertThat(state.ariaSort("secretColumn")).isEqualTo("none");
    }

    /**
     * A direction the request states is honored on the default column too. The audit trail used to
     * drop it — {@code ?dir=asc} with no {@code sort} still opened newest-first, and the link the
     * page had just rendered did nothing.
     */
    @Test
    void aStatedDirectionIsHonoredEvenWhenTheRequestNamesNoColumn() {
        assertThat(SortState.of(null, "asc", COLUMNS, "at", true).direction()).isEqualTo("asc");
        assertThat(SortState.of(null, "desc", COLUMNS, "at", false).direction()).isEqualTo("desc");
    }

    @Test
    void anUnsortedGridHasNoActiveColumn() {
        SortState state = SortState.of(null, null, COLUMNS, null, false);

        assertThat(state.key()).isNull();
        assertThat(state.ariaSorts().values()).containsOnly("none");
    }

    @Test
    void theModelCarriesEveryColumnInHeaderOrderWithThePagesOwnQuery() {
        SortState state = SortState.of("actor", "desc", COLUMNS, "at", true);
        Map<String, Object> model = new java.util.LinkedHashMap<>();

        state.putInto(model, "/ui/audit", "&q=alice");

        assertThat(model).containsEntry("sortKey", "actor").containsEntry("sortDir", "desc");
        assertThat(state.ariaSorts()).containsExactly(
                Map.entry("at", "none"), Map.entry("actor", "descending"),
                Map.entry("action", "none"));
        assertThat(state.hrefs("/ui/audit", "&q=alice"))
                .containsEntry("actor", "/ui/audit?sort=actor&dir=asc&q=alice")
                .containsEntry("action", "/ui/audit?sort=action&dir=asc&q=alice");
        assertThat(model.get("ariaSort")).isEqualTo(state.ariaSorts());
        assertThat(model.get("sortHref")).isEqualTo(state.hrefs("/ui/audit", "&q=alice"));
    }
}
