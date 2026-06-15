package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Route response declaration (design ch. 6.3, 6.4): JSON, HTML (template-rendered), streaming
 * (large-data export), redirect, and generated-file shapes.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ResponseSpec(JsonResponse json, HtmlResponse html, StreamResponse stream,
        RedirectResponse redirect, FileResponse file) {

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
     * @param contentType the response content type (e.g. {@code text/csv})
     * @param filename    the suggested download filename
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StreamResponse(String contentType, String filename) {
    }

    /**
     * A JSON response. {@code body} is a free-form tree (maps, lists, scalars) whose leaf strings
     * are source expressions such as {@code sql.rows}, {@code sql.rowCount}, or {@code params.limit}
     * resolved by the response renderer against the execution context.
     *
     * @param status HTTP status code, defaulting to 200
     * @param body   the response body template
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record JsonResponse(Integer status, Object body,
            java.util.Map<String, FieldPolicy> fields) {

        public JsonResponse {
            fields = fields == null ? java.util.Map.of() : java.util.Map.copyOf(fields);
        }

        public int effectiveStatus() {
            return status == null ? 200 : status;
        }
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
     * @param model    template model: each value is a source expression (e.g. {@code sql.rows})
     * @param headers  response headers; nested map values (e.g. {@code HX-Trigger}) are serialized
     *                 to JSON
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HtmlResponse(Integer status, String template,
            java.util.Map<String, Object> model, java.util.Map<String, Object> headers) {

        public HtmlResponse {
            model = model == null ? java.util.Map.of() : java.util.Map.copyOf(model);
            headers = headers == null ? java.util.Map.of() : java.util.Map.copyOf(headers);
        }

        public int effectiveStatus() {
            return status == null ? 200 : status;
        }
    }
}
