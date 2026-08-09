package io.tesseraql.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** The result factories, incl. jsonError carrying structure with isError (silent-tolerance T7). */
class McpToolResultTest {

    @Test
    void jsonIsNotAnError() {
        McpToolResult result = McpToolResult.json(Map.of("ok", true));
        assertThat(result.isError()).isFalse();
        assertThat(result.structured()).isEqualTo(Map.of("ok", true));
    }

    @Test
    void errorHasNoStructuredValue() {
        McpToolResult result = McpToolResult.error("boom");
        assertThat(result.isError()).isTrue();
        assertThat(result.structured()).isNull();
    }

    @Test
    void jsonErrorSetsTheFlagAndKeepsTheStructuredValue() {
        McpToolResult result = McpToolResult.jsonError(Map.of("blocked", true));
        assertThat(result.isError()).isTrue();
        assertThat(result.structured()).isEqualTo(Map.of("blocked", true));
    }
}
