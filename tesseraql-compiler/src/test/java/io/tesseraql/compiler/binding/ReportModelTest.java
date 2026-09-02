package io.tesseraql.compiler.binding;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.yaml.i18n.MessageCatalog;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The shared report model (docs/bulk-report.md decision 1, docs/csv-import.md decision 4): one
 * display contract two feeders fill, grouped by reason, bounded at both levels, and honest
 * about what it bounded.
 */
class ReportModelTest {

    private static final MessageCatalog CATALOG = MessageCatalog.empty();
    private static final Locale EN = Locale.ENGLISH;

    private static ReportModel.Entry entry(String code, String message, String label) {
        return new ReportModel.Entry(code, message, label, null, null, null);
    }

    @Test
    void oneCodeWithTwoMessagesIsTwoReasons() {
        // The defect the extraction fixes: keyed on the code alone these merged, and the
        // second message disappeared. A parse pass emits one code with a sentence per column.
        ReportModel.Rendered rendered = new ReportModel("r", "warning", "3 failed.", List.of(),
                List.of(entry("TQL-FIELD-4001", "qty is not a number", "Line 2"),
                        entry("TQL-FIELD-4001", "held_on is not a date", "Line 3"),
                        entry("TQL-FIELD-4001", "qty is not a number", "Line 7")),
                10, 5).render(CATALOG, EN);

        List<?> groups = (List<?>) rendered.model().get("groups");
        assertThat(groups).hasSize(2);
        assertThat(heading(groups, 0)).isEqualTo("qty is not a number (2)");
        assertThat(heading(groups, 1)).isEqualTo("held_on is not a date (1)");
        // Each entry knows the group it landed in, which is what a grid's row mark describes.
        assertThat(rendered.groupIds()).containsExactly("r-group-0", "r-group-1", "r-group-0");
    }

    @Test
    void aReasonWithoutAMessageIsHeadedByItsCode() {
        ReportModel.Rendered rendered = new ReportModel("r", "warning", "1 failed.", List.of(),
                List.of(entry("TQL-WORKFLOW-3201", null, "PR-1003")), 10, 5)
                .render(CATALOG, EN);

        assertThat(heading((List<?>) rendered.model().get("groups"), 0))
                .isEqualTo("TQL-WORKFLOW-3201 (1)");
    }

    @Test
    void aGroupCapsItsEntriesButNeverItsCount() {
        ReportModel.Rendered rendered = new ReportModel("r", "warning", "4 failed.", List.of(),
                List.of(entry("C", "same reason", "Line 2"), entry("C", "same reason", "Line 3"),
                        entry("C", "same reason", "Line 4"), entry("C", "same reason", "Line 5")),
                10, 2).render(CATALOG, EN);

        Map<?, ?> group = (Map<?, ?>) ((List<?>) rendered.model().get("groups")).get(0);
        assertThat(group.get("heading")).isEqualTo("same reason (4)");
        assertThat((List<?>) group.get("rows")).hasSize(2);
        assertThat(group.get("more")).isEqualTo("…and 2 more");
        // Bounding the display never moves an entry's group: all four still describe it.
        assertThat(rendered.groupIds()).containsOnly("r-group-0");
    }

    @Test
    void theGroupListIsBoundedTooAndSaysWhatItLeftOut() {
        ReportModel.Rendered rendered = new ReportModel("r", "warning", "4 failed.", List.of(),
                List.of(entry("C", "first", "Line 2"), entry("C", "second", "Line 3"),
                        entry("C", "third", "Line 4"), entry("C", "third", "Line 5")),
                2, 5).render(CATALOG, EN);

        assertThat((List<?>) rendered.model().get("groups")).hasSize(2);
        assertThat(rendered.model().get("more")).isEqualTo("…and 1 more reason(s)");
        // An entry whose reason is not on the page is described by the report itself, so the
        // row still carries a mark rather than pointing at an id nothing renders.
        assertThat(rendered.groupIds())
                .containsExactly("r-group-0", "r-group-1", "r-report", "r-report");
    }

    @Test
    void anEntryCarriesItsOwnLinkFieldAndRejectedValue() {
        // What the second feeder needs: the link is a value, not a "#row-<token>" derivation,
        // and a parse rejection names the column and quotes the text it refused.
        ReportModel.Rendered rendered = new ReportModel("r", "warning", "1 failed.", List.of(),
                List.of(new ReportModel.Entry("TQL-FIELD-4001", "not a number", "Line 3",
                        "#line-3", "qty", "abc")),
                10, 5).render(CATALOG, EN);

        Map<?, ?> row = (Map<?, ?>) ((List<?>) ((Map<?, ?>) ((List<?>) rendered.model()
                .get("groups")).get(0)).get("rows")).get(0);
        assertThat(row.get("label")).isEqualTo("Line 3");
        assertThat(row.get("href")).isEqualTo("#line-3");
        assertThat(row.get("field")).isEqualTo("qty");
        assertThat(row.get("value")).isEqualTo("abc");
        assertThat(rendered.groupIds()).containsExactly("r-group-0");
    }

