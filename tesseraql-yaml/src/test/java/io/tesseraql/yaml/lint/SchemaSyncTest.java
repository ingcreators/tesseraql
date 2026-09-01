package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The shipped JSON Schema stays in sync with the linter (authoring feedback, roadmap Phase 43):
 * a recipe the linter accepts must appear in the schema's enum, or editors would flag valid
 * documents — machine-checked here instead of hoped for.
 */
class SchemaSyncTest {

    /**
     * Property coverage, not just enum coverage.
     *
     * <p>The enum checks caught a missing recipe, but nothing checked that a *key* the model
     * accepts appears in the schema at all — so `domain:` and `use:` shipped, and the published
     * YAML surface (generated straight from this file) documented neither. A reader concluded
     * they did not exist. Reflecting over the model record is what makes the next added field
     * fail here rather than go quietly missing.
     */
    @Test
    void schemaInputFieldCoversEveryModelComponent() throws Exception {
        JsonNode schema = new ObjectMapper().readTree(
                getClass().getResourceAsStream("/schema/tesseraql-defs-v1.schema.json"));
        JsonNode properties = schema.path("$defs").path("inputField").path("properties");

        List<String> declared = new ArrayList<>();
        for (var component : io.tesseraql.yaml.model.InputField.class.getRecordComponents()) {
            // The YAML name, not the Java one: default/enum are reserved words, so those two
            // components carry a @JsonProperty rename. A record component's annotations land on
            // the backing field, not on the component mirror.
            String name = component.getName();
            try {
                var field = io.tesseraql.yaml.model.InputField.class.getDeclaredField(name);
                var renamed = field.getAnnotation(
                        com.fasterxml.jackson.annotation.JsonProperty.class);
                if (renamed != null && !renamed.value().isBlank()) {
                    name = renamed.value();
                }
            } catch (NoSuchFieldException impossibleForARecord) {
                throw new IllegalStateException(impossibleForARecord);
            }
            declared.add(name);
        }
        List<String> documented = new ArrayList<>();
        properties.fieldNames().forEachRemaining(documented::add);

        assertThat(documented)
                .as("every input: key the model accepts is documented in the shipped schema")
                .containsAll(declared);
    }

    /**
     * Each kind's schema covers every key its model accepts — and no more.
     *
     * <p>This is the guard whose absence let {@code trigger:}, {@code params:},
     * {@code idempotency:}, and {@code policy:} sit as undocumented stubs for months: the
     * inputField check above covered one $def while the root went unchecked.
     *
     * <p>Now that each kind has its own schema (docs/unified-sources.md), the check runs in
     * both directions. Coverage alone was satisfied by the monolith — which is how a route
     * schema came to offer a view's {@code columns:} and a job's {@code trigger:} on every
     * document. Exactness is what makes a key in the wrong kind's file fail.
     */
    @Test
    void eachKindSchemaMatchesItsModelExactly() throws Exception {
        assertThat(rootProperties("/schema/tesseraql-route-v1.schema.json"))
                .as("the route schema documents the route model's keys, and only those")
                .containsExactlyInAnyOrderElementsOf(
                        yamlNames(io.tesseraql.yaml.model.RouteDefinition.class));
        assertThat(rootProperties("/schema/tesseraql-job-v1.schema.json"))
                .as("the job schema documents the job model's keys, and only those")
                .containsExactlyInAnyOrderElementsOf(
                        yamlNames(io.tesseraql.yaml.model.JobDefinition.class));
    }

