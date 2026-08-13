package io.tesseraql.yaml.view;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The declarative view document (roadmap Phase 39, docs/declarative-views.md). */
class ViewSpecTest {

    private static Path write(Path dir, String name, String yaml) throws Exception {
        Path file = dir.resolve(name);
        Files.writeString(file, yaml);
        return file;
    }

    @Test
    void parsesAListViewWithColumns(@TempDir Path dir) throws Exception {
        ViewSpec spec = ViewSpec.parse(write(dir, "items.view.yml", """
                version: tesseraql/v1
                kind: view
                recipe: list
                title: view.items.title
                columns:
                  - name: name
                    link: /items/{id}
                  - name: due_date
                    label: Due
                """));
        assertThat(spec.view()).isEqualTo(ViewSpec.LIST);
        assertThat(spec.id()).isEqualTo("items");
        assertThat(spec.source()).isEqualTo("main");
        assertThat(spec.columns()).hasSize(2);
        assertThat(spec.columns().get(0).link()).isEqualTo("/items/{id}");
        assertThat(spec.columns().get(1).label()).isEqualTo("Due");
    }

    @Test
    void parsesAFormViewWithFieldOverrides(@TempDir Path dir) throws Exception {
        ViewSpec spec = ViewSpec.parse(write(dir, "new.view.yml", """
                version: tesseraql/v1
                id: items.new
                kind: view
                recipe: form
                action: /items/create
                fields:
                  - name: note
                    widget: textarea
                """));
        assertThat(spec.id()).isEqualTo("items.new");
        assertThat(spec.action()).isEqualTo("/items/create");
        assertThat(spec.fields()).hasSize(1);
        assertThat(spec.fields().get(0).widget()).isEqualTo("textarea");
    }

    @Test
    void parsesADetailViewWithChildrenAndSlots(@TempDir Path dir) throws Exception {
        ViewSpec spec = ViewSpec.parse(write(dir, "item.view.yml", """
                version: tesseraql/v1
                kind: view
                recipe: detail
                fields:
                  - name: name
                children:
                  - source: orders
                    title: Orders
                    columns:
                      - name: qty
                slots:
                  header: frags.html::actions
                """));
        assertThat(spec.view()).isEqualTo(ViewSpec.DETAIL);
        assertThat(spec.children()).hasSize(1);
        assertThat(spec.children().get(0).source()).isEqualTo("orders");
        assertThat(spec.children().get(0).columns()).hasSize(1);
        assertThat(spec.slots()).containsEntry("header", "frags.html::actions");
    }

    @Test
    void rejectsChildrenOnANonDetailView(@TempDir Path dir) throws Exception {
        Path file = write(dir, "x.view.yml", """
                version: tesseraql/v1
                kind: view
                recipe: list
                children:
                  - source: orders
                """);
        assertThatThrownBy(() -> ViewSpec.parse(file))
                .isInstanceOf(TqlException.class).hasMessageContaining("detail-view key");
    }

    @Test
    void rejectsAChildWithoutSource(@TempDir Path dir) throws Exception {
        Path file = write(dir, "x.view.yml", """
                version: tesseraql/v1
                kind: view
                recipe: detail
                children:
                  - title: Orders
                """);
        assertThatThrownBy(() -> ViewSpec.parse(file))
                .isInstanceOf(TqlException.class).hasMessageContaining("requires source");
    }

    @Test
    void parsesADashboardWithPanels(@TempDir Path dir) throws Exception {
        ViewSpec spec = ViewSpec.parse(write(dir, "stats.view.yml", """
                version: tesseraql/v1
                kind: view
                recipe: dashboard
                title: Stats
                panels:
                  - title: Users
                    type: stat
                    source: totals
                    column: user_count
                  - title: Signups
                    type: chart
                    chart: bar
                    source: signups
                    x: day
                    y: n
                  - type: sparkline
                    source: signups
                    column: n
                  - type: table
                    source: recent
                """));
        assertThat(spec.view()).isEqualTo(ViewSpec.DASHBOARD);
        assertThat(spec.panels()).hasSize(4);
        assertThat(spec.panels().get(1).x()).isEqualTo("day");
        assertThat(spec.panels().get(2).column()).isEqualTo("n");
    }

