package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Route response declaration (design ch. 6.3, 6.4): JSON, HTML (template-rendered), streaming
 * (large-data export), redirect, generated-file and generated-text shapes, plus an optional
 * {@code onError} that steers the error response of an htmx caller.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ResponseSpec(JsonResponse json, HtmlResponse html, StreamResponse stream,
        RedirectResponse redirect, FileResponse file, TextResponse text, OnError onError,
        SessionResponse session) {

    /**
     * Browser-session handling for the response (docs/session-rotation.md): {@code rotate}
     * re-issues the caller's session cookie — a fresh id and CSRF token, the old id
     * invalidated before the response leaves — on a successful execution. Declared on
     * routes that elevate the session (confirming an MFA enrollment); a caller without a
     * session cookie (bearer, public) is untouched.
     *
     * @param rotate re-issue the session id in place on success
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SessionResponse(Boolean rotate) {

        public boolean rotates() {
            return Boolean.TRUE.equals(rotate);
        }
    }

    /**
     * How an htmx caller's <em>error</em> response is steered (design ch. 6.4): the error renderer
     * sets {@code HX-Retarget} (a CSS selector — send the error fragment to e.g. a flash region
     * instead of the triggering element) and/or {@code HX-Reswap} (override the swap strategy) on a
     * 4xx/5xx response to an {@code HX-Request}. Both are optional; absent leaves htmx's defaults
     * (the field-errors fragment swaps into the form's own target).
     *
     * @param retarget the {@code HX-Retarget} CSS selector for the error response
     * @param reswap   the {@code HX-Reswap} strategy for the error response (e.g. {@code outerHTML})
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OnError(String retarget, String reswap) {
    }

    /**
     * A template-generated file response (design ch. 6.4): the template is rendered against the
     * model (like an HTML response) and served with an arbitrary text content type, optionally as
     * a download. Non-HTML templates render in Thymeleaf TEXT mode: interpolate with
     * {@code [(${value})]} and gate optional blocks with {@code [# th:if="..."]...[/]}.
     *
     * @param status      HTTP status code, defaulting to 200
     * @param template    template path relative to the template root
     * @param contentType the response content type, defaulting to {@code text/plain}
     * @param filename    when set, served as an attachment download with this filename
     * @param model       template model: each value is a source expression (e.g. {@code params.x})
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FileResponse(Integer status, String template, String contentType, String filename,
            java.util.Map<String, Object> model) {

        public FileResponse {
            model = model == null ? java.util.Map.of() : java.util.Map.copyOf(model);
        }

        public int effectiveStatus() {
            return status == null ? 200 : status;
        }

        public String effectiveContentType() {
            return contentType == null || contentType.isBlank()
                    ? "text/plain; charset=utf-8"
                    : contentType;
        }
    }

    /**
     * A template-generated text response (docs/prompt-as-recipe.md decision 3): the template
     * renders against the model in Thymeleaf TEXT mode, exactly as {@code file:} does, and the
     * rendered string <em>is</em> the answer — the message a {@code prompts/get} returns rather
     * than a download.
     *
     * <p>It is {@code file:} without {@code filename:} and {@code contentType:}, because a
     * message has nowhere to put either. Reusing {@code file:} here would have accepted both keys
     * and dropped them, which is the class of defect the design document is about.
     *
     * @param status   HTTP status code, defaulting to 200 — the exchange status the MCP endpoint
     *                 reads to tell a rendered message from a failure the error renderer handled
     * @param template template path relative to the template root
     * @param model    template model: each value is a source expression (e.g. {@code params.x})
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TextResponse(Integer status, String template,
            java.util.Map<String, Object> model) {

        public TextResponse {
            model = model == null ? java.util.Map.of() : java.util.Map.copyOf(model);
        }

        public int effectiveStatus() {
            return status == null ? 200 : status;
        }
    }

    /**
     * A redirect response for browser form posts (design ch. 6.4, the post/redirect/get pattern):
     * after the route's SQL has run, the client is redirected to {@code location}. Placeholders in
     * curly braces (for example {@code /admin/users/{path.id}}) are source expressions resolved
     * against the execution context.
     *
     * @param status   HTTP status code, defaulting to 303 (See Other)
     * @param location the redirect target, with optional {expression} placeholders
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RedirectResponse(Integer status, String location) {

        public int effectiveStatus() {
            return status == null ? 303 : status;
        }
    }

    /**
     * A streaming response for large-data export (design ch. 28.10).
     *
     * @param filename    the suggested download filename
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StreamResponse(String filename) {
    }

    /**
     * A JSON response. {@code body} is a free-form tree (maps, lists, scalars) whose leaf strings
     * are source expressions such as {@code main.rows}, {@code main.rowCount}, or {@code params.limit}
     * resolved by the response renderer against the execution context.
     *
     * @param status HTTP status code, defaulting to 200
     * @param body   the response body template
     * @param headers  response headers, resolved and merged exactly as
     *                 {@link HtmlResponse#headers()} — {@code {expression}} placeholders resolve
     *                 against the execution context, and the app-wide
     *                 {@code security.responseHeaders} merge under them
     * @param headersWhen  per-header guard expressions (header name &rarr; boolean expression); a
     *                 header is emitted only when its guard is truthy. The case this exists for on
     *                 a JSON response is the header defined in terms of the status: {@code status}
     *                 already varies per request through {@code statusWhen}, so {@code Location} on
     *                 a 201, {@code Retry-After} on a 429 or 503, and {@code WWW-Authenticate} on a
     *                 401 have to be able to vary with it. A response that could change its status
     *                 but not the headers that describe that status is the asymmetry this closes;
     *                 conditional payload information belongs in the body, which a JSON client
     *                 parses anyway
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record JsonResponse(Integer status, Object body,
            java.util.Map<String, FieldPolicy> fields,
            java.util.List<StatusWhen> statusWhen,
            java.util.Map<String, Object> headers, java.util.Map<String, String> headersWhen) {

        public JsonResponse {
            fields = fields == null ? java.util.Map.of() : java.util.Map.copyOf(fields);
            statusWhen = statusWhen == null
                    ? java.util.List.of()
                    : java.util.List.copyOf(statusWhen);
            headers = headers == null ? java.util.Map.of() : java.util.Map.copyOf(headers);
            headersWhen = headersWhen == null
                    ? java.util.Map.of()
                    : java.util.Map.copyOf(headersWhen);
        }

        /** A synthesized response: a status and a body, with nothing declared around them. */
        public JsonResponse(Integer status, Object body,
                java.util.Map<String, FieldPolicy> fields,
                java.util.List<StatusWhen> statusWhen) {
            this(status, body, fields, statusWhen, null, null);
        }

        public int effectiveStatus() {
            return status == null ? 200 : status;
        }

        /**
         * A copy carrying the effective header map — how the compiler merges the app-wide
         * default response headers (docs/route-defaults.md) under the route's own entries.
         */
        public JsonResponse withHeaders(java.util.Map<String, Object> effective) {
            if (effective.equals(headers)) {
                return this;
            }
            return new JsonResponse(status, body, fields, statusWhen, effective, headersWhen);
        }
    }

    /**
     * A declarative business-condition &rarr; HTTP status mapping (roadmap Phase 41,
     * generalizing {@code expect.onMismatch}): the first truthy {@code when} wins.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StatusWhen(String when, int status) {
    }

    /**
     * Output field authorization and masking policy (design ch. 33.3, 34.2).
     *
     * @param visible        when false the field is removed from the response
     * @param policy         authorization policy the principal must satisfy to see the field
     * @param mask           masking strategy ({@code email}, {@code last4}, {@code fixed})
     * @param classification data classification driving a default masking action
     * @param unmaskWhen     a row flag column (e.g. one a {@code /*%scope … as boolean *}{@code /}
     *                       directive selects): the field is masked unless that column is truthy in
     *                       the row, giving row-level (in-scope) masking (roadmap Phase 29 slice 3).
     *                       The flag column is removed from the response.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FieldPolicy(Boolean visible, String policy, String mask, String classification,
            String unmaskWhen) {
    }

    /**
     * An HTML fragment / page response (design ch. 6.4, 12).
     *
     * @param status   HTTP status code, defaulting to 200
     * @param template template path relative to the template root
     * @param shell    shell negotiation (docs/view-composition.md wave 2a): {@code auto} (the
     *                 default) serves the bare {@code #page-content} region to htmx requests and
     *                 the shell-wrapped page to direct navigation, with {@code Vary: HX-Request};
     *                 {@code always} always wraps; {@code never} always serves the region — an
     *                 htmx-only endpoint
     * @param views    view ids whose models a {@code template:} route binds
     *                 (docs/view-composition.md wave 2c): each renders into
     *                 {@code views['<id>']}, so a hand-owned template inserts
     *                 {@code ~{tql/view/list :: view(${views['items']})}} — the ladder's
     *                 round-trip; illegal alongside {@code view:}
     * @param model    template model: each value is a source expression (e.g. {@code main.rows})
     * @param headers  response headers; nested map values (e.g. {@code HX-Trigger}) are serialized
     *                 to JSON, and {@code {expression}} placeholders in values are resolved against
     *                 the execution context (like the redirect location), so a header can carry
     *                 per-request data — e.g. a dynamic {@code HX-Trigger} toast message
     * @param headersWhen  per-header guard expressions (header name → boolean expression); a header
     *                 is emitted only when its guard is truthy (or it has none), so e.g. an
     *                 {@code HX-Trigger} toast can fire only on success within a single fragment
     *                 response
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HtmlResponse(Integer status, String template, String view, String shell,
            java.util.List<String> views, java.util.Map<String, Object> model,
            java.util.Map<String, Object> headers, java.util.Map<String, String> headersWhen,
            java.util.List<StatusWhen> statusWhen) {

        public HtmlResponse {
            statusWhen = statusWhen == null
                    ? java.util.List.of()
                    : java.util.List.copyOf(statusWhen);
            views = views == null ? java.util.List.of() : java.util.List.copyOf(views);
            model = model == null ? java.util.Map.of() : java.util.Map.copyOf(model);
            headers = headers == null ? java.util.Map.of() : java.util.Map.copyOf(headers);
            headersWhen = headersWhen == null
                    ? java.util.Map.of()
                    : java.util.Map.copyOf(headersWhen);
        }

        public int effectiveStatus() {
            return status == null ? 200 : status;
        }

        /** The shell negotiation mode, defaulting to {@code auto}. */
        public String effectiveShell() {
            return shell == null ? "auto" : shell;
        }

        /**
         * A copy carrying the effective header map — how the compiler merges the app-wide
         * default response headers (docs/route-defaults.md) under the route's own entries.
         */
        public HtmlResponse withHeaders(java.util.Map<String, Object> effective) {
            if (effective.equals(headers)) {
                return this;
            }
            return new HtmlResponse(status, template, view, shell, views, model, effective,
                    headersWhen, statusWhen);
        }
    }
}
