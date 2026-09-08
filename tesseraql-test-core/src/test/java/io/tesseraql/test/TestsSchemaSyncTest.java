package io.tesseraql.test;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The shipped test-suite schema stays in sync with {@link TestSuite}.
 *
 * <p>Nothing checked this before. {@code tesseraql-tests-v1.schema.json} ships in every scaffolded
 * app's {@code .vscode} and is mapped to {@code tests/**}{@code /*.yml}, but it lives in
 * {@code tesseraql-yaml} while the model lives here — and {@code tesseraql-yaml} cannot depend on
 * this module, so its own {@code SchemaSyncTest} can never reach {@link TestSuite}. The guard
 * belongs on this side of the arrow.
 *
 * <p>What it caught: a case's {@code sql:} and {@code expect:} were described, while the
 * {@code verify:} read-back's copies of the same two shapes were
 * {@code {type: object, additionalProperties: true}} with no properties. The file contradicted
 * itself, and an author writing a read-back got no completion for either.
 */
class TestsSchemaSyncTest {

    private static final String SCHEMA = "/schema/tesseraql-tests-v1.schema.json";

    /**
     * A {@code verify:} step describes the same shapes the case itself does.
     *
     * <p>Coverage, not exactness: this schema's root {@code $comment} records that it is
     * deliberately open so newer keys never break older editors, and both {@code sql:} and
     * {@code expect:} carry keys the loader tolerates beyond the record's. Asserting exactness
     * here would fight a decision the file states rather than catch drift.
     */
    @Test
    void aVerifyStepDescribesTheSameShapesTheCaseDoes() throws Exception {
        JsonNode tests = new ObjectMapper().readTree(getClass().getResourceAsStream(SCHEMA));
        JsonNode caseNode = tests.at("/properties/tests/items/properties");
        JsonNode step = tests.at("/properties/tests/items/properties/verify/items/properties");

        // Non-vacuity: a pointer that stops resolving would make every check below pass on an
        // empty node, which is exactly how a schema guard goes quietly blind.
        assertThat(names(caseNode)).as("the case shape resolves").isNotEmpty();
        assertThat(names(step)).as("the verify-step shape resolves")
                .containsExactlyInAnyOrder("sql", "params", "expect");

        assertThat(names(step.path("sql").path("properties")))
                .as("a read-back's sql: describes the keys SqlTarget accepts")
                .containsAll(componentNames(TestSuite.SqlTarget.class));
        assertThat(names(step.path("expect").path("properties")))
                .as("a read-back's expect: describes the keys Expectation accepts")
                .containsAll(componentNames(TestSuite.Expectation.class));

        // The two copies of each shape must agree: the defect was one described and one blind.
        assertThat(names(step.path("sql").path("properties")))
                .as("a read-back's sql: describes what the case's own sql: does")
                .containsExactlyInAnyOrderElementsOf(names(caseNode.path("sql")
                        .path("properties")));
        assertThat(names(step.path("expect").path("properties")))
                .as("a read-back's expect: describes what the case's own expect: does")
                .containsExactlyInAnyOrderElementsOf(names(caseNode.path("expect")
                        .path("properties")));
    }

    /** A record's component names, which are its YAML keys for these shapes. */
    private static List<String> componentNames(Class<?> record) {
        List<String> names = new ArrayList<>();
        for (var component : record.getRecordComponents()) {
            names.add(component.getName());
        }
        return names;
    }

    /** The field names of a schema node, in declaration order. */
    private static List<String> names(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