    /**
     * The fixed-shape blocks are checked the same way their document is.
     *
     * <p>They were {@code additionalProperties: true} with no properties at all, which is a
     * schema that describes nothing and validates nothing (docs/unified-sources.md decision 15).
     * The cost was concrete: {@code export:} accepted an {@code sql:} and dropped it in silence
     * for two releases after the extraction moved to {@code sources:}, and the published
     * reference documented the block in one sentence because there was nothing else to render.
     */
    @Test
    void everyFixedShapeBlockMatchesItsModelExactly() throws Exception {
        assertThat(defProperties("/schema/tesseraql-defs-v1.schema.json", "shared", "export"))
                .as("the export block documents the export model's keys, and only those")
                .containsExactlyInAnyOrderElementsOf(
                        yamlNames(io.tesseraql.yaml.model.ExportSpec.class));
        assertThat(defProperties("/schema/tesseraql-defs-v1.schema.json", "shared", "import"))
                .as("the import block documents the import model's keys, and only those")
                .containsExactlyInAnyOrderElementsOf(
                        yamlNames(io.tesseraql.yaml.model.ImportSpec.class));
        assertThat(defProperties("/schema/tesseraql-defs-v1.schema.json", "fileColumn"))
                .as("a file column documents the column model's keys, and only those")
                .containsExactlyInAnyOrderElementsOf(
                        yamlNames(io.tesseraql.yaml.model.ColumnSpec.class));

        assertThat(defProperties("/schema/tesseraql-defs-v1.schema.json", "notification"))
                .as("the notification shape documents the notify model's keys, and only those")
                .containsExactlyInAnyOrderElementsOf(
                        yamlNames(io.tesseraql.yaml.model.NotifySpec.class));
        assertThat(defProperties("/schema/tesseraql-defs-v1.schema.json", "push"))
                .as("the push shape documents the push model's keys, and only those")
                .containsExactlyInAnyOrderElementsOf(
                        yamlNames(io.tesseraql.yaml.model.PushSpec.class));
        assertThat(defProperties("/schema/tesseraql-defs-v1.schema.json", "chunk"))
                .as("the chunk shape documents the chunk model's keys, and only those")
                .containsExactlyInAnyOrderElementsOf(
                        yamlNames(io.tesseraql.yaml.model.ChunkSpec.class));
        assertThat(defProperties("/schema/tesseraql-defs-v1.schema.json", "enrichment"))
                .as("the enrichment shape documents the enrich model's keys, and only those")
                .containsExactlyInAnyOrderElementsOf(
                        yamlNames(io.tesseraql.yaml.model.EnrichSpec.class));

        JsonNode route = new ObjectMapper().readTree(
                getClass().getResourceAsStream("/schema/tesseraql-route-v1.schema.json"));
        assertThat(names(route.path("properties").path("outbox").path("properties")))
                .as("the outbox block documents the outbox model's keys, and only those")
                .containsExactlyInAnyOrderElementsOf(
                        yamlNames(io.tesseraql.yaml.model.OutboxSpec.class));
        assertThat(names(route.path("properties").path("errors").path("properties")))
                .as("the errors block documents the errors model's keys, and only those")
                .containsExactlyInAnyOrderElementsOf(
                        yamlNames(io.tesseraql.yaml.model.ErrorsSpec.class));
    }

    /**
     * A pipeline step's blocks are the shared shapes, not copies of them.
     *
     * <p>They were three separate {@code additionalProperties: true} stubs in the job schema
     * while the route's {@code notify:} was a fourth — one concept, four descriptions, none of
     * them a shape. A {@code $ref} is what keeps the next added key from having to be added in
     * four places and being added in one.
     */
    @Test
    void aPipelineStepsBlocksReferTheSharedShapes() throws Exception {
        JsonNode step = new ObjectMapper().readTree(
                getClass().getResourceAsStream("/schema/tesseraql-job-v1.schema.json"))
                .path("properties").path("pipeline").path("items").path("allOf").path(1)
                .path("properties");

        assertThat(List.of(step.path("notify"), step.path("chunk"), step.path("push"),
                step.path("export")))
                .as("a step's output and processing blocks are shared definitions")
                .allMatch(node -> node.path("$ref").asText().contains("tesseraql-defs-v1"));

        JsonNode routeNotify = new ObjectMapper().readTree(
                getClass().getResourceAsStream("/schema/tesseraql-route-v1.schema.json"))
                .path("properties").path("notify").path("additionalProperties");
        assertThat(routeNotify.path("$ref").asText())
                .as("a route's notifications are the same shape a step's is")
                .contains("notification");
    }

