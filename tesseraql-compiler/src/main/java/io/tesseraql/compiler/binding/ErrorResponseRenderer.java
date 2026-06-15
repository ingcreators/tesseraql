package io.tesseraql.compiler.binding;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.i18n.MessageCatalog;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

/**
 * Renders a caught exception into a JSON error response (design ch. 37.2, 37.4).
 *
 * <p>External responses expose only {@code code}, a generic {@code message}, and a trace id;
 * internal diagnostics (source, line) are not leaked (design ch. 37.3). The one addition is
 * {@link TqlException#details()}: structured payload the thrower explicitly declared safe —
 * field-level constraint errors and optimistic-locking conflict hints (roadmap Phase 18).
 * htmx requests ({@code HX-Request} header) receive those details as an inline HTML fragment
 * instead of JSON, so a form can surface them next to its fields.
 *
 * <p>Messages localize through the app's message catalog with the negotiated request locale
 * (roadmap Phase 22): a field error's declared key keeps riding as {@code messageKey} (and
 * {@code data-message-key} for the kit's client catalog) while {@code message} carries the
 * resolved human text — falling back to {@code tql.constraint.<code>} for mapped constraint
 * violations, and degrading to the key itself when no translation exists.
 */
public final class ErrorResponseRenderer implements Processor {

    private final ObjectMapper mapper = new ObjectMapper();
    private final I18nSettings i18n;

    public ErrorResponseRenderer() {
        this(I18nSettings.defaults());
    }

