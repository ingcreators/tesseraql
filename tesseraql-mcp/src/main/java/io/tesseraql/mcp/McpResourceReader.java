package io.tesseraql.mcp;

/**
 * Reads the text payload of an {@link McpResource} for one {@code resources/read} call. The
 * {@link McpCallContext} carries the request's {@code Authorization} header, so an
 * application-served resource can run its own authentication and authorization before returning
 * data (the dev-tool resources, if any, ignore it).
 *
 * <p>The returned text is wrapped by the server into the resource's single {@code contents} entry,
 * tagged with the resource's own {@code uri} and {@code mimeType}. A read failure (an
 * authorization denial, a SQL error) is signalled by throwing - the server turns it into a
 * JSON-RPC error, leaving the connection up so the agent can read the message.
 */
@FunctionalInterface
public interface McpResourceReader {

    String read(McpCallContext context) throws Exception;
}