    /**
     * A block with real properties still lets anything through unless it says so, and the point
     * of these schemas is that an editor refuses what the loader drops.
     */
    @Test
    void everyFixedShapeBlockRefusesUnknownKeys() throws Exception {
        JsonNode defs = new ObjectMapper().readTree(
                getClass().getResourceAsStream("/schema/tesseraql-defs-v1.schema.json"));
        JsonNode route = new ObjectMapper().readTree(
                getClass().getResourceAsStream("/schema/tesseraql-route-v1.schema.json"));

        assertThat(List.of(
                defs.path("$defs").path("shared").path("export").path("additionalProperties"),
                defs.path("$defs").path("shared").path("import").path("additionalProperties"),
                defs.path("$defs").path("fileColumn").path("additionalProperties"),
                defs.path("$defs").path("notification").path("additionalProperties"),
                defs.path("$defs").path("push").path("additionalProperties"),
                defs.path("$defs").path("chunk").path("additionalProperties"),
                defs.path("$defs").path("enrichment").path("additionalProperties"),
                route.path("properties").path("outbox").path("additionalProperties"),
                route.path("properties").path("errors").path("additionalProperties")))
                .as("a fixed-shape block is closed, so an unknown key is a red squiggle rather"
                        + " than a key the loader quietly ignores")
                .allMatch(node -> node.isBoolean() && !node.asBoolean());
    }

    /** The property names of one {@code $defs} entry, addressed by its path. */
    private List<String> defProperties(String resource, String... path) throws Exception {
        JsonNode node = new ObjectMapper().readTree(getClass().getResourceAsStream(resource))
                .path("$defs");
        for (String step : path) {
            node = node.path(step);
        }
        return names(node.path("properties"));
    }

