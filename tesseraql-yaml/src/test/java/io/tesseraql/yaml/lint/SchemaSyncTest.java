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
        // kind covers every document family the schema claims to describe.
        List<String> kinds = new ArrayList<>();
        schema.path("properties").path("kind").path("enum")
                .forEach(node -> kinds.add(node.asText()));
        assertThat(kinds).containsExactlyInAnyOrder("route", "job", "view");
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
