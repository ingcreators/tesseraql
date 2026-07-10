package io.tesseraql.runtime;

import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.compiler.binding.ErrorResponseRenderer;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.security.Principal;
import io.tesseraql.studio.CopilotFragments;
import io.tesseraql.studio.CopilotService;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;

/**
 * The copilot panel's send and stream endpoints (docs/copilot.md, "Why these are Java
 * routes"): streaming and {@code HX-Request} content negotiation are transport concerns
 * the Simple-YAML surface deliberately does not model, so these two live beside the other
 * framework transports (login, assets, MCP, health) while the page GET and reset stay YAML
 * app routes. Mounted whenever Studio is; an unconfigured copilot refuses with
 * TQL-STUDIO-4235 exactly like the YAML send route did.
 */
final class CopilotRouteBuilder extends RouteBuilder {

    private static final String AUTH = "tesseraql-auth:authenticate?auth=browser";
    private static final String PAGE = "/_tesseraql/studio/ui/copilot";

    private final CopilotService copilot;
    private final StudioAccess studioAccess;

    CopilotRouteBuilder(CopilotService copilot, StudioAccess studioAccess) {
        this.copilot = copilot;
        this.studioAccess = studioAccess;
    }

    @Override
    public void configure() {
        onException(TqlException.class).handled(true).process(new ErrorResponseRenderer());
        onException(Exception.class).handled(true).process(new ErrorResponseRenderer());

        rest().post(PAGE + "/send").to("direct:tql.copilot.send");

        // The chat-messages recipe's dual-path send: an htmx caller gets the user item, the
        // streaming placeholder, and an out-of-band composer clear; a no-JS post runs the
        // blocking loop and lands back on the page (post/redirect/get) — the old behavior,
        // now the fallback.
        from("direct:tql.copilot.send").routeId("tql.copilot.send")
                .to(AUTH).to("tesseraql-auth:csrf").process(exchange -> {
                    requireCopilot();
                    String message = requireMessage(exchange);
                    String actor = actor(exchange);
                    boolean canEdit = studioAccess.canEdit(roles(exchange));
                    if ("true".equals(exchange.getMessage().getHeader("HX-Request",
                            String.class))) {
                        String turn = copilot.begin(actor, message, canEdit);
                        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 200);
                        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE,
                                "text/html; charset=utf-8");
                        exchange.getMessage().setBody(CopilotFragments.entryHtml(
                                new CopilotService.Entry("user", message, null))
                                + CopilotFragments.placeholder(PAGE + "/stream?turn=" + turn)
                                + CopilotFragments.messageInput(true));
                        return;
                    }
                    copilot.send(actor, message, canEdit);
                    exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 303);
                    exchange.getMessage().setHeader("Location", PAGE);
                    exchange.getMessage().setBody("");
                });

    }

    /**
     * The streaming-response recipe's stream, {@code GET …/copilot/stream?turn=<id>} — an
     * {@link SseRoutes} endpoint (registered after the platform server starts, hence not a
     * Camel route; see SseRoutes): consume the single-use turn (404 before the stream
     * opens), then run the tool loop on the SSE producer — deltas as chunk events, tool
     * markers in between, and done carrying the final transcript markup.
     */
    static void registerStream(org.apache.camel.CamelContext context, int port,
            CopilotService copilot, StudioAccess studioAccess) {
        SseRoutes.register(context, port, PAGE + "/stream", (principal, query) -> {
            if (copilot == null) {
                throw new TqlException(new TqlErrorCode(TqlDomain.STUDIO, 4235),
                        "The copilot is not configured"
                                + " (tesseraql.copilot.enabled/endpoint/model)");
            }
            String actor = principal.loginId() != null
                    ? principal.loginId()
                    : principal.subject();
            CopilotService.Turn turn = copilot.consumeTurn(query.apply("turn"), actor);
            return writer -> {
                List<CopilotService.Entry> appended;
                try {
                    appended = copilot.runTurn(actor, turn, new StreamBridge(writer));
                } catch (RuntimeException ex) {
                    // runTurn absorbs model failures; anything else is transport-level.
                    writer.event("error", CopilotFragments.errorHtml(
                            "The copilot turn failed; reload the page."));
                    return;
                }
                StringBuilder done = new StringBuilder();
                appended.forEach(entry -> done.append(CopilotFragments.entryHtml(entry)));
                writer.event("done", done.toString());
            };
        });
    }

    /** Forwards loop callbacks as SSE chunk frames; an IOException ends the turn early. */
    private record StreamBridge(SseRoutes.Writer writer)
            implements
                CopilotService.TurnListener {
        @Override
        public void delta(String text) {
            send(CopilotFragments.textChunk(text));
        }

        @Override
        public void toolCall(String name) {
            send(CopilotFragments.toolChunk(name));
        }

        private void send(String chunk) {
            try {
                writer.event("chunk", chunk);
            } catch (IOException ex) {
                // The client went away mid-turn; stop producing frames for it.
                throw new java.io.UncheckedIOException(ex);
            }
        }
    }

    private void requireCopilot() {
        if (copilot == null) {
            throw new TqlException(new TqlErrorCode(TqlDomain.STUDIO, 4235),
                    "The copilot is not configured (tesseraql.copilot.enabled/endpoint/model)");
        }
    }

    /** The urlencoded form's message field (platform-http may pre-parse it to a header). */
    private static String requireMessage(Exchange exchange) {
        String message = exchange.getMessage().getHeader("message", String.class);
        if (message == null) {
            String raw = exchange.getMessage().getBody(String.class);
            if (raw != null) {
                for (String pair : raw.split("&")) {
                    int eq = pair.indexOf('=');
                    if (eq > 0 && "message".equals(pair.substring(0, eq))) {
                        message = URLDecoder.decode(pair.substring(eq + 1),
                                StandardCharsets.UTF_8);
                    }
                }
            }
        }
        if (message == null || message.isBlank() || message.length() > 4000) {
            // The same code InputBinder raises for a missing required input, so the Java
            // route rejects exactly like the YAML send route it replaced.
            throw new TqlException(new TqlErrorCode(TqlDomain.FIELD, 2001),
                    "message is required (max 4000 characters)");
        }
        return message;
    }

    private static String actor(Exchange exchange) {
        Principal principal = exchange.getProperty(TesseraqlProperties.PRINCIPAL,
                Principal.class);
        return principal.loginId() != null ? principal.loginId() : principal.subject();
    }

    private static List<String> roles(Exchange exchange) {
        Principal principal = exchange.getProperty(TesseraqlProperties.PRINCIPAL,
                Principal.class);
        return principal == null ? List.of() : principal.roles();
    }
}
