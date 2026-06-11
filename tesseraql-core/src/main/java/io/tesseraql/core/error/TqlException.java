package io.tesseraql.core.error;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Base runtime exception carrying a {@link TqlErrorCode} plus optional source diagnostics.
 *
 * <p>Internal diagnostics (source file, line) are retained here but, per design ch. 37.3,
 * callers decide what to expose externally. Construct with {@link #builder(TqlErrorCode)} when
 * source location is available.
 *
 * <p>{@link #details()} is the explicit exception to that rule: a structured payload the thrower
 * declares safe for external responses (for example field-level constraint errors or an
 * optimistic-locking conflict hint, roadmap Phase 18). Never put internal SQL, schema, or stack
 * information in it.
 */
public class TqlException extends RuntimeException {

    private final TqlErrorCode code;
    private final String source;
    private final Integer line;
    private final transient Map<String, Object> details;

    public TqlException(TqlErrorCode code, String message) {
        this(code, message, null, null, null);
    }

    public TqlException(TqlErrorCode code, String message, Throwable cause) {
        this(code, message, null, null, cause);
    }

    protected TqlException(TqlErrorCode code, String message, String source, Integer line,
            Throwable cause) {
        this(code, message, source, line, cause, null);
    }

    private TqlException(TqlErrorCode code, String message, String source, Integer line,
            Throwable cause, Map<String, ?> details) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
        this.source = source;
        this.line = line;
        this.details = details == null || details.isEmpty()
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(details));
    }

    public TqlErrorCode code() {
        return code;
    }

    public Optional<String> source() {
        return Optional.ofNullable(source);
    }

    public Optional<Integer> line() {
        return Optional.ofNullable(line);
    }

    /** Structured detail the thrower declared safe to expose in external error responses. */
    public Map<String, Object> details() {
        return details;
    }

    @Override
    public String getMessage() {
        StringBuilder sb = new StringBuilder(code.toString());
        if (super.getMessage() != null) {
            sb.append(": ").append(super.getMessage());
        }
        if (source != null) {
            sb.append(" [").append(source);
            if (line != null) {
                sb.append(':').append(line);
            }
            sb.append(']');
        }
        return sb.toString();
    }

    public static Builder builder(TqlErrorCode code) {
        return new Builder(code);
    }

    /** Fluent builder for exceptions that carry source location. */
    public static final class Builder {
        private final TqlErrorCode code;
        private String message;
        private String source;
        private Integer line;
        private Throwable cause;
        private Map<String, ?> details;

        private Builder(TqlErrorCode code) {
            this.code = Objects.requireNonNull(code, "code");
        }

        public Builder message(String message) {
            this.message = message;
            return this;
        }

        public Builder source(String source) {
            this.source = source;
            return this;
        }

        public Builder line(Integer line) {
            this.line = line;
            return this;
        }

        public Builder cause(Throwable cause) {
            this.cause = cause;
            return this;
        }

        /** Attaches structured detail that is safe to expose in external error responses. */
        public Builder details(Map<String, ?> details) {
            this.details = details;
            return this;
        }

        public TqlException build() {
            return new TqlException(code, message, source, line, cause, details);
        }
    }
}
