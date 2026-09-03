package io.tesseraql.compiler.binding;

import io.tesseraql.core.expr.EvaluationContext;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.Step;
import io.tesseraql.pipeline.TesseraqlProperties;
import io.tesseraql.yaml.model.ResponseSpec.RedirectResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
public final class RedirectRenderer implements Step {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^}]+)}");

    /**
     * The {@code location: back} sentinel (docs/list-surface.md decision 11): the redirect
     * target is the request's {@code _return} field — the list URL a page-frame row link sent
     * along, fragment included — validated as an app-local path, falling back to the
     * application root. The value is caller-supplied, so it is never interpolated.
     */
    static final String BACK = "back";

    private final RedirectResponse redirect;

    public RedirectRenderer(RedirectResponse redirect) {
        this.redirect = redirect;
    }

    @Override
    public void process(Exchange exchange) {
        negotiate(exchange, redirect.effectiveStatus(),
                resolveLocation(exchange, redirect.location()));
    }

    /**
     * A declared redirect location, resolved against this request: the {@code back} sentinel
     * through the app-local gate, otherwise its {@code {expression}} placeholders interpolated
     * from the execution context.
     *
     * <p>Extracted so the conflict answer's Reload choice lands where a successful save would
     * have (docs/edit-conflict.md decision 6) — one resolution rather than two that drift the
     * first time anyone touches encoding or the sentinel.
     */
    static String resolveLocation(Exchange exchange, String declaredLocation) {
        if (BACK.equals(declaredLocation)) {
            String declared = exchange.request().param("_return");
            return io.tesseraql.core.http.BasePaths.isLocal(declared) ? declared : "/";
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> context = exchange.getProperty(TesseraqlProperties.CONTEXT, Map.of(),
                Map.class);
        EvaluationContext evaluation = new EvaluationContext(context);

        Matcher matcher = PLACEHOLDER.matcher(declaredLocation);
        StringBuilder location = new StringBuilder();
        while (matcher.find()) {
            Object value = evaluation.resolve(Arrays.asList(matcher.group(1).split("\\.")));
            String encoded = URLEncoder.encode(
                    value == null ? "" : String.valueOf(value), StandardCharsets.UTF_8);
            matcher.appendReplacement(location, Matcher.quoteReplacement(encoded));
        }
        matcher.appendTail(location);
        return location.toString();
    }

    /**
     * The studio shell's member segment (docs/studio-shell.md structural decision 2): a studio
     * page serving under {@code /_tesseraql/studio/<member>/} redirects within the same
     * member's workshop, so a studio-addressed location gains the segment here — the same rule
     * the link builder applies to emitted links, keeping the app tree member-agnostic.
     */
    private static String withStudioMember(Exchange exchange, String location) {
        if (location == null || !location.startsWith("/_tesseraql/studio/ui")) {
            return location;
        }
        String member = exchange.request().param("member");
        String route = exchange.getFromRouteId();
        if (member == null || route == null || !route.startsWith("tql.studio.")) {
            return location;
        }
        return "/_tesseraql/studio/" + member
                + location.substring("/_tesseraql/studio".length());
    }

    private static boolean isHtmxRequest(Exchange exchange) {
        return "true".equalsIgnoreCase(exchange.request().header("HX-Request"));
    }

    /**
     * The one htmx-aware redirect (docs/vocabulary-cleanup.md slice 3): an htmx caller gets
     * {@code 204 + HX-Redirect} (a swap would inline the target page), everyone else the given
     * 3xx + {@code Location}. Framework routes use this instead of hand-rolling the
     * negotiation.
     *
     * <p>The location is base-relative and acquires the application's prefix here
     * (docs/base-path.md): a redirect is a URL the browser will ask for, so it must name an
     * address this runtime serves. Being the one redirect in the framework, this is also the one
     * place the prefix has to go.
     */
    public static void negotiate(Exchange exchange, int status, String location) {
        String target = io.tesseraql.pipeline.BasePath.url(exchange,
                withStudioMember(exchange, location));
        if (isHtmxRequest(exchange)) {
            exchange.response().status(204);
            exchange.response().header("HX-Redirect", target);
        } else {
            exchange.response().status(status);
            exchange.response().header("Location", target);
        }
        exchange.setBody("");
    }
}
