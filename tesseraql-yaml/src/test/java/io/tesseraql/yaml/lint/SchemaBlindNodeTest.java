package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.yaml.scaffold.AppScaffolder;
import io.tesseraql.yaml.scaffold.ScaffoldedFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * No shipped schema node describes nothing.
 *
 * <p>{@code {"type": "object", "additionalProperties": true}} with no {@code properties} is a
 * schema that validates everything and completes nothing: the editor offers no keys and the
 * generated reference renders the row as a bare "object" or "array of any". Twenty-five such
 * nodes shipped across six files when this campaign started (docs/yaml-surface-drift.md).
 *
 * <p><strong>This reads only the schema files.</strong> It does not compare them to the model —
 * the model is a cyclic graph ({@code InputField.items} &rarr; {@code InputItems.fields} &rarr;
 * {@code Map<String, InputField>}) and a walk over it cannot terminate. The claim here is
 * strictly smaller and the assertion messages say so: "describes nothing" is not "matches the
 * model". The schema-to-model checks live in {@link SchemaSyncTest}, shape by shape.
 *
 * <p><strong>This guard was green on arrival</strong> — the fixes landed first, because dropping
 * a shrinking ledger leaves nothing to absorb them. Its proof is therefore the broken-variant
 * suite recorded in the pull request, not a red run: an honest node turned blind, a blind node in
 * a file the guard never named, a new schema file, an allow-listed node that got fixed, the four
 * empty-container shapes, and a directory that does not exist.
 */
class SchemaBlindNodeTest {

    /** The source tree, not the classpath: the file set is derived by listing the directory. */
    private static final Path SCHEMAS = Path.of("src/main/resources/schema");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * The nodes that describe nothing <em>because the value really is free-form</em>.
     *
     * <p>Every entry names the Java type that makes it so. An entry whose reason is prose nothing
     * checks is the mechanical response to a red guard, so the reason has to be a claim a reader
     * can go and verify — and the test fails when an entry stops being blind, so the list cannot
     * quietly outlive the shape that justified it.
     */
    private static final Map<String, String> FREE_FORM = new LinkedHashMap<>();

    static {
        FREE_FORM.put("tesseraql-config-v1.schema.json"
                + "#/properties/tesseraql/properties/connectors/properties/poll"
                + "/properties/credentials/additionalProperties",
                "a per-connector secret bag: no record, the keys differ by transport");
        FREE_FORM.put("tesseraql-decisions-v1.schema.json"
                + "#/properties/decisions/additionalProperties/properties/outputs"
                + "/additionalProperties/properties/enum",
                "the output's value space: any scalar the table yields");
        FREE_FORM.put("tesseraql-decisions-v1.schema.json"
                + "#/properties/decisions/additionalProperties/properties/default",
                "outputs keyed by the table's own declared output names");
        FREE_FORM.put("tesseraql-decisions-v1.schema.json"
                + "#/properties/decisions/additionalProperties/properties/rows/items"
                + "/properties/when",
                "cells keyed by the table's own declared input names");
        FREE_FORM.put("tesseraql-decisions-v1.schema.json"
                + "#/properties/decisions/additionalProperties/properties/rows/items"
                + "/properties/outputs",
                "cells keyed by the table's own declared output names");
        FREE_FORM.put("tesseraql-defs-v1.schema.json#/$defs/inputField/properties/default",
                "InputField.defaultValue is an Object: any scalar the field's type accepts");
        FREE_FORM.put("tesseraql-route-v1.schema.json"
                + "#/properties/response/properties/json/properties/body",
                "ResponseSpec.JsonResponse.body is an Object: arbitrary JSON");
        FREE_FORM.put("tesseraql-route-v1.schema.json"
                + "#/properties/response/properties/json/properties/headers",
                "JsonResponse.headers is Map<String, Object>: ResponseHeaders interpolates a "
                        + "nested map or list recursively and JSON-serializes it");
        FREE_FORM.put("tesseraql-route-v1.schema.json"
                + "#/properties/response/properties/html/properties/headers",
                "HtmlResponse.headers is Map<String, Object>, as on the json arm");
        FREE_FORM.put("tesseraql-tests-v1.schema.json"
                + "#/properties/tests/items/properties/principal/properties/claims",
                "TestSuite.PrincipalSpec.claims is Map<String, Object>: a token's claims");
        FREE_FORM.put("tesseraql-tests-v1.schema.json#/properties/tests/items/properties/params",
                "TestSuite.TestCase.params is Map<String, Object>: bind values");
        FREE_FORM.put("tesseraql-tests-v1.schema.json"
                + "#/properties/tests/items/properties/expect/properties/rows/items",
                "TestSuite.Expectation.rows is List<Map<String, Object>>: a result row");
        FREE_FORM.put("tesseraql-tests-v1.schema.json"
                + "#/properties/tests/items/properties/verify/items/properties/params",
                "TestSuite.VerifyStep.params is Map<String, Object>: bind values");
        FREE_FORM.put("tesseraql-tests-v1.schema.json"
                + "#/properties/tests/items/properties/verify/items/properties"
                + "/expect/properties/rows/items",
                "TestSuite.Expectation.rows, as on the case's own expect:");
    }

