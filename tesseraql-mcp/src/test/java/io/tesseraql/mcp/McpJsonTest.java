package io.tesseraql.mcp;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.core.json.JsonLimits;
import org.junit.jupiter.api.Test;

/**
 * The module's local constrained factory actually constrains — this mapper parses MCP requests
 * off the wire, and the only guard so far was a grep for the construction site.
 */
class McpJsonTest {

    @Test
    void depthAtTheDeclaredBoundParsesAndOnePastItRefuses() {
        ObjectMapper mapper = McpJson.constrained();

        assertThatCode(() -> mapper.readTree(nested(JsonLimits.MAX_NESTING_DEPTH)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> mapper.readTree(nested(JsonLimits.MAX_NESTING_DEPTH + 1)))
                .isInstanceOf(StreamConstraintsException.class);
    }

    private static String nested(int depth) {
        return "[".repeat(depth) + "]".repeat(depth);
    }
}
