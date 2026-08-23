package io.tesseraql.compiler.binding;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.Headers;
import io.tesseraql.pipeline.Step;
import io.tesseraql.pipeline.TesseraqlProperties;
import io.tesseraql.yaml.i18n.I18nSettings;
import io.tesseraql.yaml.i18n.MessageCatalog;
import io.tesseraql.yaml.model.ResponseSpec.OnError;
import io.tesseraql.yaml.template.Templates;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders a caught exception into a JSON error response (design ch. 37.2, 37.4).
 *
 * <p>External responses expose only {@code code}, a generic {@code message}, and a trace id;
 * internal diagnostics (source, line) are not leaked (design ch. 37.3). The one addition is
 * {@link TqlException#details()}: structured payload the thrower explicitly declared safe —
 * field-level constraint errors and optimistic-locking conflict hints (roadmap Phase 18) —
 * rendered as the {@code error.details} namespace (transition-engine Track F), so a detail
 * may use any key, {@code code} and {@code message} included, without colliding with the
 * envelope's own. htmx requests ({@code HX-Request} header) receive those details as an
 * inline HTML fragment instead of JSON, so a form can surface them next to its fields.
 *
 * <p>Messages localize through the app's message catalog with the negotiated request locale
 * (roadmap Phase 22): a field error's declared key keeps riding as {@code messageKey} (and
 * {@code data-message-key} for the kit's client catalog) while {@code message} carries the
 * resolved human text — falling back to {@code tql.constraint.<code>} for mapped constraint
 * violations, and degrading to the key itself when no translation exists.
 */
public final class ErrorResponseRenderer implements Step {

    /**
     * TQL-ROUTE-5000: an unexpected internal error — the failure carried no TesseraQL error
     * code (HTTP 500).
     */
    private static final TqlErrorCode INTERNAL_ERROR = new TqlErrorCode(TqlDomain.ROUTE, 5000);

    private final ObjectMapper mapper = new ObjectMapper();
    private final I18nSettings i18n;
    private final Map<String, OnError> onErrorByRoute;
    private final java.nio.file.Path appHome;
    private final Map<String, String> securityHeaders;

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
        this(i18n, onErrorByRoute, appHome, Map.of());
    }

    /**
     * @param securityHeaders the app's {@code security.responseHeaders} block. An HTML error
     *                        page and an htmx error fragment are HTML documents the browser
     *                        renders like any other, but they were the one HTML surface the
     *                        defaults never reached: they are produced here, and the merge lived
     *                        in the success render the error path short-circuits.
     */
    public ErrorResponseRenderer(I18nSettings i18n, Map<String, OnError> onErrorByRoute,
            java.nio.file.Path appHome, Map<String, String> securityHeaders) {
        this.i18n = i18n;
        this.onErrorByRoute = onErrorByRoute == null ? Map.of() : Map.copyOf(onErrorByRoute);
        this.appHome = appHome;
        this.securityHeaders = securityHeaders == null ? Map.of() : Map.copyOf(securityHeaders);
    }

    /** Applies the app's default security headers to an HTML error response. */
    private void applySecurityHeaders(Exchange exchange) {
        securityHeaders.forEach((name, value) -> exchange.response().header(name, value));
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        Throwable cause = exchange.getProperty(TesseraqlProperties.EXCEPTION_CAUGHT,
                Throwable.class);
        TqlErrorCode code = cause instanceof TqlException tql
                ? tql.code()
                : INTERNAL_ERROR;
        int status = httpStatus(code);
        String tag = exchange.getProperty(TesseraqlProperties.LOCALE,
                i18n.defaultTag(), String.class);

        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code.toString());
        error.put("message", statusMessage(tag, status));
        if (cause instanceof TqlException tql && !tql.details().isEmpty()) {
            Map<String, Object> details = new LinkedHashMap<>(tql.details());
            localizeFields(details, tag);
            localizeConflict(details, tag);
            error.put("details", details);
        }
        Map<String, Object> body = Map.of("error", error);

        exchange.response().status(status);
        // Capacity refusals are retryable; every 429/503 the envelope renders says so
        // (docs/vocabulary-cleanup.md slice 3) — the login throttle was the only surface
        // that did.
        if (status == 429 || status == 503) {
            exchange.response().header("Retry-After", "5");
        }
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
                exchange.response().header(Headers.CONTENT_TYPE, "text/html; charset=utf-8");
                applySecurityHeaders(exchange);
                exchange.setBody(page);
                return;
            }
        }
        if ("true".equals(exchange.request().header("HX-Request"))) {
            exchange.response().header(Headers.CONTENT_TYPE, "text/html; charset=utf-8");
            applySecurityHeaders(exchange);
            applyOnError(exchange);
            exchange.setBody(htmxFragment(error));
            return;
        }
        exchange.response().header(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
        exchange.setBody(mapper.writeValueAsString(body));
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
        if ("true".equals(exchange.request().header("HX-Request"))) {
            return false;
        }
        Object method = exchange.request().method();
        if (method != null && !"GET".equalsIgnoreCase(String.valueOf(method))) {
            return false;
        }
        String accept = exchange.request().header("Accept");
        return accept != null && accept.contains("text/html");
    }

    /** Emits a 302 to the login page, preserving the original target as a sanitized {@code redirect}. */
    private static void redirectToLogin(Exchange exchange) {
        String path = exchange.request().uri();
        String query = exchange.request().query();
        String wirePath = path == null || !path.startsWith("/") || path.startsWith("//")
                ? "/"
                : path;
        String suffix = query == null || query.isBlank() ? "" : "?" + query;
        // A hosted stack member has no sign-in door of its own: the bounce goes origin-absolute
        // to the stack's login, carrying the original *prefixed* path so the round trip returns
        // to the member page that bounced (docs/stack-shells.md structural decision 3). The
        // topology bean is the signal; its absence is the unhosted boot, whose bounce stays
        // base-relative: the request URI is a wire URL already carrying the application's
        // prefix, and `redirect` is handed back to the redirect helper after sign-in, which
        // prefixes it again, so it is stored base-relative like every other URL inside the
        // runtime (docs/base-path.md).
        boolean hostedMember = exchange.beans()
                .lookup(io.tesseraql.pipeline.TesseraqlProperties.STACK_MEMBER_BEAN) != null;
        String target;
        String location;
        if (hostedMember) {
            target = wirePath + suffix;
            location = LOGIN_PATH;
        } else {
            target = io.tesseraql.pipeline.BasePath.relative(exchange, wirePath) + suffix;
            location = io.tesseraql.pipeline.BasePath.url(exchange, LOGIN_PATH);
        }
        exchange.response().status(302);
        exchange.response().header("Location", location + "?redirect="
                + java.net.URLEncoder.encode(target, java.nio.charset.StandardCharsets.UTF_8));
        exchange.response().header(Headers.CONTENT_TYPE, "text/plain; charset=utf-8");
        exchange.setBody("");
    }

    /**
     * Steers the htmx error response per the failing route's {@code response.onError}: sets
     * {@code HX-Retarget}/{@code HX-Reswap} so the error fragment can land in e.g. a flash region
     * instead of the form's own target. Routes without {@code onError} are unaffected.
     */
    private void applyOnError(Exchange exchange) {
        String routeId = exchange.getProperty(TesseraqlProperties.FAILURE_ROUTE_ID, String.class);
        OnError onError = routeId == null ? null : onErrorByRoute.get(routeId);
        if (onError == null) {
            return;
        }
        if (onError.retarget() != null && !onError.retarget().isBlank()) {
            exchange.response().header("HX-Retarget", onError.retarget());
        }
        if (onError.reswap() != null && !onError.reswap().isBlank()) {
            exchange.response().header("HX-Reswap", onError.reswap());
        }
    }

    /** Localizes the field-error entries in place: {@code messageKey} + resolved {@code message}. */
    private void localizeFields(Map<String, Object> details, String tag) {
        if (!(details.get("fields") instanceof List<?> fields)) {
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
        details.put("fields", localized);
    }

    /** Resolves a conflict hint declared as a message key; literal hints pass through. */
    private void localizeConflict(Map<String, Object> details, String tag) {
        if (!(details.get("conflict") instanceof Map<?, ?> raw) || raw.get("hint") == null) {
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
        details.put("conflict", conflict);
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
        Map<String, Object> details = error.get("details") instanceof Map<?, ?> raw
                ? (Map<String, Object>) raw
                : Map.of();
        StringBuilder html = new StringBuilder();
        html.append("<div class=\"hc-alert\" data-variant=\"error\" role=\"alert\""
                + " data-hc-field-errors data-error-code=\"")
                .append(escape(String.valueOf(error.get("code")))).append("\">");
        html.append("<p class=\"hc-alert__title\">")
                .append(escape(String.valueOf(error.get("message")))).append("</p>");
        if (details.get("fields") instanceof java.util.List<?> fields && !fields.isEmpty()) {
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
        if (details.get("conflict") instanceof Map<?, ?> conflict
                && conflict.get("hint") != null) {
            html.append("<p class=\"hc-alert__body\">")
                    .append(escape(String.valueOf(conflict.get("hint")))).append("</p>");
        } else if (details.get("message") != null) {
            // A workflow guard's declared refusal message (details.code/details.message):
            // surface the WHY in the alert body, not just the status phrase.
            html.append("<p class=\"hc-alert__body\">")
                    .append(escape(String.valueOf(details.get("message")))).append("</p>");
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
                // 4011 unauthenticated; 4012/4013 webhook signature invalid or stale — the
                // caller's credential material is wrong. 4143/4144 are the same shape: the token
                // verified, but it was minted for another relying party or with no expiry, so the
                // credential is the problem and not this server (docs/audit-hardening.md
                // Decision 1).
                case 4011, 4012, 4013, 4143, 4144 -> 401;
                // 4148: authenticated and allowed in, but acting in a capacity the caller
                // does not hold (docs/application-roles.md) — the browser leg redirects to
                // the role picker before this renderer ever sees it, so what reaches here
                // is the API caller's 403.
                // 4149: the sign-in came from a network this deployment does not admit
                // (docs/access-governance.md structural decision 8) — the credential is not
                // the problem, so it is a refusal and not a challenge.
                case 4031, 4032, 4148, 4149 -> 403;
                case 4014 -> 409; // an inbound webhook replay (roadmap Phase 26)
                // 4120: invitations are not configured on this deployment — the surface is
                // absent, not broken (the ACCOUNT-4805 precedent). Found by the status
                // ledger's audit: an administrator clicking invite read Internal Server Error.
                case 4120 -> 404;
                case 4150 -> 413; // the request body exceeds tesseraql.http.maxBodyBytes
                // The SEC domain is the whole security namespace, not an auth-failure one:
                // everything else is a server-side fault — config errors (4000, 4001, 4085-4089,
                // 4120, 4132, 4135), egress refusals (4141), federation failures
                // (4140), and crypto errors (5001, 5002). A 401 here invites clients into
                // token-refresh retries against a genuinely broken server
                // (docs/contract-bugfixes.md track B).
                default -> 500;
            };
            // 4220: declarative validation rejected the input (roadmap Phase 19); other FIELD
            // failures are malformed requests.
            case FIELD -> code.number() == 4220 ? 422 : 400;
            // 5030: the live-event registry is at capacity — server saturation, not caller
            // misbehavior (docs/contract-bugfixes.md track I).
            case RATE -> code.number() == 5030 ? 503 : 429;
            case LANE -> code.number() == 5031 ? 503 : 500;
            case STUDIO -> switch (code.number()) {
                // 4230/4231/4233/4234: route-form / connector / recorder / row-edit input
                // rejected (Phase 43 Track J); 4237: a decision-rows grid save that cannot
                // reach the decision compile (wrong target / malformed grid)
                // 4241: a menu edit naming an index the menu does not have
                // 4003/4222: a caller-crafted path or template escaping the app home;
                // 4225-4229, 4238/4239, 4243: overlay/calendar/job-policy/migration edits
                // that cannot mean anything; 4240: an unknown wizard name. All the caller's
                // input — the status ledger's audit found them reading Internal Server Error.
                case 4002, 4003, 4222, 4224, 4225, 4226, 4227, 4228, 4229, 4230, 4231, 4233,
                        4234, 4237, 4238, 4239, 4240, 4241, 4243 ->
                    400;
                case 4030, 4031 -> 403; // the library's read-only refusals (McpDevTools instances)
                // 4043: unknown workshop member — or out of the caller's tql.studio.edit
                // scope, which reads identically (docs/studio-shell.md structural decision 2)
                // 4042: a doc name the portal does not hold
                case 4040, 4042, 4043 -> 404;
                // 5030: a member's runtime did not answer the studio shell's delegated call
                case 5030 -> 503;
                case 4090 -> 409; // a draft applied over a concurrently changed source (backlog D5)
                case 4236 -> 409; // baseline capture without a schema sidecar to copy
                // 4221: invalid draft; 4223: apply not confirmed; 4232: egress change not confirmed
                case 4221, 4223, 4232 -> 422;
                default -> 500;
            };
            case IDEM -> code.number() == 4090 ? 409 : 500;
            // 3003: a token request naming a member the exchange does not address — the
            // caller's request, not a server fault. The other OAUTH codes are boot refusals
            // and never surface over HTTP.
            case OAUTH -> code.number() == 3003 ? 400 : 500;
            // 3320: the live stream was asked for a topic no route declares — the caller's
            // query, not a server fault. Build-time view codes never surface over HTTP.
            case VIEW -> code.number() == 3320 ? 400 : 500;
            // Authoring/build-range decision codes (4700..4719) surface over HTTP only from
            // Studio's validate-before-persist (the decision-rows grid), where they reject the
            // author's cells — an unprocessable edit, not a server fault. The runtime-range
            // codes (4720+: multi-hit, miss, lookup failure) keep the 500 default; 4730 is a
            // malformed scaffold request, the caller's.
            case DECISION -> switch (code.number()) {
                case 4730 -> 400;
                default -> code.number() >= 4700 && code.number() <= 4719 ? 422 : 500;
            };
            // The MCP transport's own refusals; the JSON-RPC and lint codes never ride this
            // renderer.
            case MCP -> switch (code.number()) {
                case 4263 -> 401;
                case 4264 -> 405;
                default -> 500;
            };
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
            // The IAM refusals are answers to the caller, not faults: a 500 hides the
            // message, so an administrator told "you may not" read "Internal Server Error"
            // and had nothing to act on (docs/access-governance.md slice 2 found this).
            case IAM -> switch (code.number()) {
                case 4030, 4031 -> 403; // the realm's capability refuses the write
                case 4032, 4033 -> 400; // a malformed rule condition or role-admin input
                case 4034 -> 409; // the grant conflicts with a separation-of-duties constraint
                case 4035 -> 400; // an elevation asked for is not one that can be granted
                // 4036: the write is outside what this administrator may touch. Slice 7 added
                // the refusal and not the mapping, so a delegated administrator reaching past
                // their application read "Internal Server Error" — the same defect slice 2
                // found in this very switch, in the very next number.
                case 4036 -> 403;
                default -> 500;
            };
            // 4040: unknown - or out-of-scope, which reads identically - event or execution,
            // matching the JSON ops API's Not Found body for the same code.
            case BATCH -> switch (code.number()) {
                case 4040 -> 404;
                case 4041, 4043 -> 400; // 4043: manual run body is not valid JSON
                case 4042 -> 409; // cancel target is not running - nothing left to stop
                // 5030: a member's runtime did not answer the ops shell's delegated call —
                // a replace in progress or a crashed runtime, not caller misbehavior
                // (docs/stack-shells.md structural decision 2).
                case 5030 -> 503;
                default -> 500;
            };
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
                case 4041 -> 400; // invalid or integrity-failed package — the caller's bytes
                default -> 404;
            };
            // The deploy endpoint's refusals (docs/stack-shells.md, the deploy surface): a
            // preflight that says no is a state conflict, a name the catalogue does not hold is
            // not found; everything else in the domain never surfaces over HTTP.
            case UPGRADE -> switch (code.number()) {
                case 4090 -> 409;
                case 4091 -> 404;
                default -> 500;
            };
            // Approval workflow (roadmap Phase 28): an illegal/concurrent transition is a conflict,
            // a falsy guard an unprocessable entity, an unassigned caller a forbidden.
            case WORKFLOW -> switch (code.number()) {
                case 3201 -> 409;
                case 3202 -> 422;
                case 3203 -> 403;
                case 3204 -> 409;
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
            case 405 -> "Method Not Allowed";
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
