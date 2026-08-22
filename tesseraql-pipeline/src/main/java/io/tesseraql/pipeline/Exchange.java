package io.tesseraql.pipeline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One request as it travels a pipeline (docs/camel-removal.md structural decision 2).
 *
 * <p>This was {@code org.apache.camel.Exchange}, and what it carries was counted rather than
 * copied: a message, exchange properties, the exception, the route id, the stop flag, and the
 * completions that must run whether the request succeeded or failed. Nothing else in Camel's
 * exchange was called by this framework — no in/out pair, no unit of work, no type-converter
 * registry to ask for a conversion nobody wrote down.
 *
 * <p><strong>The completions are the part that had to be got right</strong>, and the campaign said
 * so before it started: the route audit row, the concurrency permit, the lane permit, the telemetry
 * span and the SQL step's streamed body all ride on them, and the failure mode of losing one is
 * silent and only on the error path.
 */
public final class Exchange {

    private final Beans beans;
    private final Request request = new Request();
    private final Response response = new Response();
    private Object body;
    private final Map<String, Object> properties = new LinkedHashMap<>();
    private final List<Completion> completions = new ArrayList<>();
    private Exception exception;
    private boolean routeStop;
    private String fromRouteId;

    public Exchange(Beans beans) {
        this.beans = beans;
    }

    /** The body: the request's on the way in, the response's once a renderer wrote one. */
    public Object getBody() {
        return body;
    }

    /**
     * The body as {@code type}.
     *
     * <p>The one real conversion this framework asks for is bytes or a stream to text
     * (docs/vertx-native.md decision 3); everything else is a cast of a value a step stored.
     */
    public <T> T getBody(Class<T> type) {
        return Conversions.convert(body, type);
    }

    public void setBody(Object body) {
        this.body = body;
    }

    /**
     * The request as it arrived (docs/vertx-native.md structural decision 1): wire headers,
     * metadata, and parameters, each in its own place. Immutable in spirit — the edge fills it,
     * steps read it — though a caller that builds an exchange by hand fills it the same way.
     */
    public Request request() {
        return request;
    }

    /**
     * The response this route is writing (docs/vertx-native.md structural decision 1).
     *
     * <p>Separate from the message so that writing a response never means clearing a request:
     * the message keeps what arrived, this starts empty, and the edge writes only this.
     */
    public Response response() {
        return response;
    }

    /** What this runtime bound, for a step that needs a service rather than a value. */
    public Beans beans() {
        return beans;
    }

    public Object getProperty(String name) {
        return properties.get(name);
    }

    public <T> T getProperty(String name, Class<T> type) {
        return Conversions.convert(properties.get(name), type);
    }

    /** The property as {@code type}, or {@code fallback} when unset. */
    public <T> T getProperty(String name, Object fallback, Class<T> type) {
        Object value = properties.get(name);
        return Conversions.convert(value == null ? fallback : value, type);
    }

    public void setProperty(String name, Object value) {
        if (value == null) {
            properties.remove(name);
        } else {
            properties.put(name, value);
        }
    }

    public Exception getException() {
        return exception;
    }

    public void setException(Exception exception) {
        this.exception = exception;
    }

    /**
     * Whether a step has already answered.
     *
     * <p>One step sets this — the role-activation redirect — and a loop that does not check it runs
     * the renderer behind the redirect, overwriting a 302 with the page the caller was being
     * redirected away from. That is a fact this framework learned from a passing test suite, and it
     * is why the flag survives its rename.
     */
    public boolean isRouteStop() {
        return routeStop;
    }

    public void setRouteStop(boolean routeStop) {
        this.routeStop = routeStop;
    }

    /** Which pipeline is running, which two renderers ask. */
    public String getFromRouteId() {
        return fromRouteId;
    }

    public void setFromRouteId(String fromRouteId) {
        this.fromRouteId = fromRouteId;
    }

    /** Registers work that runs when this exchange is done, however it ends. */
    public void addOnCompletion(Completion completion) {
        completions.add(completion);
    }

    /**
     * Runs everything registered on this exchange, once (docs/vertx-native.md decision 5).
     *
     * <p>The list is emptied before anything runs, so an exchange that has drained cannot drain
     * twice — and the drain lives here rather than on the runner so that an exchange built and
     * run <em>without</em> a runner (the poll loop hands one straight to the import step) still
     * has one call that keeps the completion guarantee.
     *
     * <p>One completion failing must not strand the ones after it: an audit row that cannot be
     * written is not a reason to leak a permit.
     */
    public void drain() {
        List<Completion> taken = List.copyOf(completions);
        completions.clear();
        for (Completion completion : taken) {
            try {
                completion.onDone(this);
            } catch (RuntimeException failed) {
                org.slf4j.LoggerFactory.getLogger(Exchange.class)
                        .warn("A completion of route {} failed", fromRouteId, failed);
            }
        }
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
            if (type == java.io.InputStream.class) {
                return (T) new java.io.ByteArrayInputStream(bytes(value));
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
                return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            }
            if (value instanceof java.io.InputStream stream) {
                try (stream) {
                    return new String(stream.readAllBytes(),
                            java.nio.charset.StandardCharsets.UTF_8);
                } catch (java.io.IOException unreadable) {
                    throw new IllegalStateException("Could not read the body as text", unreadable);
                }
            }
            return String.valueOf(value);
        }

        private static byte[] bytes(Object value) {
            if (value instanceof byte[] bytes) {
                return bytes;
            }
            if (value instanceof java.io.InputStream stream) {
                try (stream) {
                    return stream.readAllBytes();
                } catch (java.io.IOException unreadable) {
                    throw new IllegalStateException("Could not read the body", unreadable);
                }
            }
            return String.valueOf(value).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
