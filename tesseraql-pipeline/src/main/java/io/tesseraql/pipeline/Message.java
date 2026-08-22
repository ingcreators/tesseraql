package io.tesseraql.pipeline;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * The request or response an exchange carries: headers, a body, and uploaded parts
 * (docs/camel-removal.md structural decision 2).
 *
 * <p>Headers are multi-valued in the sense that matters here — a repeated query parameter or form
 * field arrives as a {@code List} under one name — which is a property the edge relies on and the
 * binders read.
 */
public final class Message {

    /**
     * The headers, matched without regard to case.
     *
     * <p>Not a detail: HTTP/2 lower-cases every header name on the wire, a client may send
     * {@code authorization} or {@code Authorization}, and this framework's steps read
     * {@code "Cookie"} and {@code "Authorization"} as written. Camel's message used a
     * case-insensitive map and nothing said so out loud — the suite did, with 59 test classes
     * answering 401 the moment this was a plain map (docs/camel-removal.md decision 2).
     */
    private final Map<String, Object> headers = new java.util.TreeMap<>(
            String.CASE_INSENSITIVE_ORDER);
    private Object body;

    /** The header's value, or null. */
    public Object getHeader(String name) {
        return headers.get(name);
    }

    /** The header's value as {@code type}, or null when absent. */
    public <T> T getHeader(String name, Class<T> type) {
        return Conversions.convert(headers.get(name), type);
    }

    /** The header's value as {@code type}, or {@code fallback} when absent. */
    public <T> T getHeader(String name, Object fallback, Class<T> type) {
        Object value = headers.get(name);
        return Conversions.convert(value == null ? fallback : value, type);
    }

    public void setHeader(String name, Object value) {
        headers.put(name, value);
    }

    /** The headers, live: the edge and the binders both add to this map in place. */
    public Map<String, Object> getHeaders() {
        return headers;
    }

    public void setHeaders(Map<String, Object> replacement) {
        headers.clear();
        headers.putAll(replacement);
    }

    public void removeHeader(String name) {
        headers.remove(name);
    }

    /**
     * Removes headers matching {@code pattern}, where {@code *} is a wildcard.
     *
     * <p>The renderers use this to drop the inbound request's headers before writing a response,
     * which is the one thing this had to keep doing: the request's headers are on the message, so
     * copying them out untouched would echo a caller's cookie back.
     */
    public void removeHeaders(String pattern) {
        removeHeaders(pattern, null);
    }

    /** Removes headers matching {@code pattern} except those matching {@code keep}. */
    public void removeHeaders(String pattern, String keep) {
        java.util.function.Predicate<String> matches = wildcard(pattern);
        java.util.function.Predicate<String> kept = keep == null ? name -> false : wildcard(keep);
        headers.keySet().removeIf(name -> matches.test(name) && !kept.test(name));
    }

    private static java.util.function.Predicate<String> wildcard(String pattern) {
        if ("*".equals(pattern)) {
            return name -> true;
        }
        String regex = java.util.regex.Pattern.quote(pattern).replace("*", "\\E.*\\Q");
        return name -> name.matches(regex);
    }

    public Object getBody() {
        return body;
    }

    /**
     * The body as {@code type}.
     *
     * <p>The only real conversion this framework asks for is bytes or a stream to text — measured
     * at 25 call sites, against 165 that are casts of values it stored itself
     * (docs/camel-removal.md decision 3).
     */
    public <T> T getBody(Class<T> type) {
        return Conversions.convert(body, type);
    }

    public void setBody(Object body) {
        this.body = body;
    }

    /** The framework's own conversions, declared rather than discovered. */
    static final class Conversions {

        private Conversions() {
        }

        @SuppressWarnings("unchecked")
        static <T> T convert(Object value, Class<T> type) {
            if (value == null) {
                return null;
            }
            if (type.isInstance(value)) {
                return (T) value;
            }
            if (type == String.class) {
                return (T) text(value);
            }
            if (type == Integer.class && value instanceof Number number) {
                return (T) Integer.valueOf(number.intValue());
            }
            if (type == Long.class && value instanceof Number number) {
                return (T) Long.valueOf(number.longValue());
            }
            if (type == Boolean.class && value instanceof String string) {
                return (T) Boolean.valueOf(string);
            }
            if (type == Integer.class && value instanceof String string) {
                return (T) Integer.valueOf(string.trim());
            }
            if (type == InputStream.class) {
                return (T) new ByteArrayInputStream(bytes(value));
            }
            if (type == byte[].class) {
                return (T) bytes(value);
            }
            // A conversion this framework does not perform is a null rather than a guess: the
            // registry that used to answer here could find a path nobody had written down.
            return null;
        }

        private static String text(Object value) {
            if (value instanceof byte[] bytes) {
                return new String(bytes, StandardCharsets.UTF_8);
            }
            if (value instanceof InputStream stream) {
                try (stream) {
                    return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException unreadable) {
                    throw new IllegalStateException("Could not read the body as text", unreadable);
                }
            }
            return String.valueOf(value);
        }

        private static byte[] bytes(Object value) {
            if (value instanceof byte[] bytes) {
                return bytes;
            }
            if (value instanceof InputStream stream) {
                try (stream) {
                    return stream.readAllBytes();
                } catch (IOException unreadable) {
                    throw new IllegalStateException("Could not read the body", unreadable);
                }
            }
            return String.valueOf(value).getBytes(StandardCharsets.UTF_8);
        }
    }
}
