package io.tesseraql.compiler.binding;

import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.core.expr.EvaluationContext;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.camel.Exchange;

/**
 * Resolves a file transfer's locale/timezone declaration (design ch. 28): a plain value
 * ({@code ja-JP}, {@code Asia/Tokyo}) is taken literally, while a source expression
 * ({@code principal.claim.locale}, {@code query.tz}, ...) resolves against the request - so the
 * starting user's principal can decide how dates and numbers render.
 */
final class FormatSources {

    private FormatSources() {
    }

    /** Resolves the declaration for this exchange; null stays null, unresolved sources too. */
    @SuppressWarnings("unchecked")
    static String resolve(Exchange exchange, String declaration) {
        if (declaration == null || !isSourceExpression(declaration)) {
            return declaration;
        }
        // The negotiated request locale (roadmap Phase 22) lives in an exchange property, so it
        // resolves even on routes without a request binder (file-import).
        if ("request.locale".equals(declaration)) {
            return exchange.getProperty(TesseraqlProperties.LOCALE, String.class);
        }
        Map<String, Object> context = exchange.getProperty(
                TesseraqlProperties.CONTEXT, Map.class);
        Object principal = exchange.getProperty(TesseraqlProperties.PRINCIPAL);
        return resolve(context, principal, declaration);
    }

    /** Pure resolution against the bound context and/or principal (unit-testable). */
    static String resolve(Map<String, Object> context, Object principal, String declaration) {
        if (declaration == null || !isSourceExpression(declaration)) {
            return declaration;
        }
        Map<String, Object> root = new LinkedHashMap<>();
        if (context != null) {
            root.putAll(context);
        }
        if (principal != null) {
            root.putIfAbsent("principal", principal);
        }
        Object value = new EvaluationContext(root)
                .resolve(Arrays.asList(declaration.split("\\.")));
        return value == null ? null : String.valueOf(value);
    }

    private static boolean isSourceExpression(String declaration) {
        return declaration.matches("(principal|query|body|params|request)\\..+");
    }
}
