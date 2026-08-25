package io.tesseraql.yaml.fragments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestLoader;
import io.tesseraql.yaml.manifest.RouteFile;
import io.tesseraql.yaml.model.Binding;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Shared step fragments (docs/transactional-writes.md, "Shared step fragments"): a named
 * sequence declared once, expanded at manifest load so everything downstream sees ordinary
 * steps.
 */
class StepFragmentsTest {

    private static void writeApp(Path dir, String fragments, String steps) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"),
                "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("fragments"));
        Files.writeString(dir.resolve("fragments/audit-note.sql"),
                "insert into audit_note (entity, entity_id, note) values"
                        + " (/* entity */ 'x', /* entityId */ 'y', /* note */ 'z')\n");
        Files.writeString(dir.resolve("fragments/audit.yml"), fragments);
        Files.createDirectories(dir.resolve("web/api/things"));
        Files.writeString(dir.resolve("web/api/things/create.sql"),
                "insert into things (title) values (/* title */ 'x')\n");
        Files.writeString(dir.resolve("web/api/things/post.yml"), """
                version: tesseraql/v1
                id: api.things.create
                kind: route
                recipe: command-json
                input:
                  title: { type: string, required: true }
                steps:
                %s
                response:
                  json:
                    status: 201
                    body: { id: steps.header.keys.id }
                """.formatted(steps));
    }

    private static final String FRAGMENT = """
            version: tesseraql/v1
            fragments:
              audit-note:
                binds: { entity: string, entityId: string, note: string }
                steps:
                  - id: note
                    sql:
                      file: audit-note.sql
                      mode: update
                      params:
                        entity: binds.entity
                        entityId: binds.entityId
                        note: binds.note
            """;

    private static final String STEPS = """
            - id: header
              sql: { file: create.sql, keys: [id], params: { title: params.title } }
            - id: audit
              use:
                fragment: audit-note
                params:
                  entity: "'thing'"
                  entityId: steps.header.keys.id
                  note: params.title""";

    private static Map<String, Binding> steps(Path dir) {
        AppManifest manifest = new ManifestLoader().load(dir);
        RouteFile route = manifest.routes().stream()
                .filter(file -> "api.things.create".equals(file.definition().id()))
                .findFirst().orElseThrow();
        return route.definition().steps();
    }

    /**
     * The expansion is total: an ordinary step map, prefixed ids, the reference's wiring in
     * place of the binds, and the SQL re-pathed to where it sits beside the fragment.
     */
    @Test
    void aFragmentExpandsIntoOrdinarySteps(@TempDir Path dir) throws Exception {
        writeApp(dir, FRAGMENT, STEPS);

        Map<String, Binding> steps = steps(dir);

        assertThat(steps).containsOnlyKeys("header", "audit_note");
        Binding note = steps.get("audit_note");
        assertThat(note.file()).isEqualTo("../../../fragments/audit-note.sql");
        assertThat(note.mode()).isEqualTo("update");
        assertThat(note.params())
                .containsEntry("entity", "'thing'")
                .containsEntry("entityId", "steps.header.keys.id")
                .containsEntry("note", "params.title");
        assertThat(note.usesFragment()).isFalse();
    }

    /** A typo must not silently drop the sequence it names. */
    @Test
    void anUnknownFragmentFailsTheLoad(@TempDir Path dir) throws Exception {
        writeApp(dir, FRAGMENT, STEPS.replace("fragment: audit-note", "fragment: audit-notes"));

        assertThatThrownBy(() -> steps(dir))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-YAML-1060");
    }

    /** The reference wires only two of the three declared binds. */
    private static final String MISSING_BIND = """
            - id: header
              sql: { file: create.sql, keys: [id], params: { title: params.title } }
            - id: audit
              use:
                fragment: audit-note
                params:
                  entity: "'thing'"
                  entityId: steps.header.keys.id""";

    /** The reference wires a bind the fragment never declared. */
    private static final String EXTRA_BIND = """
            - id: header
              sql: { file: create.sql, keys: [id], params: { title: params.title } }
            - id: audit
              use:
                fragment: audit-note
                params:
                  entity: "'thing'"
                  entityId: steps.header.keys.id
                  note: params.title
                  extra: params.title""";

    /** The contract is checked both ways: a missing bind and an undeclared one. */
    @Test
    void aReferenceMissingABindFailsTheLoad(@TempDir Path dir) throws Exception {
        writeApp(dir, FRAGMENT, MISSING_BIND);

        assertThatThrownBy(() -> steps(dir))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-YAML-1061")
                .hasMessageContaining("note");
    }

    @Test
    void aReferenceWiringAnUndeclaredBindFailsTheLoad(@TempDir Path dir) throws Exception {
        writeApp(dir, FRAGMENT, EXTRA_BIND);

        assertThatThrownBy(() -> steps(dir))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-YAML-1061")
                .hasMessageContaining("extra");
    }

    /** One hop, never a chain — so a reader of the expansion sees the whole of it. */
    @Test
    void aFragmentUsingAnotherFragmentFailsTheLoad(@TempDir Path dir) throws Exception {
        writeApp(dir, FRAGMENT + """
                  chained:
                    binds: {}
                    steps:
                      - id: inner
                        use: { fragment: audit-note, params: {} }
                """, STEPS);

        assertThatThrownBy(() -> steps(dir))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-YAML-1062");
    }

    /** A step id is a name; an expansion that collides with one leaves a reference ambiguous. */
    @Test
    void anExpansionCollidingWithADeclaredStepFailsTheLoad(@TempDir Path dir) throws Exception {
        writeApp(dir, FRAGMENT, STEPS + """

                - id: audit_note
                  sql: { file: create.sql, params: { title: params.title } }""");

        assertThatThrownBy(() -> steps(dir))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-YAML-1062");
    }

    /** A document with no use: is untouched — the pass must cost nothing where nothing uses it. */
    @Test
    void aDocumentWithoutFragmentsIsUnchanged(@TempDir Path dir) throws Exception {
        writeApp(dir, FRAGMENT, """
                - id: header
                  sql: { file: create.sql, keys: [id], params: { title: params.title } }""");

        assertThat(steps(dir)).containsOnlyKeys("header");
    }
}