    @Test
    void everySchemaNodeDescribesSomething() throws IOException {
        List<String> files = schemaFiles();
        List<String> blind = new ArrayList<>();
        for (String file : files) {
            JsonNode root = MAPPER.readTree(SCHEMAS.resolve(file).toFile());
            descend(root, file + "#", blind);
        }

        // Non-vacuity, per root rather than globally: a walk that stopped descending, or a
        // directory listing that came back short, would satisfy the assertion below by finding
        // nothing at all. These floors are what a disappearing root actually trips.
        assertThat(files).as("the schema directory lists its files").hasSizeGreaterThan(10);
        assertThat(blind).as("the walk reaches the nodes the allow-list names")
                .hasSizeGreaterThanOrEqualTo(FREE_FORM.size());

        assertThat(blind)
                .as("no schema node describes nothing — an object with no properties completes"
                        + " nothing in the editor and renders as a bare type in the reference."
                        + " This is not a claim that the schemas match the model.")
                .containsExactlyInAnyOrderElementsOf(FREE_FORM.keySet());
    }

    /**
     * The allow-list may only name nodes that are still blind.
     *
     * <p>The stale direction. Without it the list silently accumulates entries for shapes that
     * were described years ago, and the next reader trusts a silence the guard never earned.
     * {@code containsExactlyInAnyOrder} above already fails on a stale entry; this states the
     * failure in its own words so the reason is legible rather than inferred from a set diff.
     */
    @Test
    void theAllowListNamesOnlyNodesThatAreStillBlind() throws IOException {
        List<String> blind = new ArrayList<>();
        for (String file : schemaFiles()) {
            descend(MAPPER.readTree(SCHEMAS.resolve(file).toFile()), file + "#", blind);
        }
        assertThat(blind).as("the walk found nodes to compare against").isNotEmpty();

        List<String> fixed = FREE_FORM.keySet().stream().filter(at -> !blind.contains(at)).toList();
        assertThat(fixed)
                .as("an allow-listed node that now describes something: delete its entry, and the"
                        + " reason with it")
                .isEmpty();
    }

    /**
     * The file set is derived, and it is the set a scaffolded app receives.
     *
     * <p>A guard over a hand-kept list goes blind to a new file the day it is added, and this
     * repository already keeps four such lists. Listing the directory is what makes a new schema
     * arrive inside the walk; comparing it to {@link AppScaffolder}'s output is what catches the
     * other half — a schema added to the source tree and never shipped to an author's editor.
     */
    @Test
    void everySchemaInTheDirectoryIsShippedToTheEditor() throws IOException {
        Set<String> shipped = new TreeSet<>();
        for (ScaffoldedFile file : new AppScaffolder().scaffold("drift-check")) {
            if (file.path().startsWith(".vscode/") && file.path().endsWith(".schema.json")) {
                shipped.add(file.path().substring(".vscode/".length()));
            }
        }
        assertThat(shipped).as("the scaffolder ships schemas at all").hasSizeGreaterThan(10);
        assertThat(schemaFiles())
                .as("every schema in the source tree reaches a scaffolded app's editor, and the"
                        + " editor is offered no schema that does not exist")
                .containsExactlyInAnyOrderElementsOf(shipped);
    }