    public ErrorResponseRenderer(I18nSettings i18n) {
        this.i18n = i18n;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        Throwable cause = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Throwable.class);
        TqlErrorCode code = cause instanceof TqlException tql
                ? tql.code()
                : new TqlErrorCode(TqlDomain.CAMEL, 5000);
        int status = httpStatus(code);
        String tag = exchange.getProperty(TesseraqlProperties.LOCALE,
                i18n.defaultTag(), String.class);

        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code.toString());
        error.put("message", statusMessage(tag, status));
        if (cause instanceof TqlException tql) {
            tql.details().forEach((key, value) -> error.putIfAbsent(key, value));
        }
        localizeFields(error, tag);
        localizeConflict(error, tag);
        Map<String, Object> body = Map.of("error", error);

        // Inbound form fields can surface as multi-line message headers (platform-http); drop them
        // so the error response is writable as HTTP (header values must not contain newlines).
        exchange.getMessage().getHeaders().entrySet()
                .removeIf(entry -> entry.getValue() instanceof String value
                        && (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0));

        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, status);
        if ("true".equals(exchange.getMessage().getHeader("HX-Request", String.class))) {
            exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "text/html; charset=utf-8");
            exchange.getMessage().setBody(htmxFragment(error));
            return;
        }
        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json; charset=utf-8");
        exchange.getMessage().setBody(mapper.writeValueAsString(body));
    }

    /** Localizes the field-error entries in place: {@code messageKey} + resolved {@code message}. */
    private void localizeFields(Map<String, Object> error, String tag) {
        if (!(error.get("fields") instanceof List<?> fields)) {
            return;
        }
        List<Map<String, Object>> localized = new ArrayList<>();
        for (Object entry : fields) {
            if (!(entry instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> field = new LinkedHashMap<>();
            raw.forEach((key, value) -> field.put(String.valueOf(key), value));
            String key = field.get("message") instanceof String declared && !declared.isBlank()
                    ? declared
                    : null;
            String resolved = key == null
                    ? null
                    : i18n.catalog().resolve(tag,
                            i18n.defaultTag(), key);
            if (resolved == null && field.get("code") != null) {
                // Mapped constraint violations carry only a code; the framework catalog
                // translates the built-in ones (duplicate, required, ...).
                resolved = i18n.catalog().resolve(tag, i18n.defaultTag(),
                        "tql.constraint." + field.get("code"));
            }
            if (key != null) {
                field.put("messageKey", key);
                field.remove("message");
            }
            if (resolved != null) {
                field.put("message", MessageCatalog.interpolate(resolved, field));
            }
            localized.add(field);
        }
        error.put("fields", localized);
    }

    /** Resolves a conflict hint declared as a message key; literal hints pass through. */
    private void localizeConflict(Map<String, Object> error, String tag) {
        if (!(error.get("conflict") instanceof Map<?, ?> raw) || raw.get("hint") == null) {
            return;
        }
        Map<String, Object> conflict = new LinkedHashMap<>();
        raw.forEach((key, value) -> conflict.put(String.valueOf(key), value));
        String hint = String.valueOf(conflict.get("hint"));
        String resolved = i18n.catalog().resolve(tag, i18n.defaultTag(), hint);
        if (resolved != null) {
            conflict.put("hintKey", hint);
            conflict.put("hint", MessageCatalog.interpolate(resolved, conflict));
        }
        error.put("conflict", conflict);
    }

    /**
     * The entry's interpolation values for the kit's client-side catalog lookup: everything the
     * server-side interpolation saw except the implicit {@code field}/{@code code} and the
     * message/messageKey pair itself; null when nothing remains.
     */
    private String messageParams(Map<String, Object> field) {
        Map<String, Object> params = new LinkedHashMap<>(field);
        params.remove("field");
        params.remove("code");
        params.remove("message");
        params.remove("messageKey");
        if (params.isEmpty()) {
            return null;
        }
        try {
            return mapper.writeValueAsString(params);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            return null;
        }
    }

    /** The generic response message: the localized status phrase. */
    private String statusMessage(String tag, int status) {
        String resolved = i18n.catalog().resolve(tag, i18n.defaultTag(), "tql.http." + status);
        return resolved != null ? resolved : reasonPhrase(status);
    }

    /**
     * Renders the error as the Hypermedia Components field-errors fragment (the kit's documented
     * contract) for htmx requests: the kit's auto-installed {@code installFieldErrors} behavior
     * distributes each {@code hc-alert__error} item next to the input matching its
     * {@code data-field}, and a conflict hint renders as the alert body.
     */
    @SuppressWarnings("unchecked")
    private String htmxFragment(Map<String, Object> error) {
        StringBuilder html = new StringBuilder();
        html.append("<div class=\"hc-alert\" data-variant=\"error\" role=\"alert\""
                + " data-hc-field-errors data-error-code=\"")
                .append(escape(String.valueOf(error.get("code")))).append("\">");
        html.append("<p class=\"hc-alert__title\">")
                .append(escape(String.valueOf(error.get("message")))).append("</p>");
        if (error.get("fields") instanceof java.util.List<?> fields && !fields.isEmpty()) {
            html.append("<ul class=\"hc-alert__errors\">");
            for (Object entry : fields) {
                Map<String, Object> field = (Map<String, Object>) entry;
                html.append("<li class=\"hc-alert__error\" data-field=\"")
                        .append(escape(String.valueOf(field.get("field"))))
                        .append("\" data-code=\"")
                        .append(escape(String.valueOf(field.get("code"))))
                        .append("\"");
                // A validation rule's message key (Phase 19); the kit's client catalog may
                // re-resolve it on top of the server-localized text below, interpolating the
                // entry's params carried as data-message-params (hc 0.1.1).
                if (field.get("messageKey") != null) {
                    html.append(" data-message-key=\"")
                            .append(escape(String.valueOf(field.get("messageKey"))))
                            .append("\"");
                    String params = messageParams(field);
                    if (params != null) {
                        html.append(" data-message-params=\"").append(escape(params))
                                .append("\"");
                    }
                }
                Object text = field.get("message") != null
                        ? field.get("message")
                        : field.get("field") + ": " + field.get("code");
                html.append(">").append(escape(String.valueOf(text))).append("</li>");
            }
            html.append("</ul>");
        }
        if (error.get("conflict") instanceof Map<?, ?> conflict
                && conflict.get("hint") != null) {
            html.append("<p class=\"hc-alert__body\">")
                    .append(escape(String.valueOf(conflict.get("hint")))).append("</p>");
        }
        return html.append("</div>").toString();
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /** Maps an error code to an HTTP status (design ch. 37.4). */
    public static int httpStatus(TqlErrorCode code) {
        return switch (code.domain()) {
            case SEC -> switch (code.number()) {
                case 4011 -> 401;
                case 4031, 4032 -> 403;
                case 4014 -> 409; // an inbound webhook replay (roadmap Phase 26)
                default -> 401;
            };
            // 4220: declarative validation rejected the input (roadmap Phase 19); other FIELD
            // failures are malformed requests.
            case FIELD -> code.number() == 4220 ? 422 : 400;
            case RATE -> 429;
            case LANE -> code.number() == 5031 ? 503 : 500;
            case STUDIO -> switch (code.number()) {
                case 4002 -> 400;
                case 4030 -> 403;
                case 4040 -> 404;
                case 4221 -> 422;
                default -> 500;
            };
            case IDEM -> code.number() == 4090 ? 409 : 500;
            case LD -> switch (code.number()) {
                case 2820 -> 400; // file-import without an uploaded body
                case 2822 -> 404; // unknown transfer id
                case 2823 -> 409; // export not ready for download yet
                default -> 500;
            };
            case IAM -> code.number() == 4030 ? 403 : 500;
            case SQL -> switch (code.number()) {
                case 4001, 4002 -> 400; // not-null / check violation
                // unique / foreign-key / row-count expectation / serialization conflict
                case 4090, 4091, 4092, 4093 -> 409;
                default -> 500;
            };
            case TENANT, APP -> switch (code.number()) {
                case 4001 -> 400;
                case 4031 -> 403;
                default -> 404;
            };
            // Approval workflow (roadmap Phase 28): an illegal/concurrent transition is a conflict,
            // a falsy guard an unprocessable entity.
            case WORKFLOW -> switch (code.number()) {
                case 3201 -> 409;
                case 3202 -> 422;
                default -> 500;
            };
            default -> 500;
        };
    }

    private static String reasonPhrase(int status) {
        return switch (status) {
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 409 -> "Conflict";
            case 422 -> "Unprocessable Entity";
            case 429 -> "Too Many Requests";
            case 503 -> "Service Unavailable";
            default -> "Internal Server Error";
        };
    }
}