    @Test
    void rejectsAPanelWithoutAKnownType(@TempDir Path dir) throws Exception {
        Path file = write(dir, "x.view.yml", """
                version: tesseraql/v1
                kind: view
                recipe: dashboard
                panels:
                  - title: Broken
                """);
        assertThatThrownBy(() -> ViewSpec.parse(file))
                .isInstanceOf(TqlException.class).hasMessageContaining("type:");
    }

    @Test
    void rejectsAStatPanelWithoutAColumn(@TempDir Path dir) throws Exception {
        Path file = write(dir, "x.view.yml", """
                version: tesseraql/v1
                kind: view
                recipe: dashboard
                panels:
                  - type: stat
                """);
        assertThatThrownBy(() -> ViewSpec.parse(file))
                .isInstanceOf(TqlException.class).hasMessageContaining("requires column");
    }

    @Test
    void rejectsAChartPanelWithoutItsAxes(@TempDir Path dir) throws Exception {
        Path file = write(dir, "x.view.yml", """
                version: tesseraql/v1
                kind: view
                recipe: dashboard
                panels:
                  - type: chart
                    y: n
                """);
        assertThatThrownBy(() -> ViewSpec.parse(file))
                .isInstanceOf(TqlException.class).hasMessageContaining("TQL-VIEW-3313")
                .hasMessageContaining("requires x:");
        Path noSeries = write(dir, "y.view.yml", """
                version: tesseraql/v1
                kind: view
                recipe: dashboard
                panels:
                  - type: chart
                    x: day
                """);
        assertThatThrownBy(() -> ViewSpec.parse(noSeries))
                .isInstanceOf(TqlException.class).hasMessageContaining("y: or series:");
    }

    @Test
    void parsesAMultiSeriesChartWithThePassthroughAttributes(@TempDir Path dir)
            throws Exception {
        ViewSpec spec = ViewSpec.parse(write(dir, "x.view.yml", """
                version: tesseraql/v1
                kind: view
                recipe: dashboard
                panels:
                  - type: chart
                    chart: bar-grouped
                    x: month
                    xType: date
                    height: 240
                    legend: false
                    yLabel: units
                    series:
                      - { column: stock, label: In stock }
                      - { column: reserved }
                """));
        ViewSpec.Panel panel = spec.panels().get(0);
        assertThat(panel.kind()).isEqualTo("bar-grouped");
        assertThat(panel.effectiveSeries()).containsExactly(
                new ViewSpec.Series("stock", "In stock", null),
                new ViewSpec.Series("reserved", null, null));
        assertThat(panel.xType()).isEqualTo("date");
        assertThat(panel.height()).isEqualTo(240);
        assertThat(panel.legend()).isFalse();
        assertThat(panel.yLabel()).isEqualTo("units");
    }

    @Test
    void theYShorthandIsASingleSeries(@TempDir Path dir) throws Exception {
        ViewSpec spec = ViewSpec.parse(write(dir, "x.view.yml", """
                version: tesseraql/v1
                kind: view
                recipe: dashboard
                panels:
                  - { type: chart, x: day, y: n }
                """));
        assertThat(spec.panels().get(0).effectiveSeries())
                .containsExactly(new ViewSpec.Series("n", null, null));
    }

    @Test
    void rejectsChartVocabularyViolations(@TempDir Path dir) throws Exception {
        assertThatThrownBy(() -> ViewSpec.parse(write(dir, "kind.view.yml", """
                version: tesseraql/v1
                kind: view
                recipe: dashboard
                panels:
                  - { type: chart, chart: donut, x: day, y: n }
                """)))
                .isInstanceOf(TqlException.class).hasMessageContaining("TQL-VIEW-3313");
        assertThatThrownBy(() -> ViewSpec.parse(write(dir, "both.view.yml", """
                version: tesseraql/v1
                kind: view
                recipe: dashboard
                panels:
                  - type: chart
                    x: day
                    y: n
                    series:
                      - { column: m }
                """)))
                .isInstanceOf(TqlException.class).hasMessageContaining("not both");
        assertThatThrownBy(() -> ViewSpec.parse(write(dir, "mark.view.yml", """
                version: tesseraql/v1
                kind: view
                recipe: dashboard
                panels:
                  - type: chart
                    chart: bar
                    x: day
                    series:
                      - { column: n, mark: line }
                """)))
                .isInstanceOf(TqlException.class).hasMessageContaining("chart: combo");
        assertThatThrownBy(() -> ViewSpec.parse(write(dir, "stat.view.yml", """
                version: tesseraql/v1
                kind: view
                recipe: dashboard
                panels:
                  - { type: stat, column: n, height: 100 }
                """)))
                .isInstanceOf(TqlException.class).hasMessageContaining("chart-panel keys");
        assertThatThrownBy(() -> ViewSpec.parse(write(dir, "height.view.yml", """
                version: tesseraql/v1
                kind: view
                recipe: dashboard
                panels:
                  - { type: chart, x: day, y: n, height: tall }
                """)))
                .isInstanceOf(TqlException.class).hasMessageContaining("positive integer");
    }

