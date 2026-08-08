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
                getClass().getResourceAsStream("/schema/tesseraql-v1.schema.json"));
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
     * The schema's root properties cover every key the route AND job models accept.
     *
     * <p>This is the guard whose absence let {@code trigger:}, {@code params:},
     * {@code idempotency:}, and {@code policy:} sit as undocumented stubs for months: the
     * inputField check above covered one $def while the root went unchecked. One schema file
     * serves both document kinds, so both records reflect here.
     */
    @Test
    void schemaRootCoversEveryRouteAndJobComponent() throws Exception {
        JsonNode schema = new ObjectMapper().readTree(
                getClass().getResourceAsStream("/schema/tesseraql-v1.schema.json"));
        JsonNode properties = schema.path("properties");
        List<String> documented = new ArrayList<>();
        properties.fieldNames().forEachRemaining(documented::add);

        assertThat(documented)
                .as("every route: key the model accepts is documented in the shipped schema")
                .containsAll(yamlNames(io.tesseraql.yaml.model.RouteDefinition.class));
        assertThat(documented)
                .as("every job: key the model accepts is documented in the shipped schema")
                .containsAll(yamlNames(io.tesseraql.yaml.model.JobDefinition.class));
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
                getClass().getResourceAsStream("/schema/tesseraql-v1.schema.json"));
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
     * A field domain's value type <em>is</em> an input field, so the domains schema carries a
     * verbatim copy of the route schema's definition and this test is what keeps the copy
     * honest. A hand-maintained second declaration would drift the moment a field key is added,
     * which is the exact gap that let {@code domain:} ship undocumented.
     */
    @Test
    void theDomainSchemaCarriesAVerbatimCopyOfTheInputFieldDefinition() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode route = mapper.readTree(
                getClass().getResourceAsStream("/schema/tesseraql-v1.schema.json"));
        JsonNode domains = mapper.readTree(
                getClass().getResourceAsStream("/schema/tesseraql-domains-v1.schema.json"));

        assertThat(domains.path("properties").path("domains").path("additionalProperties")
                .path("$ref").asText())
                .as("the domains map's value is an input field")
                .isEqualTo("#/$defs/inputField");
        assertThat(domains.path("$defs").path("inputField"))
                .as("copied from tesseraql-v1.schema.json; re-copy it rather than editing here")
                .isEqualTo(route.path("$defs").path("inputField"));
    }

    @Test
    void schemaValidateRuleDocumentsSharedRuleReferences() throws Exception {
        JsonNode schema = new ObjectMapper().readTree(
                getClass().getResourceAsStream("/schema/tesseraql-v1.schema.json"));
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
        JsonNode schema = new ObjectMapper().readTree(
                getClass().getResourceAsStream("/schema/tesseraql-v1.schema.json"));
        List<String> schemaRecipes = new ArrayList<>();
        schema.path("properties").path("recipe").path("enum")
                .forEach(node -> schemaRecipes.add(node.asText()));

        assertThat(schemaRecipes).containsAll(AppLinter.knownRouteRecipes());
        // The non-route document recipes ride the same schema (consume/** and batch jobs).
        assertThat(schemaRecipes).contains("queue-consume", "batch-tasklet", "batch-pipeline");
        // kind covers every document family the framework parses (contract-bugfixes track F):
        // route/job/view take this schema's shapes, workflow/scope/attachment ride it for
        // version/id/kind (additionalProperties stays true), and the mcp/ kinds reuse the
        // route model outright.
        List<String> kinds = new ArrayList<>();
        schema.path("properties").path("kind").path("enum")
                .forEach(node -> kinds.add(node.asText()));
        assertThat(kinds).containsExactlyInAnyOrder("route", "job", "view", "workflow",
                "scope", "attachment", "tool", "resource", "ui", "prompt");
    }

    @Test
    void schemaAuthEnumMatchesTheFrameworkAuthModes() throws Exception {
        JsonNode schema = new ObjectMapper().readTree(
                getClass().getResourceAsStream("/schema/tesseraql-v1.schema.json"));
        List<String> authModes = new ArrayList<>();
        schema.path("properties").path("security").path("properties").path("auth").path("enum")
                .forEach(node -> authModes.add(node.asText()));
        assertThat(authModes)
                .containsExactlyInAnyOrderElementsOf(AppLinter.knownAuthModes());
    }

    @Test
    void schemaInputTypeEnumMatchesTheFrameworkInputTypes() throws Exception {
        JsonNode schema = new ObjectMapper().readTree(
                getClass().getResourceAsStream("/schema/tesseraql-v1.schema.json"));
        List<String> types = new ArrayList<>();
        schema.path("$defs").path("inputField").path("properties").path("type").path("enum")
                .forEach(node -> types.add(node.asText()));
        assertThat(types)
                .containsExactlyInAnyOrderElementsOf(AppLinter.knownInputTypes());
    }
}
