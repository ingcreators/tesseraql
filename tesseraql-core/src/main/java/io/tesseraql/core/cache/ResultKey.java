package io.tesseraql.core.cache;

import io.tesseraql.core.sql.BoundParameter;
import io.tesseraql.core.sql.BoundSql;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The identity of a held result (docs/caching.md decision 3): the pool — the connector's name
 * and the resolved tenant — the statement, the row bound, the rendered SQL text and every
 * positional bind. Everything that changes the rows reaches the statement as one of those: a
 * {@code /*%scope … *}{@code /} predicate is spliced into the text with its binds, an ambient
 * {@code principal.*} or {@code tenant.*} value is a bind, a page's clause and cursor are text
 * and binds; the framework sets no session state a connection could carry.
 *
 * <p>The key is one canonical string. Each field is length-prefixed, so a value that happens to
 * contain a separator cannot collide with another key. A bind is rendered as its type and its
 * text — {@code Integer:1} and {@code String:1} are different keys, {@code null} is its own
 * marker — and a bind with no canonical text ({@code byte[]}, a stream, a collection) makes the
 * statement unheld for that request: the caller counts a bypass and executes, it never guesses.
 */
public final class ResultKey {

    private ResultKey() {
    }

    /**
     * The key, or empty when a bind cannot be rendered canonically.
     *
     * @param datasource  the connector name, before tenant routing
     * @param tenantId    the resolved tenant id, or {@code null} for none
     * @param statementId the statement's identity — the resolved file, dialect variant included
     * @param maxRows     the row bound the read executes under (it changes the rows)
     * @param onOverflow  what the bound does when exceeded ({@code warn} truncates)
     * @param bound       the rendered statement and its binds
     */
    public static Optional<String> of(String datasource, String tenantId, String statementId,
            int maxRows, String onOverflow, BoundSql bound) {
        StringBuilder key = new StringBuilder();
        field(key, datasource == null ? "main" : datasource);
        field(key, tenantId == null ? "" : tenantId);
        field(key, statementId == null ? "" : statementId);
        field(key, Integer.toString(maxRows));
        field(key, onOverflow == null ? "" : onOverflow);
        field(key, bound.sql());
        List<BoundParameter> parameters = bound.parameters();
        field(key, Integer.toString(parameters.size()));
        for (BoundParameter parameter : parameters) {
            String rendered = render(parameter.value());
            if (rendered == null) {
                return Optional.empty();
            }
            field(key, rendered);
        }
        return Optional.of(key.toString());
    }

    /**
     * The key of one reference key's rows (docs/caching.md decision 9): the connector, the
     * tenant, the reference's identity — its statement or its call, as the enrichment states
     * it — and the key tuple's components, each rendered as a bind is. Empty when a component
     * has no canonical text, which the caller counts as a bypass.
     *
     * @param datasource  the connector name, or {@code http} for a reference that is a call
     * @param tenantId    the resolved tenant id, or {@code null} for none
     * @param referenceId what identifies the reference across requests: the resolved SQL file
     *                    with its dialect, or the call's method and url template
     * @param keyValues   the key's raw components, in {@code on:} order
     */
    public static Optional<String> ofKey(String datasource, String tenantId, String referenceId,
            List<Object> keyValues) {
        StringBuilder key = new StringBuilder();
        field(key, datasource == null ? "main" : datasource);
        field(key, tenantId == null ? "" : tenantId);
        field(key, referenceId == null ? "" : referenceId);
        field(key, Integer.toString(keyValues.size()));
        for (Object value : keyValues) {
            String rendered = render(value);
            if (rendered == null) {
                return Optional.empty();
            }
            field(key, rendered);
        }
        return Optional.of(key.toString());
    }

    /** One length-prefixed field: {@code <length>:<text>;}. */
    private static void field(StringBuilder key, String text) {
        key.append(text.length()).append(':').append(text).append(';');
    }

    /**
     * A bind's canonical text with its type, or {@code null} when the value has none. Scalars
     * the drivers bind by value are rendered; anything a driver streams or expands is not.
     */
    static String render(Object value) {
        return switch (value) {
            case null -> "null";
            case String text -> "String:" + text;
            case Boolean bool -> "Boolean:" + bool;
            case Character character -> "Character:" + character;
            case BigDecimal decimal -> "BigDecimal:" + decimal.toPlainString();
            case BigInteger integer -> "BigInteger:" + integer;
            case Number number -> number.getClass().getSimpleName() + ":" + number;
            case UUID uuid -> "UUID:" + uuid;
            case Enum<?> constant -> constant.getDeclaringClass().getName() + ":"
                    + constant.name();
            case java.time.temporal.Temporal temporal -> temporal.getClass().getSimpleName()
                    + ":" + temporal;
            case java.time.Duration duration -> "Duration:" + duration;
            case java.time.Period period -> "Period:" + period;
            // java.sql.Date/Time/Timestamp extend java.util.Date; the epoch value is the identity.
            case java.util.Date date -> date.getClass().getSimpleName() + ":" + date.getTime();
            default -> null;
        };
    }
}
