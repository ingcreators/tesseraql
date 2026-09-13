package io.tesseraql.compiler.binding;

import io.tesseraql.core.expr.EvaluationContext;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.TesseraqlProperties;
import io.tesseraql.yaml.app.ExportDeclarations;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resolves a file transfer's locale/timezone declaration (design ch. 28): a plain value
 * ({@code ja-JP}, {@code Asia/Tokyo}) is taken literally, while a source expression
 * ({@code principal.claim.locale}, {@code query.tz}, ...) resolves against the request - so the
 * starting user's principal can decide how dates and numbers render.
 *
 * <p>Since docs/export-declarations.md the resolution answers with its provenance: the value
 * and where it came from. A source that resolves to nothing is not the platform default — it is
 * the configured default when the app sets one, and the binder needs to know which of the two
 * it holds, because only a request-sourced value is the caller's to get wrong.
 */
final class FormatSources {

    /** Where a resolved formatting value came from — the altitude that judged it. */
    enum Provenance {
        /** The route's own literal: judged at lint and at boot. */
        LITERAL,
        /** A request source that resolved: judged here, per request. */
        SOURCE,
        /**
         * {@code request.locale}: the tag {@code LocaleResolution} negotiated against the app's
         * supported locales — the framework's value, never the caller's, so nobody judges it.
         */
        NEGOTIATED,
        /** {@code tesseraql.files.<key>}: a literal judged at lint and at boot. */
        CONFIG,
        /** Nothing declared anywhere: the platform default, judged by nobody. */
        PLATFORM
    }

    /**
     * A resolved formatting value.
     *
     * @param value      the string the write spec takes, or null for the platform default
     * @param provenance which rung of the chain answered
     * @param raw        the object the source resolved to (a claim may be a number or a list)
     */
    record Resolved(String value, Provenance provenance, Object raw) {
    }

    private static final String NEGOTIATED_LOCALE = "request.locale";

    private FormatSources() {
    }

    /**
     * The chain: the route's literal; else the source, when it resolves to something; else the
     * configured literal; else nothing. The two keys walk it independently.
     */
    static Resolved resolve(Exchange exchange, FormatDeclaration declaration) {
        String declared = declaration.declared();
        if (declared != null && !ExportDeclarations.isSourceExpression(declared)) {
            return new Resolved(declared, Provenance.LITERAL, declared);
        }
        Object raw = declared == null ? null : resolveRaw(exchange, declared);
        // A blank string is "no value", as ColumnValues.zone/locale already read it — so
        // `?tz=` falls to the configured default instead of silently past it to the platform.
        if (raw != null && !(raw instanceof CharSequence text && text.toString().isBlank())) {
            Provenance provenance = NEGOTIATED_LOCALE.equals(declared)
                    ? Provenance.NEGOTIATED
                    : Provenance.SOURCE;
            return new Resolved(String.valueOf(raw), provenance, raw);
        }
        if (declaration.configDefault() != null) {
            return new Resolved(declaration.configDefault(), Provenance.CONFIG,
                    declaration.configDefault());
        }
        return new Resolved(null, Provenance.PLATFORM, null);
    }

    @SuppressWarnings("unchecked")
    private static Object resolveRaw(Exchange exchange, String declaration) {
        // The negotiated request locale (roadmap Phase 22) lives in an exchange property, so it
        // resolves even on routes without a request binder (file-import).
        if (NEGOTIATED_LOCALE.equals(declaration)) {
            return exchange.getProperty(TesseraqlProperties.LOCALE, String.class);
        }
        Map<String, Object> context = exchange.getProperty(
                TesseraqlProperties.CONTEXT, Map.class);
        Object principal = exchange.getProperty(TesseraqlProperties.PRINCIPAL);
        return resolveRaw(context, principal, declaration);
    }

    /** Pure resolution against the bound context and/or principal (unit-testable). */
    static String resolve(Map<String, Object> context, Object principal, String declaration) {
        if (declaration == null || !ExportDeclarations.isSourceExpression(declaration)) {
            return declaration;
        }
        Object value = resolveRaw(context, principal, declaration);
        return value == null ? null : String.valueOf(value);
    }

    private static Object resolveRaw(Map<String, Object> context, Object principal,
            String declaration) {
        Map<String, Object> root = new LinkedHashMap<>();
        if (context != null) {
            root.putAll(context);
        }
        if (principal != null) {
            root.putIfAbsent("principal", principal);
        }
        return new EvaluationContext(root).resolve(Arrays.asList(declaration.split("\\.")));
    }
}
