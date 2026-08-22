package io.tesseraql.pipeline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The request as it arrived: wire headers, metadata, and its parameters, each in its own place
 * (docs/vertx-native.md structural decisions 1 and 2).
 *
 * <p>The one-bag message put all of these under one namespace, and the collisions were real
 * defects: a query parameter sharing a path parameter's name arrived joined with it, a form field
 * of that name replaced it, and {@code PathTemplate} existed to re-parse the URL for a value the
 * router had already matched. Here a parameter has a source, and a reader that says
 * {@code pathParam("id")} means the URL.
 *
 * <p>Header names match without regard to case — HTTP/2 lower-cases every name on the wire, and a
 * step that reads {@code "Cookie"} as written must find {@code cookie}. Values are ordered and may
 * repeat. There is no inbound filter any more: internal state never shared this namespace, so a
 * client-sent {@code tql.}-anything is just a header nothing reads.
 *
 * <p>{@link #param} is the one merged view, and its order is declared once: <strong>path, then
 * query, then form</strong> — the URL outranks what the caller appended, which outranks what the
 * body carried.
 */
public final class Request {

    private final Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final Map<String, String> pathParams = new LinkedHashMap<>();
    private final Map<String, List<String>> queryParams = new LinkedHashMap<>();
    private final Map<String, List<String>> formFields = new LinkedHashMap<>();
    private final Map<String, Part> attachments = new LinkedHashMap<>();
    private String method;
    private String uri;
    private String path;
    private String absoluteUrl;
    private String query;
    private String localAddress;
    private String remoteAddress;

    /** The first value of the wire header {@code name}, or null. */
    public String header(String name) {
        List<String> values = headers.get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    /** Every value of the wire header {@code name}, in arrival order. */
    public List<String> headers(String name) {
        List<String> values = headers.get(name);
        return values == null ? List.of() : List.copyOf(values);
    }

    /** Appends a wire header, preserving arrival order under one name. */
    public Request addHeader(String name, String value) {
        headers.computeIfAbsent(name, added -> new ArrayList<>(1)).add(value);
        return this;
    }

    /** Sets a wire header to exactly one value, for the callers that build a request by hand. */
    public Request header(String name, String value) {
        List<String> values = new ArrayList<>(1);
        values.add(value);
        headers.put(name, values);
        return this;
    }

    /** The request's path parameters, under their declared names, as the router matched them. */
    public Map<String, String> pathParams() {
        return pathParams;
    }

    /** The query parameters, decoded, multi-valued. */
    public Map<String, List<String>> queryParams() {
        return queryParams;
    }

    /** The form fields of a urlencoded or multipart body, multi-valued. */
    public Map<String, List<String>> formFields() {
        return formFields;
    }

    /** Uploaded parts, by field name. */
    public Map<String, Part> attachments() {
        return attachments;
    }

    /**
     * The one merged parameter view: path, then query, then form — first value.
     *
     * <p>Declared here and nowhere else. The bag decided this by insertion order, which is how a
     * form field came to replace a path segment.
     */
    public String param(String name) {
        String fromPath = pathParams.get(name);
        if (fromPath != null) {
            return fromPath;
        }
        List<String> fromQuery = queryParams.get(name);
        if (fromQuery != null && !fromQuery.isEmpty()) {
            return fromQuery.get(0);
        }
        List<String> fromForm = formFields.get(name);
        return fromForm == null || fromForm.isEmpty() ? null : fromForm.get(0);
    }

    /** The HTTP method, upper case. */
    public String method() {
        return method;
    }

    public Request method(String method) {
        this.method = method;
        return this;
    }

    /** The request URI as it arrived: path plus query string. */
    public String uri() {
        return uri;
    }

    public Request uri(String uri) {
        this.uri = uri;
        return this;
    }

    /** The normalised path — not the raw one. */
    public String path() {
        return path;
    }

    public Request path(String path) {
        this.path = path;
        return this;
    }

    /** The absolute URL the client used. */
    public String absoluteUrl() {
        return absoluteUrl;
    }

    public Request absoluteUrl(String absoluteUrl) {
        this.absoluteUrl = absoluteUrl;
        return this;
    }

    /**
     * The query string, raw, or null. Raw is the contract, not a fallback: every reader either
     * rebuilds a {@code "?" + query} redirect target or splits before decoding, and both corrupt
     * on a pre-decoded value (docs/vertx-native.md decision 2).
     */
    public String query() {
        return query;
    }

    public Request query(String query) {
        this.query = query;
        return this;
    }

    /** The address the connection arrived on. */
    public String localAddress() {
        return localAddress;
    }

    public Request localAddress(String localAddress) {
        this.localAddress = localAddress;
        return this;
    }

    /** Copies {@code from} onto this request, for a caller forwarding one pipeline to another. */
    public void becomeCopyOf(Request from) {
        headers.clear();
        from.headers.forEach((name, values) -> headers.put(name,
                new ArrayList<>(values)));
        pathParams.clear();
        pathParams.putAll(from.pathParams);
        queryParams.clear();
        from.queryParams.forEach((name, values) -> queryParams.put(name,
                new ArrayList<>(values)));
        formFields.clear();
        from.formFields.forEach((name, values) -> formFields.put(name,
                new ArrayList<>(values)));
        attachments.clear();
        attachments.putAll(from.attachments);
        method = from.method;
        uri = from.uri;
        path = from.path;
        absoluteUrl = from.absoluteUrl;
        query = from.query;
        localAddress = from.localAddress;
        remoteAddress = from.remoteAddress;
    }

    /** The peer's address — what a role's network condition and the session record resolve against. */
    public String remoteAddress() {
        return remoteAddress;
    }

    public Request remoteAddress(String remoteAddress) {
        this.remoteAddress = remoteAddress;
        return this;
    }
}
