package io.tesseraql.docs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The YAML-surface reference (docs/docs-site.md): a recursive walk of the shipped JSON
 * Schemas — the same files the editors ship and the linter's {@code SchemaSyncTest} keeps
 * honest — rendered as one markdown page. The schemas only use {@code const}/{@code enum}/
 * {@code $ref}/{@code items}/{@code additionalProperties} beside plain types, and that is
 * exactly what this renders; nothing here is hand-maintained.
 *
 * <p>One schema per document kind (docs/unified-sources.md), so the page has a section per
 * kind rather than one table whose rows apply to whichever kind the reader happens to be
 * writing. A {@code $ref} into the shared definitions file is followed the same way an
 * internal one is: the shared node is merged into the resolution root, so a reference to a
 * value shape still links to the one section describing it.
 */
final class SchemaReference {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The path prefix marking a shared *property* definition, inlined rather than linked. */
    private static final String SHARED_PROPERTY = "/$defs/shared/";

    private SchemaReference() {
    }

    /** One document kind: the schema file and the heading its section carries. */
    record DocumentKind(String title, Path schemaFile) {
    }

    /**
     * Renders the whole page: a section per document kind, then the shared-definition
     * documents, then the value shapes every kind refers to.
     *
     * @param defsFile      the shared definitions, resolved into every kind's refs
     * @param documentKinds route, job and view — the kinds with an id: and a kind:
     * @param otherKinds    the shared-definition documents (domains, rules, decisions)
     */
    static String render(Path defsFile, List<DocumentKind> documentKinds,
            List<DocumentKind> otherKinds) throws IOException {
        JsonNode defs = MAPPER.readTree(defsFile.toFile());
        StringBuilder md = new StringBuilder();
        md.append("# YAML surface reference\n\n")
                .append("Generated from the [shipped JSON Schemas](https://github.com/"
                        + "ingcreators/tesseraql/tree/main/tesseraql-yaml/src/main/resources/"
                        + "schema) — the ones the loader, the editors, and the "
                        + "linter share — on every refresh, so this page cannot drift from "
                        + "what the framework accepts. One document = one file in the app "
                        + "tree, and each kind has its own schema: routes under `web/` and "
                        + "`consume/`, jobs under `batch/`, views beside the route they "
                        + "serve.\n");
        for (DocumentKind kind : documentKinds) {
            JsonNode schema = MAPPER.readTree(kind.schemaFile().toFile());
            renderObject(md, schema, defs, kind.title(), 2);
        }

        // The shared-definition documents are their own kind — no id:, no kind: — so they have
        // their own schemas, and a page generated from the route schema alone documented
        // neither. A reader had no way to learn the surface existed.
        if (!otherKinds.isEmpty()) {
            md.append("\n## Other document kinds\n\n")
                    .append("Shared definitions live in their own documents, referenced from "
                            + "routes rather than repeated in them. Each has its own schema and "
                            + "its own file association.\n");
            for (DocumentKind kind : otherKinds) {
                JsonNode document = MAPPER.readTree(kind.schemaFile().toFile());
                renderObject(md, document, defs, kind.title(), 3);
            }
        }

        md.append("\n## Shared definitions\n");
        JsonNode shapes = defs.get("$defs");
        for (Iterator<String> names = shapes.fieldNames(); names.hasNext();) {
            String name = names.next();
            // The shared *property* definitions are inlined into each kind's table above;
            // only the value shapes get a section of their own.
            if (!"shared".equals(name)) {
                renderObject(md, shapes.get(name), defs, name, 3);
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
        if (node.hasNonNull("description")) {
            md.append('\n').append(node.get("description").asText()).append('\n');
        }
        JsonNode properties = node.get("properties");
        if (properties == null) {
            // An array- or map-valued definition (e.g. statusWhen) documents its
            // element shape: resolve through items/additionalProperties first.
            JsonNode resolved = resolve(node, root);
            properties = resolved.get("properties");
            if (properties == null) {
                return;
            }
            node = resolved;
        }
        List<String> required = new ArrayList<>();
        if (node.has("required")) {
            node.get("required").forEach(name -> required.add(name.asText()));
        }
        md.append("\n| Property | Type | Description |\n| --- | --- | --- |\n");
        List<String[]> children = new ArrayList<>();
        for (Iterator<String> names = properties.fieldNames(); names.hasNext();) {
            String name = names.next();
            JsonNode property = shared(properties.get(name), root);
            String childTitle = childTitle(title, name);
            // A property that is nothing but a $ref carries its description at the target; the
            // reader wants the sentence in the row, not only behind the link.
            String description = property.path("description").asText("");
            if (description.isBlank() && property.has("$ref")) {
                description = resolve(property, root).path("description").asText("");
            }
            md.append("| `").append(name).append('`')
                    .append(required.contains(name) ? " \\*" : "").append(" | ")
                    .append(typeOf(property, childTitle, children, name)).append(" | ")
                    // An undescribed property renders as an em dash, not a blank cell — the row is
                    // still visible as an undocumented key (the ErrorIndex "—" convention).
                    .append(description.isBlank() ? "—" : ReferenceGenerator.cell(description))
                    .append(" |\n");
        }
        for (String[] child : children) {
            renderObject(md, resolve(shared(properties.get(child[0]), root), root), root,
                    child[1], level + 1);
        }
    }

    /**
     * A key several kinds declare identically is stored once and referenced; the reader wants
     * the declaration, not a pointer, so it is inlined into the kind's own table. Value shapes
     * ({@code inputField}, {@code sqlBinding}) keep their reference and their own section.
     */
    private static JsonNode shared(JsonNode property, JsonNode defs) {
        return property.path("$ref").asText("").contains(SHARED_PROPERTY)
                ? resolve(property, defs)
                : property;
    }

    /**
     * Subsection titles are dotted paths from the document root, e.g. {@code response.json}.
     * A kind's own section is the root, so its children carry the key alone.
     */
    private static String childTitle(String parentTitle, String name) {
        return parentTitle.endsWith("documents") ? name : parentTitle + "." + name;
    }

    /**
     * Follows the schemas' only indirections: {@code $ref}, {@code allOf}, array items, map
     * values. A cross-file {@code $ref} into the shared definitions resolves against that file,
     * which is the {@code defs} root every render call carries.
     */
    private static JsonNode resolve(JsonNode node, JsonNode defs) {
        if (node.has("$ref")) {
            String ref = node.get("$ref").asText();
            return defs.at(ref.substring(ref.indexOf('#') + 1));
        }
        if (node.has("allOf")) {
            return merged(node.get("allOf"), defs);
        }
        if (node.has("items")) {
            return resolve(node.get("items"), defs);
        }
        if (node.has("additionalProperties") && node.get("additionalProperties").isObject()) {
            return resolve(node.get("additionalProperties"), defs);
        }
        return node;
    }

    /**
     * One object from an {@code allOf}'s branches, so a shape composed of several renders as the
     * shape an author writes.
     *
     * <p>A pipeline step is `allOf: [binding, {id, output blocks}]` — two branches because the
     * arms are shared with every other binding and the outputs are the step's own. The reader
     * writes one map, and the page said `array of any`: the most important shape on a job
     * document was the one it did not document.
     */
    private static JsonNode merged(JsonNode branches, JsonNode defs) {
        ObjectNode flattened = MAPPER.createObjectNode();
        ObjectNode properties = flattened.putObject("properties");
        ArrayNode required = flattened.putArray("required");
        flattened.put("type", "object");
        for (JsonNode branch : branches) {
            JsonNode resolved = resolve(branch, defs);
            JsonNode branchProperties = resolved.path("properties");
            for (Iterator<String> names = branchProperties.fieldNames(); names.hasNext();) {
                String name = names.next();
                properties.set(name, branchProperties.get(name));
            }
            resolved.path("required").forEach(required::add);
        }
        // No description: a merged shape's own is the property's, which the reader has just
        // read in the row that linked here. A branch's would describe one half as if it were
        // the whole.
        return flattened;
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
            if (items.has("properties") || items.has("allOf")) {
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
