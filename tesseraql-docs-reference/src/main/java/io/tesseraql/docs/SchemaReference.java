package io.tesseraql.docs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The YAML-surface reference (docs/docs-site.md): a recursive walk of
 * {@code schema/tesseraql-v1.schema.json} — the same schema the editors ship and the
 * linter's {@code SchemaSyncTest} keeps honest — rendered as one markdown page. The
 * schema only uses {@code const}/{@code enum}/{@code $ref}/{@code items}/
 * {@code additionalProperties} beside plain types, and that is exactly what this
 * renders; nothing here is hand-maintained.
 */
final class SchemaReference {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SchemaReference() {
    }

    /** Renders the whole page from the schema file. */
    static String render(Path schemaFile) throws IOException {
        JsonNode schema = MAPPER.readTree(schemaFile.toFile());
        StringBuilder md = new StringBuilder();
        md.append("# YAML surface reference\n\n")
                .append("Generated from [`tesseraql-v1.schema.json`](../tesseraql-yaml/src/"
                        + "main/resources/schema/tesseraql-v1.schema.json) — the schema the "
                        + "loader, the editors, and the linter share — on every refresh, so "
                        + "it cannot drift from what the framework accepts. One document = "
                        + "one file under an app's `routes/` tree; which root properties "
                        + "apply depends on the document's `kind`.\n");
        if (schema.hasNonNull("description")) {
            md.append('\n').append(schema.get("description").asText()).append('\n');
        }
        renderObject(md, schema, schema, "The document", 2);
        JsonNode defs = schema.get("$defs");
        if (defs != null) {
            md.append("\n## Shared definitions\n");
            for (Iterator<String> names = defs.fieldNames(); names.hasNext();) {
                String name = names.next();
                renderObject(md, defs.get(name), schema, name, 3);
            }
        }
        return md.toString();
    }

    /**
     * One object schema as a heading plus a property table; object-valued properties
     * recurse into their own subsections (titled by dotted path) so every nesting level
     * has a linkable anchor.
     */
    private static void renderObject(StringBuilder md, JsonNode node, JsonNode root,
            String title, int level) {
        md.append('\n').append("#".repeat(Math.min(level, 6))).append(' ').append(title)
                .append('\n');
        if (node.hasNonNull("description") && level > 2) {
            md.append('\n').append(node.get("description").asText()).append('\n');
        }
        JsonNode properties = node.get("properties");
        if (properties == null) {
            return;
        }
        List<String> required = new ArrayList<>();
        if (node.has("required")) {
            node.get("required").forEach(name -> required.add(name.asText()));
        }
        md.append("\n| Property | Type | Description |\n| --- | --- | --- |\n");
        List<String[]> children = new ArrayList<>();
        for (Iterator<String> names = properties.fieldNames(); names.hasNext();) {
            String name = names.next();
            JsonNode property = properties.get(name);
            String childTitle = childTitle(title, name);
            md.append("| `").append(name).append('`')
                    .append(required.contains(name) ? " \\*" : "").append(" | ")
                    .append(typeOf(property, childTitle, children, name)).append(" | ")
                    .append(ReferenceGenerator.cell(property.path("description").asText("")))
                    .append(" |\n");
        }
        for (String[] child : children) {
            renderObject(md, resolve(properties.get(child[0]), root), root, child[1],
                    level + 1);
        }
    }

    /** Subsection titles are dotted paths from the document root, e.g. {@code view.columns}. */
    private static String childTitle(String parentTitle, String name) {
        return "The document".equals(parentTitle) ? name : parentTitle + "." + name;
    }

    /** Follows the schema's only indirections: {@code $ref}, array items, map values. */
    private static JsonNode resolve(JsonNode node, JsonNode root) {
        if (node.has("$ref")) {
            return root.at(node.get("$ref").asText().substring(1));
        }
        if (node.has("items")) {
            return resolve(node.get("items"), root);
        }
        if (node.has("additionalProperties") && node.get("additionalProperties").isObject()) {
            return resolve(node.get("additionalProperties"), root);
        }
        return node;
    }

    /**
     * A property's type cell: scalars inline with their constraints; enums listed; refs
     * link to the shared definition; nested objects (direct, array items, map values)
     * queue a subsection and link down to it.
     */
    private static String typeOf(JsonNode property, String childTitle, List<String[]> children,
            String name) {
        if (property.has("$ref")) {
            return defLink(property);
        }
        if (property.has("const")) {
            return "const `" + property.get("const").asText() + "`";
        }
        if (property.has("enum")) {
            List<String> values = new ArrayList<>();
            property.get("enum").forEach(value -> values.add("`" + value.asText() + "`"));
            return "enum: " + String.join(" \\| ", values);
        }
        String type = property.path("type").asText("");
        if ("array".equals(type)) {
            JsonNode items = property.path("items");
            if (items.has("$ref")) {
                return "array of " + defLink(items);
            }
            if (items.has("properties")) {
                children.add(new String[]{name, childTitle});
                return "array of [object](#" + ReferenceGenerator.slug(childTitle) + ")";
            }
            return "array of " + items.path("type").asText("any");
        }
        if ("object".equals(type)) {
            if (property.has("properties")) {
                children.add(new String[]{name, childTitle});
                return "[object](#" + ReferenceGenerator.slug(childTitle) + ")";
            }
            JsonNode values = property.get("additionalProperties");
            if (values != null && values.isObject()) {
                if (values.has("$ref")) {
                    return "map of " + defLink(values);
                }
                if (values.has("properties")) {
                    children.add(new String[]{name, childTitle});
                    return "map of [object](#" + ReferenceGenerator.slug(childTitle) + ")";
                }
                return "map of " + values.path("type").asText("any");
            }
            return "object";
        }
        StringBuilder cell = new StringBuilder(type.isEmpty() ? "any" : type);
        if (property.has("pattern")) {
            cell.append(" matching `").append(property.get("pattern").asText()).append('`');
        }
        if (property.has("minLength")) {
            cell.append(", min length ").append(property.get("minLength").asInt());
        }
        if (property.has("minimum")) {
            cell.append(" ≥ ").append(property.get("minimum").asInt());
        }
        if (property.has("maximum")) {
            cell.append(" ≤ ").append(property.get("maximum").asInt());
        }
        return ReferenceGenerator.cell(cell.toString());
    }

    private static String defLink(JsonNode node) {
        String def = node.get("$ref").asText().replaceAll(".*/", "");
        return "[" + def + "](#" + ReferenceGenerator.slug(def) + ")";
    }
}
