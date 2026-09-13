package io.tesseraql.compiler.binding;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.yaml.app.ExportDeclarations;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * The request-time judge of a file recipe's formatting values (docs/export-declarations.md):
 * resolves a {@link FormatDeclaration} and, when the answer came from the request, holds it to
 * the same predicate lint and boot hold a literal to — before any SQL runs.
 *
 * <p>A literal (the route's or the configuration's) is not re-judged here: its verdict was
 * given at boot, and a boot-refused value never reaches a binder. The negotiated request
 * locale is not judged either — it is the framework's own answer, not the caller's. So the
 * only value this class can refuse is one the request supplied, and the refusal is the
 * caller's: 400, the {@code InputBinder} field-error envelope naming the input — or, for an
 * identity-provider claim, naming the claim under its own message key — and a DEBUG line,
 * never an ERROR one.
 */
final class RequestFormats {

    private static final String PRINCIPAL_PREFIX = "principal.";
    private static final String CLAIM_PREFIX = "principal.claim.";

    private RequestFormats() {
    }

    /** The locale the write/read spec takes, or null for the platform default. */
    static String locale(Exchange exchange, FormatDeclaration declaration) {
        return judged(exchange, declaration, ExportDeclarations::localeProblem, "locale", true);
    }

    /** The zone the write spec takes, or null for the platform default. */
    static String timezone(Exchange exchange, FormatDeclaration declaration) {
        return judged(exchange, declaration, ExportDeclarations::zoneProblem, "time zone", false);
    }

    private static String judged(Exchange exchange, FormatDeclaration declaration,
            Function<String, Optional<String>> problem, String noun, boolean isLocale) {
        FormatSources.Resolved resolved = FormatSources.resolve(exchange, declaration);
        if (resolved.provenance() != FormatSources.Provenance.SOURCE) {
            return resolved.value();
        }
        String source = declaration.declared();
        if (!(resolved.raw() instanceof CharSequence)) {
            // An IdP claim is JSON: a number, an array or an object stringifies to something
            // ZoneId.of refuses anyway, but "'[Asia/Tokyo]' is not a time zone" blames the
            // wrong party. Say what it is.
            throw reject(declaration, source, describe(source) + " is not a text value but "
                    + article(jsonKind(resolved.raw())) + ", so it cannot name a " + noun);
        }
        String value = resolved.value();
        if (isLocale && source.startsWith(PRINCIPAL_PREFIX)) {
            // OpenID Connect Core 1.0 section 5.1: a locale claim may arrive as en_US, and a
            // relying party may accept it. The dash form is used only when it parses strictly;
            // an author's literal and a caller's query value keep the strict rule, because the
            // author can fix a YAML typo and the operator cannot fix an identity provider.
            String dashed = value.replace('_', '-');
            if (!dashed.equals(value) && problem.apply(dashed).isEmpty()) {
                return dashed;
            }
        }
        Optional<String> refused = problem.apply(value);
        if (refused.isPresent()) {
            throw reject(declaration, source, describe(source) + ": " + refused.get());
        }
        return value;
    }

    /**
     * The field the envelope names: the declared input for a request source ({@code query.tz}
     * to {@code tz}, {@code body.report.tz} to {@code report.tz}), the whole expression for a
     * principal claim, which no input declares.
     */
    static String fieldOf(String source) {
        for (String prefix : new String[]{"query.", "params.", "body."}) {
            if (source.startsWith(prefix)) {
                return source.substring(prefix.length());
            }
        }
        return source;
    }

    private static String describe(String source) {
        if (source.startsWith(CLAIM_PREFIX)) {
            return "IdP claim '" + source.substring(CLAIM_PREFIX.length()) + "'";
        }
        if (source.startsWith(PRINCIPAL_PREFIX)) {
            return "principal attribute '" + source.substring(PRINCIPAL_PREFIX.length()) + "'";
        }
        return "Input '" + fieldOf(source) + "'";
    }

    private static String article(String noun) {
        return ("aeiou".indexOf(Character.toLowerCase(noun.charAt(0))) >= 0 ? "an " : "a ") + noun;
    }

    private static String jsonKind(Object raw) {
        if (raw instanceof Number) {
            return "number";
        }
        if (raw instanceof Boolean) {
            return "boolean";
        }
        if (raw instanceof List<?>) {
            return "array";
        }
        if (raw instanceof Map<?, ?>) {
            return "object";
        }
        return raw.getClass().getSimpleName();
    }

    /**
     * The {@code InputBinder} envelope — one minter: {@code details.fields[{field, code,
     * message, source}]}, the code being the key ({@code timezone} / {@code locale}); the
     * message key is {@code tql.input.<code>} for an input the caller sent and
     * {@code tql.input.claim.<code>} for an identity-provider claim, whose text says the value
     * is the sign-in profile's. The offending value travels only in the exception message —
     * bounded and control-stripped by the predicate — which the runner logs at DEBUG for a
     * 4xx; the wire says which input and which declaration, and the caller knows what it sent.
     */
    private static TqlException reject(FormatDeclaration declaration, String source,
            String logMessage) {
        String messageKey = source.startsWith(PRINCIPAL_PREFIX)
                ? "tql.input.claim." + declaration.key()
                : "tql.input." + declaration.key();
        return InputBinder.reject(fieldOf(source), declaration.key(), messageKey,
                Map.of("source", source), logMessage);
    }
}
