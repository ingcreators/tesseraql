package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.yaml.model.InputField;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/**
 * An MCP tool's input schema describes what the framework actually accepts.
 *
 * <p>A declared {@code type: array} fell through to {@code "string"}, so a model was told to send
 * text where the framework rejects anything but a list — a tool call the model cannot diagnose,
 * because the schema it was given is what it followed. Elements are validated against
 * {@code items:} now, so the schema has to carry that too: a model that sees the constraint
 * produces a valid call.
 */
class McpInputSchemaTest {

    private static InputField array(InputField.InputItems items) {
        return new InputField("array", false, null, null, null, null, null, null, null, null,
                null, items, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void aDeclaredArrayIsAnArray() {
        ObjectNode schema = McpInputSchema.fromInputs(Map.of("ids", array(null)));

        assertThat(schema.path("properties").path("ids").path("type").asString())
                .isEqualTo("array");
    }

    @Test
    void theElementTypeAndEnumTravelWithIt() {
        ObjectNode schema = McpInputSchema.fromInputs(Map.of("codes",
                array(new InputField.InputItems("string", List.of("A", "B"), null))));

        ObjectNode items = (ObjectNode) schema.path("properties").path("codes").path("items");
        assertThat(items.path("type").asString()).isEqualTo("string");
        assertThat(items.path("enum").toString()).isEqualTo("[\"A\",\"B\"]");
    }

    /**
     * A model told only "array" sends lines the binder refuses field by field, and it has no way
     * to diagnose a rejection against a schema that never mentioned the fields.
     */
    @Test
    void anObjectElementTravelsAsItsFieldContract() {
        java.util.Map<String, InputField> fields = new java.util.LinkedHashMap<>();
        fields.put("itemId", new InputField("string", true, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null));
        fields.put("qty", new InputField("integer", true, null, java.math.BigDecimal.ONE, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null));
        fields.put("desiredDate", new InputField("date", false, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                null));

        ObjectNode schema = McpInputSchema.fromInputs(Map.of("lines",
                array(new InputField.InputItems(null, null, fields))));

        ObjectNode items = (ObjectNode) schema.path("properties").path("lines").path("items");
        assertThat(items.path("type").asString()).isEqualTo("object");
        assertThat(items.path("required").toString()).isEqualTo("[\"itemId\",\"qty\"]");
        assertThat(items.path("properties").path("qty").path("minimum").asInt()).isEqualTo(1);
        assertThat(items.path("properties").path("desiredDate").path("format").asString())
                .isEqualTo("date");
    }

    /**
     * {@code description:} is JSON Schema's own key and the hint a model reads when it chooses a
     * value, so a field that declares one says so on the wire rather than at the manifest.
     */
    @Test
    void aFieldDescriptionIsTheSchemaDescription() {
        InputField sku = new InputField("string", true, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null,
                "The stock keeping unit to look up.");

        ObjectNode schema = McpInputSchema.fromInputs(Map.of("sku", sku));

        assertThat(schema.path("properties").path("sku").path("description").asString())
                .isEqualTo("The stock keeping unit to look up.");
    }

    /**
     * The string constraints the binder enforces — a regex, a minimum length, the email/uuid/url
     * formats — ride into the schema the way they ride into the OpenAPI contract for the same
     * field; a model told only {@code string} sent a SKU the binder refused
     * (docs/audit-low-leads.md, G4). {@code url} is JSON Schema's {@code uri}.
     */
    @Test
    void theStringConstraintsTheBinderEnforcesAreAdvertised() {
        var definition = new io.tesseraql.yaml.SimpleYamlParser().parseRoute(
                """
                        version: tesseraql/v1
                        id: items.find
                        kind: route
                        recipe: query-json
                        input:
                          sku: {type: string, required: true, pattern: "^[A-Z]{3}-\\\\d+$", minLength: 6, maxLength: 20}
                          email: {type: string, format: email}
                          ref: {type: string, format: uuid}
                          site: {type: string, format: url}
                          lines:
                            type: array
                            items:
                              fields:
                                code: {type: string, pattern: "^[a-z]+$", minLength: 2}
                                contact: {type: string, format: email}
                        """,
                "<test>");

        ObjectNode schema = McpInputSchema.fromInputs(definition.input());
        ObjectNode properties = (ObjectNode) schema.path("properties");

        assertThat(properties.path("sku").path("pattern").asString()).isEqualTo("^[A-Z]{3}-\\d+$");
        assertThat(properties.path("sku").path("minLength").asInt()).isEqualTo(6);
        assertThat(properties.path("sku").path("maxLength").asInt()).isEqualTo(20);
        assertThat(properties.path("email").path("format").asString()).isEqualTo("email");
        assertThat(properties.path("ref").path("format").asString()).isEqualTo("uuid");
        assertThat(properties.path("site").path("format").asString()).isEqualTo("uri");

        ObjectNode element = (ObjectNode) properties.path("lines").path("items").path("properties");
        assertThat(element.path("code").path("pattern").asString()).isEqualTo("^[a-z]+$");
        assertThat(element.path("code").path("minLength").asInt()).isEqualTo(2);
        assertThat(element.path("contact").path("format").asString()).isEqualTo("email");
    }

    /** A field with no description carries no key, rather than a null or an empty string. */
    @Test
    void aFieldWithoutOneCarriesNoDescription() {
        ObjectNode schema = McpInputSchema.fromInputs(Map.of("ids", array(null)));

        assertThat(schema.path("properties").path("ids").has("description")).isFalse();
    }

    /**
     * An agent reads the tool list once and follows the contract it was given, so the contract has
     * to be the same contract on every boot. The document is parsed rather than assembled with
     * {@code Map.of}, because a {@code Map.of} literal is already salted before the schema sees
     * it and a test written that way never crosses the boundary under test. The five names are
     * chosen by enumeration: ten reachable iteration orders, and the authored one is not among
     * them (docs/deterministic-output.md).
     */
    @Test
    void aToolAdvertisesItsFieldsInTheAuthoredOrder() {
        var definition = new io.tesseraql.yaml.SimpleYamlParser().parseRoute("""
                version: tesseraql/v1
                id: users.find
                kind: route
                recipe: query-json
                input:
                  name: {type: string, required: true}
                  email: {type: string, required: true}
                  department: {type: string}
                  role: {type: string, required: true}
                  status: {type: string, required: true}
                """, "<test>");

        ObjectNode schema = McpInputSchema.fromInputs(definition.input());

        List<String> advertised = new java.util.ArrayList<>();
        schema.path("properties").propertyNames().iterator().forEachRemaining(advertised::add);

        assertThat(advertised).containsExactly("name", "email", "department", "role", "status");
        assertThat(schema.path("required").toString())
                .isEqualTo("[\"name\",\"email\",\"role\",\"status\"]");
    }
}
