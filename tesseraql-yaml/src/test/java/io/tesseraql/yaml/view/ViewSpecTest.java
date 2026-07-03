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
                view: list
                title: view.items.title
                columns:
                  - name: name
                    link: /items/{id}
                  - name: due_date
                    label: Due
                """));
        assertThat(spec.view()).isEqualTo(ViewSpec.LIST);
        assertThat(spec.id()).isEqualTo("items");
        assertThat(spec.source()).isEqualTo("sql");
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
                view: form
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
                view: detail
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
                kind: view
                view: list
                children:
                  - source: orders
                """);
        assertThatThrownBy(() -> ViewSpec.parse(file))
                .isInstanceOf(TqlException.class).hasMessageContaining("detail-view key");
    }

    @Test
    void rejectsAChildWithoutSource(@TempDir Path dir) throws Exception {
        Path file = write(dir, "x.view.yml", """
                kind: view
                view: detail
                children:
                  - title: Orders
                """);
        assertThatThrownBy(() -> ViewSpec.parse(file))
                .isInstanceOf(TqlException.class).hasMessageContaining("requires source");
    }

    @Test
    void rejectsAWrongKind(@TempDir Path dir) throws Exception {
        Path file = write(dir, "x.view.yml", "kind: route\nview: list\n");
        assertThatThrownBy(() -> ViewSpec.parse(file))
                .isInstanceOf(TqlException.class).hasMessageContaining("kind must be 'view'");
    }

    @Test
    void rejectsAnUnknownViewKind(@TempDir Path dir) throws Exception {
        Path file = write(dir, "x.view.yml", "kind: view\nview: wizard\n");
        assertThatThrownBy(() -> ViewSpec.parse(file))
                .isInstanceOf(TqlException.class).hasMessageContaining("view must be");
    }

    @Test
    void rejectsAFormWithoutAction(@TempDir Path dir) throws Exception {
        Path file = write(dir, "x.view.yml", "kind: view\nview: form\n");
        assertThatThrownBy(() -> ViewSpec.parse(file))
                .isInstanceOf(TqlException.class).hasMessageContaining("action");
    }

    @Test
    void rejectsAFieldWithoutName(@TempDir Path dir) throws Exception {
        Path file = write(dir, "x.view.yml", """
                kind: view
                view: form
                action: /x
                fields:
                  - widget: textarea
                """);
        assertThatThrownBy(() -> ViewSpec.parse(file))
                .isInstanceOf(TqlException.class).hasMessageContaining("requires name");
    }
}
