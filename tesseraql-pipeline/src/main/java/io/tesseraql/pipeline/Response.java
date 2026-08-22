package io.tesseraql.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The response a route is writing: a status, and the headers that will go on the wire
 * (docs/vertx-native.md structural decision 1).
 *
 * <p>It starts empty, which is the point. The one-bag message put the response in the same map as
 * the request, and everything below existed to survive that: an outbound filter deciding which
 * names may leave, ten {@code removeHeaders("*")} calls meaning "forget the request, I am writing
 * the response", and a {@code tql.} prefix rule whose mis-spelling silently disabled acting
 * roles. A response that never contained the request has nothing to filter out of it — echoing a
 * caller's {@code Cookie} back is unrepresentable rather than prevented.
 *
 * <p>Header names match without regard to case, the same rule the request side follows and for
 * the same reason: the wire does not promise a spelling. Values are ordered and may repeat —
 * {@code Set-Cookie} is the header that needs that — so {@link #header} replaces and
 * {@link #addHeader} appends.
 */
public final class Response {

    private final Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private Integer status;

    /** The status a step decided, or null while nothing has answered yet. */
    public Integer status() {
        return status;
    }

    /** The status as it will go on the wire: what was set, or 200 when nothing was. */
    public int statusOr200() {
        return status == null ? 200 : status;
    }

    public Response status(int code) {
        this.status = code;
        return this;
    }

    /** Sets {@code name} to exactly {@code value}, replacing earlier values. */
    public Response header(String name, String value) {
        List<String> values = new ArrayList<>(1);
        values.add(value);
        headers.put(name, values);
        return this;
    }

    /** Appends a value under {@code name} — {@code Set-Cookie} is why appending exists. */
    public Response addHeader(String name, String value) {
        headers.computeIfAbsent(name, added -> new ArrayList<>(1)).add(value);
        return this;
    }

    /** The first value under {@code name}, or null. */
    public String header(String name) {
        List<String> values = headers.get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    /** Every header, in name order, for the edge that writes them onto the wire. */
    public Map<String, List<String>> headers() {
        return headers;
    }

    /** Copies {@code from}'s status and headers onto this response, replacing what was here. */
    public void becomeCopyOf(Response from) {
        status = from.status;
        headers.clear();
        from.headers.forEach((name, values) -> headers.put(name, new ArrayList<>(values)));
    }

    @Override
    public String toString() {
        return "Response[" + (status == null ? "-" : status) + " "
                + headers.keySet().toString().toLowerCase(Locale.ROOT) + "]";
    }
}
