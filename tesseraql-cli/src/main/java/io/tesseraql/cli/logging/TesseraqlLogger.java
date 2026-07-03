package io.tesseraql.cli.logging;

import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.Map;
import org.slf4j.MDC;
import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.LegacyAbstractLogger;
import org.slf4j.helpers.MessageFormatter;

/**
 * A JDK-only structured logger (roadmap Phase 45): one line per event on stderr, plain text
 * by default or JSON when {@code tesseraql.logging.format=json}, always carrying the MDC —
 * so the runtime's {@code traceId}/{@code spanId} correlate every line with the request that
 * produced it. Settings re-read per line (cheap system-property lookups), so the serve
 * command's flags apply no matter when the first logger was created.
 */
public final class TesseraqlLogger extends LegacyAbstractLogger {

    /** Overridable for tests; stderr keeps stdout clean (the MCP stdio precedent). */
    static PrintStream stream = System.err;

    TesseraqlLogger(String name) {
        this.name = name;
    }

    private static Level threshold() {
        String level = System.getProperty("tesseraql.logging.level", "info");
        return switch (level.toLowerCase(java.util.Locale.ROOT)) {
            case "trace" -> Level.TRACE;
            case "debug" -> Level.DEBUG;
            case "warn" -> Level.WARN;
            case "error" -> Level.ERROR;
            default -> Level.INFO;
        };
    }

    private static boolean json() {
        return "json".equalsIgnoreCase(System.getProperty("tesseraql.logging.format", "text"));
    }

    private boolean enabled(Level level) {
        return level.toInt() >= threshold().toInt();
    }

    @Override
    public boolean isTraceEnabled() {
        return enabled(Level.TRACE);
    }

    @Override
    public boolean isDebugEnabled() {
        return enabled(Level.DEBUG);
    }

    @Override
    public boolean isInfoEnabled() {
        return enabled(Level.INFO);
    }

    @Override
    public boolean isWarnEnabled() {
        return enabled(Level.WARN);
    }

    @Override
    public boolean isErrorEnabled() {
        return enabled(Level.ERROR);
    }

    @Override
    protected String getFullyQualifiedCallerName() {
        return null;
    }

    @Override
    protected void handleNormalizedLoggingCall(Level level, Marker marker, String template,
            Object[] arguments, Throwable throwable) {
        String message = MessageFormatter.basicArrayFormat(template, arguments);
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        String line = json()
                ? jsonLine(level, message, mdc, throwable)
                : textLine(level, message, mdc, throwable);
        stream.println(line);
    }

    private String textLine(Level level, String message, Map<String, String> mdc,
            Throwable throwable) {
        StringBuilder out = new StringBuilder();
        out.append(Instant.now()).append(' ').append(level).append(' ').append(name);
        if (mdc != null && !mdc.isEmpty()) {
            out.append(" [");
            boolean first = true;
            for (Map.Entry<String, String> entry : new java.util.TreeMap<>(mdc).entrySet()) {
                if (!first) {
                    out.append(' ');
                }
                out.append(entry.getKey()).append('=').append(entry.getValue());
                first = false;
            }
            out.append(']');
        }
        out.append(" - ").append(message);
        if (throwable != null) {
            out.append(System.lineSeparator()).append(stackTrace(throwable).stripTrailing());
        }
        return out.toString();
    }

    private String jsonLine(Level level, String message, Map<String, String> mdc,
            Throwable throwable) {
        StringBuilder out = new StringBuilder("{");
        field(out, "ts", Instant.now().toString());
        field(out, "level", level.toString());
        field(out, "logger", name);
        field(out, "message", message == null ? "" : message);
        if (mdc != null) {
            for (Map.Entry<String, String> entry : new java.util.TreeMap<>(mdc).entrySet()) {
                field(out, entry.getKey(), entry.getValue());
            }
        }
        if (throwable != null) {
            field(out, "error", stackTrace(throwable));
        }
        return out.append('}').toString();
    }

    private static void field(StringBuilder out, String key, String value) {
        if (out.length() > 1) {
            out.append(',');
        }
        out.append('"').append(escape(key)).append("\":\"").append(escape(value)).append('"');
    }

    private static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    private static String stackTrace(Throwable throwable) {
        StringWriter out = new StringWriter();
        throwable.printStackTrace(new PrintWriter(out));
        return out.toString();
    }
}
