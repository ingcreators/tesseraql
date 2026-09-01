package io.tesseraql.compiler.binding;

import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.Step;
import io.tesseraql.pipeline.TesseraqlProperties;
import io.tesseraql.yaml.i18n.I18nSettings;
import io.tesseraql.yaml.i18n.MessageCatalog;
import io.tesseraql.yaml.template.Templates;
import io.tesseraql.yaml.view.ViewFields;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The synthesized resolve companion of a {@code lookup:} field (docs/reference-lookup.md
 * decision 2): {@code GET <form action>/_lookup/<field>} re-renders the whole field fragment
 * against the referenced route's rows — <strong>200 resolved</strong> (exactly one row: hint =
 * label, hidden id = the key, the code echoed canonical), <strong>422 unresolved</strong> (zero
 * rows, or more than one — an ambiguous code is not a resolution — with the hidden id emptied:
 * the two-fields-one-truth rule's teeth), <strong>200 cleared</strong> (an empty request;
 * required-ness stays the submit endpoint's business). A request keyed by the field name
 * instead of the code resolves by id — how a prefilled edit form and, later, a dialog pick
 * re-enter the same fragment.
 */
public final class LookupResolveProcessor implements Step {

    private final LookupReferences.Compiled lookup;
    private final ViewFields.FieldDef field;
    private final String companionPath;
    private final Path appHome;
    private final String defaultLocaleTag;
    private final Map<String, String> defaultHeaders;
    private final String basePath;
    private final int timeoutSeconds;

    /**
     * @param companionPath the mounted URL template (the form action's path plus the
     *                      {@code /_lookup/<field>} suffix), re-interpolated per request so
     *                      the re-rendered fragment's own {@code hx-get} follows a
     *                      per-record action
     */
    public LookupResolveProcessor(LookupReferences.Compiled lookup, ViewFields.FieldDef field,
            String companionPath, Path appHome, String defaultLocaleTag,
            Map<String, String> defaultHeaders, String basePath, int timeoutSeconds) {
        this.lookup = lookup;
        this.field = field;
        this.companionPath = companionPath;
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
        Object bound = context.get("params");
        Map<?, ?> params = bound instanceof Map<?, ?> map ? map : Map.of();
        String code = text(params.get(lookup.spec().code()));
        Object id = params.get(field.name());

        String tag = exchange.getProperty(TesseraqlProperties.LOCALE, defaultLocaleTag,
                String.class);
        Locale locale = Locale.forLanguageTag(tag);
        MessageCatalog catalog = MessageCatalog.live(appHome.resolve("messages"))
                .withFallback(I18nSettings.builtinCatalog());
        String resolve = interpolate(companionPath, exchange);

        Map<String, Object> state;
        int status;
        if (code.isEmpty() && text(id).isEmpty()) {
            // Cleared: an empty code is not an error — empty id, hint emptied.
            state = LookupFieldModel.state(field, resolve, "", "", false, "", false);
            status = 200;
        } else {
            List<Map<String, Object>> rows = fetch(exchange, context,
                    code.isEmpty() ? field.name() : lookup.spec().code(),
                    code.isEmpty() ? id : code);
            if (rows.size() == 1) {
                Map<String, Object> row = rows.get(0);
                state = LookupFieldModel.state(field, resolve,
                        text(LookupReferences.column(lookup, row, lookup.spec().code())),
                        text(LookupReferences.column(lookup, row, lookup.spec().label())),
                        false, "", false);
                id = LookupReferences.column(lookup, row, field.name());
                status = 200;
            } else {
                state = LookupFieldModel.state(field, resolve, code, "", true,
                        message(catalog, locale), false);
                id = "";
                status = 422;
            }
        }

        Map<String, Object> f = LookupFieldModel.fragment(field,
                label(catalog, locale), text(id), state);
        Map<String, Object> model = new java.util.LinkedHashMap<>();
        model.put("f", f);
        model.put(io.tesseraql.yaml.template.BasePathLinkBuilder.BASE_PATH_VARIABLE,
                basePath + io.tesseraql.pipeline.BasePath.activationSegment(exchange));
        String html = Templates.render(appHome, "tql/view/field", model, locale, "field");

        exchange.response().status(status);
        exchange.response().header(io.tesseraql.pipeline.Headers.CONTENT_TYPE,
                "text/html; charset=utf-8");
        defaultHeaders.forEach((name, value) -> exchange.response().header(name, value));
        exchange.setBody(html);
    }

    /** The keyed fetch, on its own connection of the referenced route's datasource. */
    private List<Map<String, Object>> fetch(Exchange exchange, Map<String, Object> context,
            String keyColumn, Object keyValue) throws java.sql.SQLException {
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
            return LookupReferences.fetch(lookup, statements, connection,
                    scopeResolver(exchange), context, keyColumn, keyValue);
        }
    }

    private String label(MessageCatalog catalog, Locale locale) {
        String exact = catalog.forLocale(locale.toLanguageTag()).get(field.labelKey());
        if (exact != null) {
            return exact;
        }
        String language = catalog.forLocale(locale.getLanguage()).get(field.labelKey());
        return language != null ? language : field.labelFallback();
    }

    private static String message(MessageCatalog catalog, Locale locale) {
        String exact = catalog.forLocale(locale.toLanguageTag()).get("tql.lookup.unresolved");
        if (exact != null) {
            return exact;
        }
        String language = catalog.forLocale(locale.getLanguage()).get("tql.lookup.unresolved");
        return language != null ? language : "No match for this code.";
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