    @Test
    void rejectsPanelsOnANonDashboardView(@TempDir Path dir) throws Exception {
        Path file = write(dir, "x.view.yml", """
                version: tesseraql/v1
                kind: view
                recipe: list
                panels:
                  - type: stat
                    column: c
                """);
        assertThatThrownBy(() -> ViewSpec.parse(file))
                .isInstanceOf(TqlException.class).hasMessageContaining("dashboard-view key");
    }

    @Test
    void rejectsAWrongKind(@TempDir Path dir) throws Exception {
        Path file = write(dir, "x.view.yml", "kind: route\nview: list\n");
        assertThatThrownBy(() -> ViewSpec.parse(file))
                .isInstanceOf(TqlException.class).hasMessageContaining("kind must be 'view'");
    }

    @Test
    void rejectsAnUnknownViewKind(@TempDir Path dir) throws Exception {
        Path file = write(dir, "x.view.yml", "version: tesseraql/v1\nkind: view\nrecipe: wizard\n");
        assertThatThrownBy(() -> ViewSpec.parse(file))
                .isInstanceOf(TqlException.class).hasMessageContaining("recipe must be");
    }

    @Test
    void rejectsAFormWithoutAction(@TempDir Path dir) throws Exception {
        Path file = write(dir, "x.view.yml", "version: tesseraql/v1\nkind: view\nrecipe: form\n");
        assertThatThrownBy(() -> ViewSpec.parse(file))
                .isInstanceOf(TqlException.class).hasMessageContaining("action");
    }

    @Test
    void rejectsAFieldWithoutName(@TempDir Path dir) throws Exception {
        Path file = write(dir, "x.view.yml", """
                version: tesseraql/v1
                kind: view
                recipe: form
                action: /x
                fields:
                  - widget: textarea
                """);
        assertThatThrownBy(() -> ViewSpec.parse(file))
                .isInstanceOf(TqlException.class).hasMessageContaining("requires name");
    }

    // Embedding vocabulary (docs/view-composition.md wave 2b).

    @Test
    void parsesViewChildrenAndViewPanels(@TempDir Path dir) throws Exception {
        ViewSpec detail = ViewSpec.parse(write(dir, "detail.view.yml", """
                version: tesseraql/v1
                kind: view
                recipe: detail
                children:
                  - { view: history, source: audit }
                """));
        assertThat(detail.children().get(0).view()).isEqualTo("history");
        assertThat(detail.children().get(0).source()).isEqualTo("audit");

        ViewSpec dashboard = ViewSpec.parse(write(dir, "dash.view.yml", """
                version: tesseraql/v1
                kind: view
                recipe: dashboard
                panels:
                  - { type: view, view: recent }
                """));
        assertThat(dashboard.panels().get(0).type()).isEqualTo("view");
        assertThat(dashboard.panels().get(0).view()).isEqualTo("recent");
    }

    @Test
    void embeddingVocabularyValidates(@TempDir Path dir) throws Exception {
        assertThatThrownBy(() -> ViewSpec.parse(write(dir, "novi.view.yml", """
                version: tesseraql/v1
                kind: view
                recipe: dashboard
                panels:
                  - { type: view, source: main }
                """)))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("view panel requires view:");
        assertThatThrownBy(() -> ViewSpec.parse(write(dir, "stray.view.yml", """
                version: tesseraql/v1
                kind: view
                recipe: dashboard
                panels:
                  - { type: stat, source: main, column: total, view: recent }
                """)))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("view-panel key");
        assertThatThrownBy(() -> ViewSpec.parse(write(dir, "cols.view.yml", """
                version: tesseraql/v1
                kind: view
                recipe: detail
                children:
                  - view: history
                    columns:
                      - name: id
                """)))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("columns: belong inside it");
    }