    /** The field names of a schema node, in declaration order. */
    private static List<String> names(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    /**
     * The binding definition documents the <em>authoring</em> form, which is the creator's
     * parameters — not the record's components.
     *
     * <p>{@link io.tesseraql.yaml.model.Binding} stays flat while the surface nests
     * (docs/unified-sources.md): a document writes {@code sql: {file: …}}, and the record holds
     * {@code file}. Nothing checked the schema against the creator, so after the arms landed the
     * shipped definition still offered a bare {@code file:} and a string {@code contract:} — keys
     * the parser ignores. The published reference is generated from this file, so a reader
     * authoring from it wrote documents that silently did nothing.
     */
    @Test
    void theBindingSchemaDocumentsTheAuthoringForm() throws Exception {
        JsonNode schema = new ObjectMapper().readTree(
                getClass().getResourceAsStream("/schema/tesseraql-defs-v1.schema.json"));
        List<String> documented = new ArrayList<>();
        schema.path("$defs").path("binding").path("properties").fieldNames()
                .forEachRemaining(documented::add);

        assertThat(documented)
                .as("the binding definition documents the keys the @JsonCreator reads, and only"
                        + " those")
                .containsExactlyInAnyOrderElementsOf(creatorProperties(
                        io.tesseraql.yaml.model.Binding.class, "of"));
    }

    /** The {@code @JsonProperty} names of a model's {@code @JsonCreator} factory. */
    private static List<String> creatorProperties(Class<?> model, String factory) {
        for (var method : model.getDeclaredMethods()) {
            if (!method.getName().equals(factory)
                    || method.getAnnotation(
                            com.fasterxml.jackson.annotation.JsonCreator.class) == null) {
                continue;
            }
            List<String> names = new ArrayList<>();
            for (var parameter : method.getParameters()) {
                var property = parameter.getAnnotation(
                        com.fasterxml.jackson.annotation.JsonProperty.class);
                names.add(property == null ? parameter.getName() : property.value());
            }
            return names;
        }
        throw new IllegalStateException("no @JsonCreator " + factory + " on " + model);
    }

    /** The root property names of one shipped schema. */
    private List<String> rootProperties(String resource) throws Exception {
        JsonNode schema = new ObjectMapper().readTree(getClass().getResourceAsStream(resource));
        List<String> names = new ArrayList<>();
        schema.path("properties").fieldNames().forEachRemaining(names::add);
        return names;
    }

    /** Each record component's YAML name (honoring @JsonProperty renames). */
    private static List<String> yamlNames(Class<? extends Record> model) {
        List<String> names = new ArrayList<>();
        for (var component : model.getRecordComponents()) {
            String name = component.getName();
            try {
                var field = model.getDeclaredField(name);
                var renamed = field.getAnnotation(
                        com.fasterxml.jackson.annotation.JsonProperty.class);
                if (renamed != null && !renamed.value().isBlank()) {
                    name = renamed.value();
                }
            } catch (NoSuchFieldException impossibleForARecord) {
                throw new IllegalStateException(impossibleForARecord);
            }
            names.add(name);
        }
        return names;
    }

    @Test
    void theRuleSetSchemaCoversEveryRuleSetComponent() throws Exception {
        JsonNode schema = new ObjectMapper().readTree(
                getClass().getResourceAsStream("/schema/tesseraql-rules-v1.schema.json"));
        JsonNode rule = schema.path("properties").path("rules")
                .path("additionalProperties").path("properties");

        List<String> documented = new ArrayList<>();
        rule.fieldNames().forEachRemaining(documented::add);
        List<String> declared = new ArrayList<>();
        for (var component : io.tesseraql.yaml.model.RuleSetsDocument.RuleSet.class
                .getRecordComponents()) {
            declared.add(component.getName());
        }

        assertThat(documented).containsAll(declared);
    }

    @Test
    void theDecisionsSchemaCoversEveryDecisionComponent() throws Exception {
        JsonNode schema = new ObjectMapper().readTree(
                getClass().getResourceAsStream("/schema/tesseraql-decisions-v1.schema.json"));
        JsonNode decision = schema.path("properties").path("decisions")
                .path("additionalProperties").path("properties");

        List<String> documented = new ArrayList<>();
        decision.fieldNames().forEachRemaining(documented::add);

        assertThat(documented).containsAll(
                yamlNames(io.tesseraql.yaml.model.DecisionsDocument.Decision.class));
    }

    /** The source mapping's keys are covered too — the shape a table-backed decision authors. */
    @Test
    void theDecisionsSchemaCoversEverySourceComponent() throws Exception {
        JsonNode schema = new ObjectMapper().readTree(
                getClass().getResourceAsStream("/schema/tesseraql-decisions-v1.schema.json"));
        JsonNode source = schema.path("properties").path("decisions")
                .path("additionalProperties").path("properties").path("source")
                .path("properties");

        List<String> documented = new ArrayList<>();
        source.fieldNames().forEachRemaining(documented::add);

        assertThat(documented).containsAll(
                yamlNames(io.tesseraql.yaml.model.DecisionsDocument.Source.class));
    }

    /** The route schema's decide: entry covers every authored DecisionUse key. */
    @Test
    void theRouteSchemaCoversEveryDecideReferenceKey() throws Exception {
        JsonNode schema = new ObjectMapper().readTree(
                getClass().getResourceAsStream("/schema/tesseraql-route-v1.schema.json"));
        JsonNode reference = schema.path("properties").path("decide")
                .path("additionalProperties").path("properties");

        List<String> documented = new ArrayList<>();
        reference.fieldNames().forEachRemaining(documented::add);
        List<String> declared = new ArrayList<>();
        for (var component : io.tesseraql.yaml.model.DecisionUse.class.getRecordComponents()) {
            var field = io.tesseraql.yaml.model.DecisionUse.class
                    .getDeclaredField(component.getName());
            // The resolved decision is loader-stamped, never authored, so the schema must not
            // offer it.
            if (field.getAnnotation(com.fasterxml.jackson.annotation.JsonIgnore.class) != null) {
                continue;
            }
            declared.add(component.getName());
        }

        assertThat(documented).containsExactlyInAnyOrderElementsOf(declared);
    }

    /**
     * A field domain's value type <em>is</em> an input field, and it now says so by reference:
     * the copy this file used to carry — and the test that kept the copy honest — are both
     * gone, because a shared definitions file makes the second declaration unnecessary rather
     * than merely policed.
     */
    @Test
    void theDomainSchemaRefersToTheSharedInputFieldDefinition() throws Exception {
        JsonNode domains = new ObjectMapper().readTree(
                getClass().getResourceAsStream("/schema/tesseraql-domains-v1.schema.json"));

        assertThat(domains.path("properties").path("domains").path("additionalProperties")
                .path("$ref").asText())
                .as("the domains map's value is the one input-field definition, not a copy")
                .isEqualTo("tesseraql-defs-v1.schema.json#/$defs/inputField");
        assertThat(domains.has("$defs"))
                .as("no local copy to drift")
                .isFalse();
    }

    @Test
    void schemaValidateRuleDocumentsSharedRuleReferences() throws Exception {
        JsonNode schema = new ObjectMapper().readTree(
                getClass().getResourceAsStream("/schema/tesseraql-route-v1.schema.json"));
        JsonNode rule = schema.path("properties").path("validate")
                .path("additionalProperties").path("properties");

        // use: is how a route references a shared rule set; the node was an untyped map whose
        // description predated the feature.
        assertThat(rule.has("use")).isTrue();
        assertThat(rule.has("rule")).isTrue();
        assertThat(rule.has("file")).isTrue();
        assertThat(schema.path("properties").path("validate").path("description").asText())
                .contains("use:");
    }

    @Test
    void schemaRecipeEnumCoversEveryLinterRecipe() throws Exception {
        assertThat(enumOf("/schema/tesseraql-route-v1.schema.json", "recipe"))
                .as("a recipe the linter accepts on a route is one an editor accepts")
                .containsAll(AppLinter.knownRouteRecipes())
                .contains("queue-consume");
        assertThat(enumOf("/schema/tesseraql-job-v1.schema.json", "recipe"))
                .containsExactlyInAnyOrder("batch-pipeline");
        assertThat(enumOf("/schema/tesseraql-view-v1.schema.json", "recipe"))
                .containsExactlyInAnyOrderElementsOf(io.tesseraql.yaml.view.ViewSpec.recipes());
    }

    /**
     * Every document family the framework parses has exactly one schema that claims its
     * {@code kind}, and every kind is claimed.
     *
     * <p>The monolith listed all ten kinds in one enum, which is what made it the default home
     * for anything without a schema of its own. Split, each file names the kinds it describes,
     * and this asserts the ten partition cleanly: an unclaimed kind means a document nobody
     * validates, and a kind in two files means an editor applying both.
     */
    @Test
    void everyDocumentKindIsClaimedByExactlyOneSchema() throws Exception {
        List<String> claimed = new ArrayList<>();
        for (String resource : List.of("/schema/tesseraql-route-v1.schema.json",
                "/schema/tesseraql-job-v1.schema.json",
                "/schema/tesseraql-view-v1.schema.json",
                "/schema/tesseraql-document-v1.schema.json")) {
            JsonNode kind = new ObjectMapper()
                    .readTree(getClass().getResourceAsStream(resource))
                    .path("properties").path("kind");
            if (kind.has("const")) {
                claimed.add(kind.get("const").asText());
            } else {
                kind.path("enum").forEach(node -> claimed.add(node.asText()));
            }
        }
        assertThat(claimed).containsExactlyInAnyOrder("route", "job", "view", "workflow",
                "scope", "attachment", "tool", "resource", "ui", "prompt");
    }

    /** One schema property's enum values. */
    private List<String> enumOf(String resource, String property) throws Exception {
        JsonNode schema = new ObjectMapper().readTree(getClass().getResourceAsStream(resource));
        List<String> values = new ArrayList<>();
        schema.path("properties").path(property).path("enum")
                .forEach(node -> values.add(node.asText()));
        return values;
    }

    /**
     * The schema describes view documents the way the view loader reads them.
     *
     * <p>The gap this closes: the schema declared a top-level {@code view:} property carrying
     * {@code list | form | detail | dashboard} that {@link io.tesseraql.yaml.view.ViewSpec}
     * never read — it reads {@code recipe:}, whose enum did not admit those values — so every
     * shipped view document failed validation in an editor while the published reference
     * documented a key the loader rejects. Two directions of the same drift, neither of which
     * any guard could see, because the schema tests only ever reflected over the route and job
     * models.
     *
     * <p>Exact, not merely covering: the view loader is strict at every nesting level
     * (TQL-VIEW-3314), so a key the schema offers and the loader refuses is the same lie in the
     * other direction.
     */
    @Test
    void schemaDescribesViewDocumentsTheWayTheLoaderReadsThem() throws Exception {
        assertThat(rootProperties("/schema/tesseraql-view-v1.schema.json"))
                .as("the view schema documents exactly the keys the view loader accepts")
                .containsExactlyInAnyOrderElementsOf(
                        io.tesseraql.yaml.view.ViewSpec.documentKeys());
        assertThat(rootProperties("/schema/tesseraql-view-v1.schema.json"))
                .as("the phantom view: property is gone — the loader reads recipe:")
                .doesNotContain("view");
        assertThat(new ObjectMapper()
                .readTree(getClass().getResourceAsStream(
                        "/schema/tesseraql-view-v1.schema.json"))
                .path("additionalProperties").asBoolean(true))
                .as("a strict loader deserves a strict schema")
                .isFalse();
    }

    @Test
    void schemaAuthEnumMatchesTheFrameworkAuthModes() throws Exception {
        JsonNode schema = new ObjectMapper().readTree(
                getClass().getResourceAsStream("/schema/tesseraql-route-v1.schema.json"));
        List<String> authModes = new ArrayList<>();
        schema.path("properties").path("security").path("properties").path("auth").path("enum")
                .forEach(node -> authModes.add(node.asText()));
        assertThat(authModes)
                .containsExactlyInAnyOrderElementsOf(AppLinter.knownAuthModes());
    }

    @Test
    void schemaInputTypeEnumMatchesTheFrameworkInputTypes() throws Exception {
        JsonNode schema = new ObjectMapper().readTree(
                getClass().getResourceAsStream("/schema/tesseraql-defs-v1.schema.json"));
        List<String> types = new ArrayList<>();
        schema.path("$defs").path("inputField").path("properties").path("type").path("enum")
                .forEach(node -> types.add(node.asText()));
        assertThat(types)
                .containsExactlyInAnyOrderElementsOf(AppLinter.knownInputTypes());
    }

    /**
     * The widget enum is a hand-copied list nothing else guarded — {@code lookup} joining
     * {@code ViewSpec.WIDGETS} is exactly the change that would have silently missed it.
     */
    @Test
    void schemaWidgetEnumMatchesTheViewWidgetVocabulary() throws Exception {
        JsonNode schema = new ObjectMapper().readTree(
                getClass().getResourceAsStream("/schema/tesseraql-defs-v1.schema.json"));
        List<String> widgets = new ArrayList<>();
        schema.path("$defs").path("inputField").path("properties").path("widget").path("enum")
                .forEach(node -> widgets.add(node.asText()));
        assertThat(widgets)
                .containsExactlyInAnyOrderElementsOf(io.tesseraql.yaml.view.ViewSpec.WIDGETS);
    }
}
