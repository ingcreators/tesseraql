package io.tesseraql.compiler.binding;

import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.core.expr.EvaluationContext;
import io.tesseraql.yaml.model.ResponseSpec.RedirectResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

/**
 * Renders a redirect response (design ch. 6.4, post/redirect/get): resolves the
 * {@code {expression}} placeholders of the location template against the execution context and
 * URL-encodes each resolved value.
 *
 * <p>The reply branches on whether the caller is htmx (the {@code HX-Request: true} header, set on
 * every htmx request): an htmx caller gets {@code 204 No Content} with an {@code HX-Redirect}
 * header — htmx performs a full {@code window.location} navigation, keeping post/redirect/get
 * intact (the Hypermedia Components mutating-form recipe; {@code HX-Location} is deliberately not
 * used, as it does a boosted in-page swap). A non-htmx caller (a no-JS form post) gets the plain
 * {@code Location} redirect with the configured status (303 by default), which the browser follows
 * natively.
 */
public final class RedirectRenderer implements Processor {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^}]+)}");

    private final RedirectResponse redirect;

    public RedirectRenderer(RedirectResponse redirect) {
        this.redirect = redirect;
    }

    @Override
    public void process(Exchange exchange) {
        @SuppressWarnings("unchecked")
        Map<String, Object> context = exchange.getProperty(TesseraqlProperties.CONTEXT, Map.of(),
                Map.class);
        EvaluationContext evaluation = new EvaluationContext(context);

        Matcher matcher = PLACEHOLDER.matcher(redirect.location());
        StringBuilder location = new StringBuilder();
        while (matcher.find()) {
            Object value = evaluation.resolve(Arrays.asList(matcher.group(1).split("\\.")));
            String encoded = URLEncoder.encode(
                    value == null ? "" : String.valueOf(value), StandardCharsets.UTF_8);
            matcher.appendReplacement(location, Matcher.quoteReplacement(encoded));
        }
        matcher.appendTail(location);

        if (isHtmxRequest(exchange)) {
            exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 204);
            exchange.getMessage().setHeader("HX-Redirect", location.toString());
        } else {
            exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE,
                    redirect.effectiveStatus());
            exchange.getMessage().setHeader("Location", location.toString());
        }
        exchange.getMessage().setBody("");
    }

    private static boolean isHtmxRequest(Exchange exchange) {
        return "true".equalsIgnoreCase(exchange.getMessage().getHeader("HX-Request", String.class));
    }
}
