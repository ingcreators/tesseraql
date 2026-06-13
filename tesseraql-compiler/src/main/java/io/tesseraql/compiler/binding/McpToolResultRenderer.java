package io.tesseraql.compiler.binding;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

/**
 * The default result renderer for an application-declared MCP tool (roadmap Phase 24 follow-on):
 * serializes the SQL/command result on the exchange body to JSON, which the MCP transport returns
 * as the tool result. A tool that wants a custom shape declares a {@code response: { json: ... }}
 * block and uses {@link JsonResponseRenderer} instead.
 */
public final class McpToolResultRenderer implements Processor {

    private static final TqlErrorCode RENDER_ERROR = new TqlErrorCode(TqlDomain.MCP, 3001);

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void process(Exchange exchange) throws Exception {
        Object body = exchange.getMessage().getBody();
        String json;
        try {
            json = mapper.writeValueAsString(body);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new TqlException(RENDER_ERROR, "Failed to serialize MCP tool result: "
                    + ex.getMessage());
        }
        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json; charset=utf-8");
        exchange.getMessage().setBody(json);
    }
}