    // View documents are strict at every nesting level (docs/view-composition.md wave 0,
    // TQL-VIEW-3314): a silently dropped key renders a page that quietly ignores what the
    // author wrote — the shape the procurement gallery dashboard actually shipped with.

    /**
     * The strictness promise covers values, not only key names: {@code sortable: "yes"} passed
     * {@code rejectUnknown} — the key is real — and then coerced to null, so the column rendered
     * non-sortable with nothing said (docs/silent-tolerance.md K-e).
     */
    @Test
    void rejectsAWrongTypedBooleanValue(@TempDir Path dir) throws Exception {
        Path column = write(dir, "x.view.yml", """
                version: tesseraql/v1
                kind: view
                recipe: list
                columns:
                  - name: total
                    sortable: "yes"
                """);
        assertThatThrownBy(() -> ViewSpec.parse(column))
                .isInstanceOf(TqlException.class).hasMessageContaining("sortable")
                .hasMessageContaining("true or false");

        Path panel = write(dir, "y.view.yml", """
                version: tesseraql/v1
                kind: view
                recipe: dashboard
                panels:
                  - title: Trend
                    type: chart
                    chart: line
                    legend: "false"
                """);
        assertThatThrownBy(() -> ViewSpec.parse(panel))
                .isInstanceOf(TqlException.class).hasMessageContaining("legend");
    }

    @Test
    void rejectsAnUnknownTopLevelKey(@TempDir Path dir) throws Exception {
        Path file = write(dir, "x.view.yml", """
                version: tesseraql/v1
                kind: view
                recipe: list
                layout: wide
                """);
        assertThatThrownBy(() -> ViewSpec.parse(file))
                .isInstanceOf(TqlException.class).hasMessageContaining("TQL-VIEW-3314")
                .hasMessageContaining("layout");
    }

    @Test
    void rejectsAnUnknownPanelKey(@TempDir Path dir) throws Exception {
        Path file = write(dir, "x.view.yml", """
                version: tesseraql/v1
                kind: view
                recipe: dashboard
                panels:
                  - { type: stat, source: main, column: total, label: Total }
                """);
        assertThatThrownBy(() -> ViewSpec.parse(file))
                .isInstanceOf(TqlException.class).hasMessageContaining("TQL-VIEW-3314")
                .hasMessageContaining("label");
    }

    @Test
    void rejectsUnknownEntryKeysAtEveryLevel(@TempDir Path dir) throws Exception {
        assertThatThrownBy(() -> ViewSpec.parse(write(dir, "col.view.yml", """
                version: tesseraql/v1
                kind: view
                recipe: list
                columns:
                  - { name: sku, width: 12 }
                """)))
                .isInstanceOf(TqlException.class).hasMessageContaining("TQL-VIEW-3314")
                .hasMessageContaining("width");
        assertThatThrownBy(() -> ViewSpec.parse(write(dir, "field.view.yml", """
                version: tesseraql/v1
                kind: view
                recipe: form
                action: /x
                fields:
                  - { name: note, placeholder: hint }
                """)))
                .isInstanceOf(TqlException.class).hasMessageContaining("TQL-VIEW-3314")
                .hasMessageContaining("placeholder");
        assertThatThrownBy(() -> ViewSpec.parse(write(dir, "child.view.yml", """
                version: tesseraql/v1
                kind: view
                recipe: detail
                children:
                  - { source: orders, label: Orders }
                """)))
                .isInstanceOf(TqlException.class).hasMessageContaining("TQL-VIEW-3314")
                .hasMessageContaining("label");
        assertThatThrownBy(() -> ViewSpec.parse(write(dir, "series.view.yml", """
                version: tesseraql/v1
                kind: view
                recipe: dashboard
                panels:
                  - type: chart
                    x: label
                    series:
                      - { column: stock, color: red }
                """)))
                .isInstanceOf(TqlException.class).hasMessageContaining("TQL-VIEW-3314")
                .hasMessageContaining("color");
    }
}
