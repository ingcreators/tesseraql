package io.tesseraql.compiler.binding;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.i18n.MessageCatalog;
import io.tesseraql.yaml.model.ResponseSpec.OnError;
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

    /**
     * TQL-CAMEL-5000: an unexpected internal error — the failure carried no TesseraQL error
     * code (HTTP 500).
     */
    private static final TqlErrorCode INTERNAL_ERROR = new TqlErrorCode(TqlDomain.CAMEL, 5000);

    private final ObjectMapper mapper = new ObjectMapper();
    private final I18nSettings i18n;
    private final Map<String, OnError> onErrorByRoute;
    private final java.nio.file.Path appHome;

    public ErrorResponseRenderer() {
        this(I18nSettings.defaults());
    }

    public ErrorResponseRenderer(I18nSettings i18n) {
        this(i18n, Map.of());
    }

    /**
     * @param onErrorByRoute per-route {@code response.onError} steering (HX-Retarget/HX-Reswap),
     *                       keyed by route id; the failing route is resolved from
     *                       {@link Exchange#FAILURE_ROUTE_ID} at error time.
     */
    public ErrorResponseRenderer(I18nSettings i18n, Map<String, OnError> onErrorByRoute) {
        this(i18n, onErrorByRoute, null);
    }

    /**
     * @param appHome the app root enabling per-app custom error pages (roadmap Phase 45):
     *                {@code templates/errors/<status>.html}, falling back to
     *                {@code templates/errors/error.html}, then today's JSON envelope. Null
     *                keeps the JSON-only behavior (the framework's own endpoints).
     */
    public ErrorResponseRenderer(I18nSettings i18n, Map<String, OnError> onErrorByRoute,
            java.nio.file.Path appHome) {
        this.i18n = i18n;
        this.onErrorByRoute = onErrorByRoute == null ? Map.of() : Map.copyOf(onErrorByRoute);
        this.appHome = appHome;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        Throwable cause = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Throwable.class);
        TqlErrorCode code = cause instanceof TqlException tql
                ? tql.code()
                : INTERNAL_ERROR;
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
        // A browser opening an auth: browser admin page with no session gets bounced to the login
        // page (post/redirect/get) instead of a raw JSON 401 — only for a top-level HTML GET, never
        // an htmx swap, a JSON/API client, or a 403 (authenticated-but-unauthorized never loops).
        if (status == 401 && wantsHtmlLoginRedirect(exchange)) {
            redirectToLogin(exchange);
            return;
        }
        // Per-app custom error pages (roadmap Phase 45): a top-level browser GET renders
        // templates/errors/<status>.html (else errors/error.html) when the app provides one —
        // htmx swaps keep the inline fragment and API clients keep the JSON envelope.
        if (appHome != null && status != 401 && wantsHtmlLoginRedirect(exchange)) {
            String page = errorPage(status, error, tag);
            if (page != null) {
                exchange.getMessage().setHeader(Exchange.CONTENT_TYPE,
                        "text/html; charset=utf-8");
                exchange.getMessage().setBody(page);
                return;
            }
        }
        if ("true".equals(exchange.getMessage().getHeader("HX-Request", String.class))) {
            exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "text/html; charset=utf-8");
            applyOnError(exchange);
            exchange.getMessage().setBody(htmxFragment(error));
            return;
        }
        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json; charset=utf-8");
        exchange.getMessage().setBody(mapper.writeValueAsString(body));
    }

    /** The bundled login page (the browser-session entry point for the admin console). */
    private static final String LOGIN_PATH = "/_tesseraql/login";

    /**
     * Whether an unauthenticated (401) error should redirect to the login page rather than render a
     * JSON 401: only a top-level HTML {@code GET} navigation (a browser opening a protected page),
     * not an htmx request, a JSON/API caller, or a non-GET. Combined with the {@code status == 401}
     * guard at the call site, a 403 (logged-in but unauthorized) is never redirected, so the login
     * page cannot loop.
     */
    private static boolean wantsHtmlLoginRedirect(Exchange exchange) {
        if ("true".equals(exchange.getMessage().getHeader("HX-Request", String.class))) {
            return false;
        }
        Object method = exchange.getMessage().getHeader(Exchange.HTTP_METHOD);
        if (method != null && !"GET".equalsIgnoreCase(String.valueOf(method))) {
            return false;
        }
        String accept = exchange.getMessage().getHeader("Accept", String.class);
        return accept != null && accept.contains("text/html");
    }

    /** Emits a 302 to the login page, preserving the original target as a sanitized {@code next}. */
    private static void redirectToLogin(Exchange exchange) {
        String path = exchange.getMessage().getHeader(Exchange.HTTP_URI, String.class);
        String query = exchange.getMessage().getHeader(Exchange.HTTP_QUERY, String.class);
        String next = (path == null || !path.startsWith("/") || path.startsWith("//") ? "/" : path)
                + (query == null || query.isBlank() ? "" : "?" + query);
        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 302);
        exchange.getMessage().setHeader("Location", LOGIN_PATH + "?next="
                + java.net.URLEncoder.encode(next, java.nio.charset.StandardCharsets.UTF_8));
        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "text/plain; charset=utf-8");
        exchange.getMessage().setBody("");
    }

    /**
     * Steers the htmx error response per the failing route's {@code response.onError}: sets
     * {@code HX-Retarget}/{@code HX-Reswap} so the error fragment can land in e.g. a flash region
     * instead of the form's own target. Routes without {@code onError} are unaffected.
     */
    private void applyOnError(Exchange exchange) {
        String routeId = exchange.getProperty(Exchange.FAILURE_ROUTE_ID, String.class);
        OnError onError = routeId == null ? null : onErrorByRoute.get(routeId);
        if (onError == null) {
            return;
        }
        if (onError.retarget() != null && !onError.retarget().isBlank()) {
            exchange.getMessage().setHeader("HX-Retarget", onError.retarget());
        }
        if (onError.reswap() != null && !onError.reswap().isBlank()) {
            exchange.getMessage().setHeader("HX-Reswap", onError.reswap());
        }
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

    /** The rendered custom error page, or null when the app ships none for this status. */
    private String errorPage(int status, Map<String, Object> error, String tag) {
        for (String name : new String[]{
                "templates/errors/" + status + ".html", "templates/errors/error.html"}) {
            if (java.nio.file.Files.isRegularFile(appHome.resolve(name))) {
                Map<String, Object> model = new LinkedHashMap<>();
                model.put("status", status);
                model.put("error", error);
                try {
                    return Templates.render(appHome, name, model,
                            java.util.Locale.forLanguageTag(tag));
                } catch (RuntimeException ex) {
                    // A broken error template must never mask the original failure.
                    return null;
                }
            }
        }
        return null;
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
                // 4230/4231/4233/4234: route-form / connector / recorder / row-edit input
                // rejected (Phase 43 Track J)
                case 4002, 4224, 4230, 4231, 4233, 4234 -> 400;
                case 4030, 4031 -> 403; // read-only / caller lacks a Studio edit role (backlog D6)
                case 4040 -> 404;
                case 4090 -> 409; // a draft applied over a concurrently changed source (backlog D5)
                // 4221: invalid draft; 4223: apply not confirmed; 4232: egress change not confirmed
                case 4221, 4223, 4232 -> 422;
                default -> 500;
            };
            case IDEM -> code.number() == 4090 ? 409 : 500;
            case LD -> switch (code.number()) {
                case 2820 -> 400; // file-import without an uploaded body
                case 2822 -> 404; // unknown transfer id
                case 2823 -> 409; // export not ready for download yet
                case 2841 -> 400; // attachment upload carried no content (roadmap Phase 30)
                case 2842 -> 415; // attachment content type not allowed
                case 2843 -> 413; // attachment exceeds the declared size limit
                case 2844 -> 404; // unknown attachment
                case 2847 -> 503; // attachment scan could not complete (fail-closed, Phase 30 s3)
                case 2848 -> 409; // download of an object that did not pass scanning
                default -> 500;
            };
            case IAM -> code.number() == 4030 ? 403 : 500;
            case ACCOUNT -> switch (code.number()) {
                // 4801 undeclared preference key; 4802 invalid value; 4804 wrong password
                case 4801, 4802, 4804 -> 400;
                case 4803 -> 409; // password change unavailable (SSO-managed credentials)
                case 4805 -> 404; // account surface disabled
                case 4806 -> 404; // marking an inbox message that is not the caller's
                default -> 500;
            };
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
            // a falsy guard an unprocessable entity, an unassigned caller a forbidden.
            case WORKFLOW -> switch (code.number()) {
                case 3201 -> 409;
                case 3202 -> 422;
                case 3203 -> 403;
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
            case 413 -> "Payload Too Large";
            case 415 -> "Unsupported Media Type";
            case 422 -> "Unprocessable Entity";
            case 429 -> "Too Many Requests";
            case 503 -> "Service Unavailable";
            default -> "Internal Server Error";
        };
    }
}
