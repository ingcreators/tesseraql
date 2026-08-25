package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Every property in the shipped schema carries a {@code description}, because a description here
 * is documentation in three places at once: the generated YAML surface reference, the VS Code
 * extension's hover text, and Studio's editor.
 *
 * <p>Half of them did not, so 102 rows of the published reference read {@code —} — including
 * {@code security}, {@code sql}, {@code response}, {@code columns} and {@code fields}, the keys a
 * reader is most likely to look up (docs/documentation-ia.md). This test is the floor that keeps
 * the next added key from arriving undocumented.
 */
class SchemaDescriptionCoverageTest {

    @Test
    void everySchemaPropertyIsDescribed() throws Exception {
        List<String> undescribed = new ArrayList<>();
        for (String resource : List.of(
                "/schema/tesseraql-defs-v1.schema.json",
                "/schema/tesseraql-route-v1.schema.json",
                "/schema/tesseraql-job-v1.schema.json",
                "/schema/tesseraql-view-v1.schema.json",
                "/schema/tesseraql-document-v1.schema.json",
                "/schema/tesseraql-domains-v1.schema.json",
                "/schema/tesseraql-rules-v1.schema.json",
                "/schema/tesseraql-decisions-v1.schema.json",
                "/schema/tesseraql-catalogs-v1.schema.json")) {
            JsonNode schema = new ObjectMapper().readTree(getClass().getResourceAsStream(resource));
            collectUndescribed(schema, resource, undescribed);
        }

        assertThat(undescribed)
                .as("schema properties with no description: each is a blank row in the "
                        + "published reference and a blank tooltip in both editors")
                .isEmpty();
    }

    /**
     * Walks every {@code properties} map in the document, wherever it is nested. A property
     * that is nothing but a {@code $ref} carries its description at the target — demanding one
     * here would push a second, drifting copy back into each kind's file, which is what the
     * shared definitions exist to prevent.
     */
    private static void collectUndescribed(JsonNode node, String path, List<String> out) {
        if (node.isObject()) {
            JsonNode properties = node.get("properties");
            if (properties != null && properties.isObject()) {
                for (Iterator<String> names = properties.fieldNames(); names.hasNext();) {
                    String name = names.next();
                    JsonNode property = properties.get(name);
                    if (!property.hasNonNull("description") && !property.has("$ref")) {
                        out.add(path + " -> " + name);
                    }
                }
            }
            for (Iterator<String> names = node.fieldNames(); names.hasNext();) {
                collectUndescribed(node.get(names.next()), path, out);
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                collectUndescribed(child, path, out);
            }
        }
    }
}
