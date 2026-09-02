package io.tesseraql.compiler.binding;

import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.Step;
import io.tesseraql.pipeline.TesseraqlProperties;
import io.tesseraql.yaml.i18n.I18nSettings;
import io.tesseraql.yaml.i18n.MessageCatalog;
import io.tesseraql.yaml.template.Templates;
import io.tesseraql.yaml.view.ViewFields;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The synthesized search dialog of a {@code lookup:} field (docs/reference-lookup.md decision
 * 4): {@code GET <form action>/_lookup/<field>/dialog} answers the remote-dialog + live-search
 * composition over the referenced route's rows, and {@code …/results} answers the result list
 * alone — the fragment each debounced keystroke swaps. Each row is a button whose click
 * re-renders the field through the resolve fragment, keyed by id, and closes the dialog. The
 * list reads at most {@link #CAP} rows and says when it is cut — a dialog is a picker, not a
 * report; the search input is how the rest is reached.
 */
public final class LookupDialogProcessor implements Step {

    /** The dialog list's row cap: a picker's page, not the referenced route's read bound. */
    static final int CAP = 50;

    /** Which fragment this mount answers with. */
    public enum Fragment {
        DIALOG, RESULTS
    }

    private final LookupReferences.Compiled lookup;
    private final ViewFields.FieldDef field;
    private final String companionPath;
    private final String searchParam;
    private final Fragment fragment;
    private final Path appHome;
    private final String defaultLocaleTag;
    private final Map<String, String> defaultHeaders;
    private final String basePath;
    private final int timeoutSeconds;

    /**
     * @param companionPath the resolve companion's URL template (the {@code /_lookup/<field>}
     *                      path without the dialog/results suffix), re-interpolated per request
     * @param searchParam   the referenced route's search input the dialog's form posts —
     *                      {@code q} when declared, else its first string input, else null
     *                      (the dialog renders no search form)
     */
    public LookupDialogProcessor(LookupReferences.Compiled lookup, ViewFields.FieldDef field,
            String companionPath, String searchParam, Fragment fragment, Path appHome,
            String defaultLocaleTag, Map<String, String> defaultHeaders, String basePath,
            int timeoutSeconds) {
        this.lookup = lookup;
        this.field = field;
        this.companionPath = companionPath;
        this.searchParam = searchParam;
        this.fragment = fragment;
        this.appHome = appHome;
        this.defaultLocaleTag = defaultLocaleTag;
        this.defaultHeaders = Map.copyOf(defaultHeaders);
        this.basePath = basePath;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> context = exchange.getProperty(TesseraqlProperties.CONTEXT,
                Map.of(), Map.class);
        String tag = exchange.getProperty(TesseraqlProperties.LOCALE, defaultLocaleTag,
                String.class);
        Locale locale = Locale.forLanguageTag(tag);
        MessageCatalog catalog = MessageCatalog.live(appHome.resolve("messages"))
                .withFallback(I18nSettings.builtinCatalog());
        String resolve = interpolate(companionPath, exchange);

        List<Map<String, Object>> rows = fetch(exchange, context);
        boolean truncated = rows.size() > CAP;
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : truncated ? rows.subList(0, CAP) : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            Object id = LookupReferences.column(lookup, row, field.name());
            item.put("code", text(LookupReferences.column(lookup, row,
                    lookup.spec().code())));
            item.put("label", text(LookupReferences.column(lookup, row,
                    lookup.spec().label())));
            // The pick URL is built (and encoded) here so the template never assembles a
            // query string out of row data.
            item.put("pick", resolve + "?" + encode(field.name()) + "=" + encode(text(id)));
            items.add(item);
        }

        Object bound = context.get("params");
        Map<?, ?> params = bound instanceof Map<?, ?> map ? map : Map.of();
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("field", field.name());
        d.put("title", message(catalog, locale, field.labelKey(), field.labelFallback()));
        d.put("searchParam", searchParam);
        d.put("q", searchParam == null ? "" : text(params.get(searchParam)));
        d.put("dialogUrl", resolve + "/dialog");
        d.put("resultsUrl", resolve + "/results");
        d.put("target", "#field-" + field.name() + "-field");
        d.put("rows", items);
        d.put("truncated", truncated);
        d.put("more", message(catalog, locale, "tql.lookup.more", "{cap}+ results")
                .replace("{cap}", String.valueOf(CAP)));

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("d", d);
        model.put(io.tesseraql.yaml.template.BasePathLinkBuilder.BASE_PATH_VARIABLE,
                basePath + io.tesseraql.pipeline.BasePath.activationSegment(exchange));
        String html = Templates.render(appHome, "tql/view/lookup-dialog", model, locale,
                fragment == Fragment.DIALOG ? "dialog" : "results");

        exchange.response().status(200);
        exchange.response().header(io.tesseraql.pipeline.Headers.CONTENT_TYPE,
                "text/html; charset=utf-8");
        defaultHeaders.forEach((name, value) -> exchange.response().header(name, value));
        exchange.setBody(html);
    }

    /** The capped search read, on its own connection of the referenced route's datasource. */
    private List<Map<String, Object>> fetch(Exchange exchange, Map<String, Object> context)
            throws java.sql.SQLException {
        io.tesseraql.core.sql.SqlStatement statements = io.tesseraql.core.sql.SqlStatement
                .onCallerConnections()
                .dialect(lookup.dialect())
                .timeoutSeconds(timeoutSeconds)
                .surface("lookup")
                .tracer(tracer(exchange))
                .spanParent(exchange.getProperty(TesseraqlProperties.TRACE_CONTEXT,
                        io.tesseraql.core.telemetry.SpanContext.class));
        try (Connection connection = io.tesseraql.pipeline.tenant.TenantRouting
                .dataSource(exchange, lookup.datasource()).getConnection()) {
            return LookupReferences.search(lookup, statements, connection,
                    scopeResolver(exchange), context, CAP);
        }
    }

    private static String message(MessageCatalog catalog, Locale locale, String key,
            String fallback) {
        return ViewMessages.text(catalog, locale, key, fallback);
    }

    /** The declared path with its {@code {param}} placeholders filled from the request. */
    private static String interpolate(String path, Exchange exchange) {
        if (path.indexOf('{') < 0) {
            return path;
        }
        String[] segments = path.split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if (segment.startsWith("{") && segment.endsWith("}")) {
                String value = exchange.request()
                        .param(segment.substring(1, segment.length() - 1));
                if (value != null) {
                    segments[i] = value;
                }
            }
        }
        return String.join("/", segments);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static io.tesseraql.core.sql.ScopeResolver scopeResolver(Exchange exchange) {
        io.tesseraql.core.sql.ScopeResolver resolver = exchange.beans().lookup(
                TesseraqlProperties.SCOPE_RESOLVER_BEAN,
                io.tesseraql.core.sql.ScopeResolver.class);
        return resolver != null ? resolver : io.tesseraql.core.sql.ScopeResolver.UNSUPPORTED;
    }

    private static io.tesseraql.core.telemetry.Tracer tracer(Exchange exchange) {
        io.tesseraql.core.telemetry.Tracer tracer = exchange.beans().lookup(
                TesseraqlProperties.TRACER_BEAN, io.tesseraql.core.telemetry.Tracer.class);
        return tracer != null ? tracer : io.tesseraql.core.telemetry.NoopTracer.INSTANCE;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