    /**
     * Every {@code $ref} lands on a node that exists.
     *
     * <p>A {@code $ref} counts as "describes something" in the predicate above with no check that
     * it resolves, and seven of this campaign's fixes were {@code $ref} promotions — so without
     * this the blind-node guard is green on its own fix's most likely failure. It resolves
     * against the shared definitions the way {@code SchemaReference} does, which is also the only
     * resolution the reference generator performs.
     */
    @Test
    void everyRefResolves() throws IOException {
        Map<String, JsonNode> documents = new LinkedHashMap<>();
        for (String file : schemaFiles()) {
            documents.put(file, MAPPER.readTree(SCHEMAS.resolve(file).toFile()));
        }
        List<String> refs = new ArrayList<>();
        List<String> dangling = new ArrayList<>();
        documents.forEach((file, root) -> collectRefs(root, file, refs, dangling, documents));

        assertThat(refs).as("the schemas still cross-reference each other").hasSizeGreaterThan(30);
        assertThat(dangling).as("every $ref lands on a node that exists").isEmpty();
    }

    /** The schema basenames, listed from the directory rather than named here. */
    private static List<String> schemaFiles() throws IOException {
        try (Stream<Path> entries = Files.list(SCHEMAS)) {
            return entries.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".schema.json")).sorted().toList();
        }
    }

    /**
     * Collects every {@code $ref} and the ones that land nowhere.
     *
     * <p>A reference resolves against the file it names, and a bare {@code #/…} against its own
     * document — {@code tesseraql-messages-v1} refs its local {@code $defs/messageNode} that way.
     * The first version resolved everything against the shared definitions, mirroring
     * {@code SchemaReference}, and reported that legitimate local reference as dangling. That is
     * the generator's limitation rather than the schemas' contract, and it is exactly why a new
     * shape belongs in {@code tesseraql-defs-v1} rather than a local {@code $defs}
     * (docs/yaml-surface-drift.md decision 6).
     */
    private static void collectRefs(JsonNode node, String file, List<String> refs,
            List<String> dangling, Map<String, JsonNode> documents) {
        if (node.isObject()) {
            String ref = node.path("$ref").asText("");
            if (!ref.isEmpty()) {
                refs.add(ref);
                int hash = ref.indexOf('#');
                String target = hash <= 0 ? file : ref.substring(0, hash);
                JsonNode document = documents.get(target);
                if (document == null
                        || document.at(ref.substring(hash + 1)).isMissingNode()) {
                    dangling.add(file + " -> " + ref);
                }
            }
            node.properties().forEach(
                    entry -> collectRefs(entry.getValue(), file, refs, dangling, documents));
        } else if (node.isArray()) {
            node.forEach(child -> collectRefs(child, file, refs, dangling, documents));
        }
    }

    /**
     * Whether a node says anything at all about the value it governs.
     *
     * <p>Every clause asks present <em>and non-empty</em>: {@code properties: {}}, {@code allOf:
     * []}, {@code enum: []}, {@code items: {}} and {@code additionalProperties: {}} each turn a
     * presence-only predicate green on a node that describes nothing. The corpus happens to
     * contain none of those shapes, which is why the behaviour is untested rather than why it is
     * safe.
     *
     * <p>{@code required} and {@code not} count <strong>only on a combinator branch</strong>.
     * Four {@code oneOf} branches in the calendars and decisions schemas are constraint-only —
     * {@code {"required": ["dates"], "not": {"required": ["source"]}}} — layered on top of a
     * sibling {@code properties}, and those are honest. At a plain schema position they are not:
     * {@code {"type": "object", "required": ["location"], "additionalProperties": true}} says one
     * key must be present while the editor is still offered none. Counting {@code required}
     * everywhere made this predicate green on three separate broken variants, including the one
     * written to check exactly this — an honest node stripped of its properties.
     */
    private static boolean describes(JsonNode node, boolean branch) {
        if (!node.path("$ref").asText("").isEmpty() || node.has("const")) {
            return true;
        }
        List<String> arrays = branch
                ? List.of("allOf", "oneOf", "anyOf", "prefixItems", "enum", "required")
                : List.of("allOf", "oneOf", "anyOf", "prefixItems", "enum");
        for (String keyword : arrays) {
            JsonNode value = node.path(keyword);
            if (value.isArray() && !value.isEmpty()) {
                return true;
            }
        }
        List<String> objects = branch
                ? List.of("properties", "patternProperties", "additionalProperties", "items",
                        "not")
                : List.of("properties", "patternProperties", "additionalProperties", "items");
        for (String keyword : objects) {
            JsonNode value = node.path(keyword);
            if (value.isObject() && !value.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** A schema-position node: checked, then descended. */
    private static void check(JsonNode node, String at, List<String> blind, boolean branch) {
        if (!node.isObject()) {
            return;
        }
        if (!describesByType(node) && !describes(node, branch)) {
            blind.add(at);
        }
        descend(node, at, blind);
    }

    /**
     * Whether {@code type} alone already says what the value is.
     *
     * <p>A scalar type describes itself. {@code type} may also be a <em>list</em> of them — the
     * view schema's preset params are {@code {"type": ["string", "number", "boolean"]}}, a scalar
     * union that describes its value perfectly well. Reading that node's type with
     * {@code asText()} returns the empty string, so the first version of this predicate reported
     * it as describing nothing; the guard's own first run is what surfaced it.
     */
    private static boolean describesByType(JsonNode node) {
        JsonNode type = node.path("type");
        if (type.isTextual()) {
            return !"object".equals(type.asText()) && !"array".equals(type.asText());
        }
        if (!type.isArray() || type.isEmpty()) {
            return false;
        }
        for (JsonNode member : type) {
            if ("object".equals(member.asText()) || "array".equals(member.asText())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Descends into the positions that hold a schema.
     *
     * <p>Position decides what is a schema, not shape: the values of {@code properties},
     * {@code patternProperties}, {@code items}, {@code additionalProperties} and the branches of
     * the combinators. A {@code $defs} entry is the exception — it may be a <em>namespace</em>
     * grouping further definitions, as {@code tesseraql-defs-v1#/$defs/shared} groups
     * {@code version}, {@code id}, {@code input}, {@code datasource}, {@code import} and
     * {@code export}. A walk that treats every {@code $defs} child as a schema reports that
     * namespace as blind <em>and never descends into it</em>, which hid a real drifted node
     * ({@code shared.export.after.sql}) from my own first pass.
     */
    private static void descend(JsonNode node, String at, List<String> blind) {
        node.properties().forEach(entry -> {
            String key = entry.getKey();
            JsonNode value = entry.getValue();
            if (List.of("properties", "patternProperties").contains(key) && value.isObject()) {
                value.properties().forEach(
                        child -> check(child.getValue(), at + "/" + key + "/" + child.getKey(),
                                blind, false));
            } else if (List.of("items", "additionalProperties", "unevaluatedProperties")
                    .contains(key) && value.isObject()) {
                check(value, at + "/" + key, blind, false);
            } else if (List.of("allOf", "oneOf", "anyOf", "prefixItems").contains(key)
                    && value.isArray()) {
                for (int i = 0; i < value.size(); i++) {
                    check(value.get(i), at + "/" + key + "/" + i, blind, true);
                }
            } else if ("$defs".equals(key) && value.isObject()) {
                value.properties().forEach(
                        child -> definition(child.getValue(), at + "/$defs/" + child.getKey(),
                                blind));
            }
        });
    }

    /** A {@code $defs} entry: a schema, or a namespace holding more of them. */
    private static void definition(JsonNode node, String at, List<String> blind) {
        if (!node.isObject()) {
            return;
        }
        boolean schema = List.of("type", "properties", "$ref", "allOf", "oneOf", "anyOf", "items",
                "additionalProperties", "patternProperties", "enum", "const", "description")
                .stream().anyMatch(node::has);
        if (schema) {
            check(node, at, blind, false);
        } else {
            node.properties().forEach(
                    child -> definition(child.getValue(), at + "/" + child.getKey(), blind));
        }
    }
}