    @Test
    void fileLevelFailuresGetTheirOwnSlotAndAreAbsentWhenThereAreNone() {
        ReportModel.Rendered withFile = new ReportModel("r", "error", "Unreadable.",
                List.of("The header row does not map."), List.of(), 10, 5).render(CATALOG, EN);
        assertThat(withFile.model().get("fileErrors"))
                .isEqualTo(List.of("The header row does not map."));
        assertThat((List<?>) withFile.model().get("groups")).isEmpty();

        ReportModel.Rendered without = new ReportModel("r", "success", "All succeeded.",
                List.of(), List.of(), 10, 5).render(CATALOG, EN);
        // Null rather than empty: the fragment's th:if is what keeps a clean report quiet.
        assertThat(without.model().get("fileErrors")).isNull();
        assertThat(without.model().get("more")).isNull();
        assertThat(without.model().get("region")).isEqualTo("r-report");
    }

    @Test
    void theCatalogOverridesTheBuiltInRemainderLines() {
        MessageCatalog app = MessageCatalog.parse("ja", new java.io.ByteArrayInputStream("""
                tql:
                  report:
                    more: "…ほか {count} 件"
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8)), "ja.yml");

        ReportModel.Rendered rendered = new ReportModel("r", "warning", "2 failed.", List.of(),
                List.of(entry("C", "same", "Line 2"), entry("C", "same", "Line 3")), 10, 1)
                .render(app, Locale.forLanguageTag("ja-JP"));

        Map<?, ?> group = (Map<?, ?>) ((List<?>) rendered.model().get("groups")).get(0);
        // The bare-language fallback: a ja-JP request reads the app's ja catalog.
        assertThat(group.get("more")).isEqualTo("…ほか 1 件");
    }

    @Test
    void aFeederThatAsksForTheTableGetsTheEnumerationAndItsAnchors() {
        ReportModel.Rendered rendered = new ReportModel("r", "warning", "3 failed.", List.of(),
                List.of(new ReportModel.Entry("qty", "qty — is not a number", "Line 2", null,
                        "qty", "abc"),
                        new ReportModel.Entry("qty", "qty — is not a number", "Line 3", null,
                                "qty", "xyz"),
                        new ReportModel.Entry("sku", "sku — is required", "Line 7", null,
                                "sku", null)),
                10, 5, 2).render(CATALOG, EN);

        Map<?, ?> table = (Map<?, ?>) rendered.model().get("table");
        // Bounded like everything else, and the caption says the total rather than letting
        // "showing 2" read as "2 is all there was".
        assertThat(table.get("caption")).isEqualTo("Rejected rows (2 of 3 shown)");
        assertThat(table.get("more")).isEqualTo("…and 1 more");
        List<?> rows = (List<?>) table.get("rows");
        assertThat(rows).hasSize(2);
        Map<?, ?> first = (Map<?, ?>) rows.get(0);
        assertThat(first.get("id")).isEqualTo("r-row-0");
        assertThat(first.get("field")).isEqualTo("qty");
        assertThat(first.get("value")).isEqualTo("abc");
        assertThat(first.get("message")).isEqualTo("qty — is not a number");

        // The two halves are wired together: a grouped entry links to the table row that
        // details it, and an entry past the table cap links nowhere rather than to a row that
        // is not on the page.
        Map<?, ?> group = (Map<?, ?>) ((List<?>) rendered.model().get("groups")).get(0);
        List<?> entries = (List<?>) group.get("rows");
        assertThat(((Map<?, ?>) entries.get(0)).get("href")).isEqualTo("#r-row-0");
        assertThat(((Map<?, ?>) entries.get(1)).get("href")).isEqualTo("#r-row-1");
        Map<?, ?> second = (Map<?, ?>) ((List<?>) rendered.model().get("groups")).get(1);
        assertThat(((Map<?, ?>) ((List<?>) second.get("rows")).get(0)).get("href")).isNull();
    }

    @Test
    void aFeederThatAsksForNoTableGetsNoneAndKeepsItsOwnLinks() {
        ReportModel.Rendered rendered = new ReportModel("r", "warning", "1 failed.", List.of(),
                List.of(new ReportModel.Entry("C", "reason", "Row 1 — B-2", "#row-abc", null,
                        null)),
                10, 5).render(CATALOG, EN);

        assertThat(rendered.model().get("table")).isNull();
        Map<?, ?> group = (Map<?, ?>) ((List<?>) rendered.model().get("groups")).get(0);
        assertThat(((Map<?, ?>) ((List<?>) group.get("rows")).get(0)).get("href"))
                .isEqualTo("#row-abc");
    }

    private static Object heading(List<?> groups, int index) {
        return ((Map<?, ?>) groups.get(index)).get("heading");
    }
}
