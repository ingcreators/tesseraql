package io.tesseraql.compiler.binding;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.PageRequest;
import io.tesseraql.pipeline.Step;
import io.tesseraql.pipeline.TesseraqlProperties;
import io.tesseraql.yaml.model.PageSpec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Computes the page window a paginated query executes under (roadmap Phase 41). The
 * {@code page}/{@code size} request parameters are framework-owned (never declared inputs):
 * {@code page} is 1-based, {@code size} is only honoured up to the declared {@code maxSize}.
 * Rejections are the standard field-scoped {@code TQL-FIELD-2001} shape, so a bad page number
 * renders like any other input error.
 */
public final class PageBinder implements Step {

    private static final TqlErrorCode VALIDATION = new TqlErrorCode(TqlDomain.FIELD, 2001);

    /**
     * TQL-FIELD-4222: a snapshot page fetch posted more membership keys than the declared cap
     * (HTTP 422). The search itself renders over-cap in-page as the result-cap reject block
     * (docs/hc-recipe-alignment.md); a keys list this long can only be a broken or hostile
     * client, because the framework never rendered that many.
     */
    static final TqlErrorCode SNAPSHOT_OVER_CAP = new TqlErrorCode(TqlDomain.FIELD, 4222);

    private final PageSpec spec;
    private final List<String> snapshotKey;

    public PageBinder(PageSpec spec) {
        this(spec, null);
    }

    /**
     * The snapshot variant (docs/list-surface.md decision 10): {@code snapshotKey} is the
     * acting list view's declared {@code key:}, used to decode the posted membership tokens.
     */
    public PageBinder(PageSpec spec, List<String> snapshotKey) {
        this.spec = spec;
        this.snapshotKey = snapshotKey;
    }

    @Override
    public void process(Exchange exchange) {
        int size = size(exchange);
        if (PageSpec.SNAPSHOT.equals(spec.effectiveStrategy())) {
            List<String> posted = exchange.request().formFields().get("keys");
            if (posted == null || posted.isEmpty()) {
                // The search: fetch the whole membership; the producer's size+1 over-fetch is
                // the over-cap detection the view renders the reject block on.
                exchange.setProperty(TesseraqlProperties.PAGE,
                        new PageRequest(1, spec.effectiveCap(), 0, false, null));
            } else {
                publishSnapshotSlice(exchange, posted, size);
            }
            return;
        }
        if (PageSpec.KEYSET.equals(spec.effectiveStrategy())) {
            List<String> by = spec.effectiveBy();
            if (by.size() > 1) {
                publishAfterParts(exchange, by);
            }
            exchange.setProperty(TesseraqlProperties.PAGE,
                    new PageRequest(1, size, 0, spec.count(), by));
            return;
        }
        long number = positiveLong(exchange, "page", 1,
                io.tesseraql.yaml.model.PageSpec.MAX_PAGE);
        exchange.setProperty(TesseraqlProperties.PAGE,
                new PageRequest(number, size, (number - 1) * (long) size, spec.count(), null));
    }

    /**
     * Decodes a composite {@code ?after=} row token into {@code params.after.<column>} parts
     * (docs/list-surface.md decision 5), so the authored tuple predicate binds them like any
     * other params expression. A single-column cursor stays the author's own declared
     * {@code after} input, exactly as before.
     */
    @SuppressWarnings("unchecked")
    private static void publishAfterParts(Exchange exchange, List<String> by) {
        String token = exchange.request().param("after");
        if (token == null || token.isBlank()) {
            return;
        }
        List<String> values;
        try {
            values = io.tesseraql.core.rows.RowTokens.decode(token.trim(), by);
        } catch (IllegalArgumentException ex) {
            throw reject("after", token);
        }
        Object raw = exchange.getProperty(TesseraqlProperties.CONTEXT);
        if (!(raw instanceof Map)) {
            return;
        }
        Map<String, Object> context = (Map<String, Object>) raw;
        Map<String, Object> params = context.get("params") instanceof Map<?, ?> existing
                ? new LinkedHashMap<>((Map<String, Object>) existing)
                : new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>();
        for (int i = 0; i < by.size(); i++) {
            after.put(by.get(i), coerce(values.get(i)));
        }
        params.put("after", after);
        context.put("params", params);
    }

    /**
     * A snapshot page fetch (docs/list-surface.md decision 10): the posted membership tokens
     * are the snapshot, this page's slice decodes into typed {@code params.keys} for the
     * authored IN predicate, and the {@code snapshot} context entry carries what the view
     * re-renders — the whole membership, the page number, and the totals. Membership never
     * changes here; only a new search changes it.
     */
    @SuppressWarnings("unchecked")
    private void publishSnapshotSlice(Exchange exchange, List<String> posted, int size) {
        // Single-column keys only for now — the composite tuple-IN binding is the recorded
        // follow-up (docs/list-surface.md open question 3's residue).
        if (snapshotKey == null || snapshotKey.size() != 1) {
            return;
        }
        if (posted.size() > spec.effectiveCap()) {
            throw new TqlException(SNAPSHOT_OVER_CAP, "Snapshot page fetch posted "
                    + posted.size() + " keys — more than the declared cap "
                    + spec.effectiveCap() + " (pagination.cap, docs/list-surface.md"
                    + " decision 10)");
        }
        long number = positiveLong(exchange, "page", 1,
                io.tesseraql.yaml.model.PageSpec.MAX_PAGE);
        int from = (int) Math.min((number - 1) * size, posted.size());
        int to = (int) Math.min(number * size, posted.size());
        List<Object> keys = new java.util.ArrayList<>(to - from);
        for (String token : posted.subList(from, to)) {
            try {
                keys.add(coerce(
                        io.tesseraql.core.rows.RowTokens.decode(token, snapshotKey).get(0)));
            } catch (IllegalArgumentException ex) {
                throw reject("keys", token);
            }
        }
        Object raw = exchange.getProperty(TesseraqlProperties.CONTEXT);
        if (!(raw instanceof Map)) {
            return;
        }
        Map<String, Object> context = (Map<String, Object>) raw;
        Map<String, Object> params = context.get("params") instanceof Map<?, ?> existing
                ? new LinkedHashMap<>((Map<String, Object>) existing)
                : new LinkedHashMap<>();
        params.put("keys", keys);
        context.put("params", params);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("keys", List.copyOf(posted));
        snapshot.put("number", number);
        snapshot.put("size", size);
        context.put("snapshot", snapshot);
    }

    /** Cursor parts travel as canonical text; numeric ones bind as numbers again. */
    private static Object coerce(String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException notLong) {
            try {
                return new java.math.BigDecimal(value);
            } catch (NumberFormatException notNumeric) {
                return value;
            }
        }
    }

    private int size(Exchange exchange) {
        int declared = spec.effectiveSize();
        if (spec.maxSize() == null) {
            return declared;
        }
        long requested = positiveLong(exchange, "size", declared, Long.MAX_VALUE);
        return (int) Math.min(requested, spec.maxSize().longValue());
    }

    /**
     * A declared page or size: absent, or an integer within its bounds.
     *
     * <p>{@code max} is what stops the arithmetic downstream from wrapping. The two {@code page}
     * call sites pass {@link io.tesseraql.yaml.model.PageSpec#MAX_PAGE}; the {@code size} call
     * site passes {@code Long.MAX_VALUE} deliberately, because it is already clamped by
     * {@code Math.min} against a declared {@code maxSize} the contract publishes — a page-shaped
     * bound there would silently refuse a large {@code ?size=} that is harmless today.
     *
     * <p>The refusal reuses {@code reject}, adding no new message literal: the reference
     * generator joins at most two meanings per code and this one's cell is already at that cap,
     * so a bespoke sentence would displace a published one.
     */
    private static long positiveLong(Exchange exchange, String name, long fallback, long max) {
        String raw = exchange.request().param(name);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            long value = Long.parseLong(raw.trim());
            if (value < 1 || value > max) {
                throw reject(name, raw);
            }
            return value;
        } catch (NumberFormatException ex) {
            throw reject(name, raw);
        }
    }

    private static TqlException reject(String name, String raw) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("field", name);
        field.put("code", "page");
        field.put("message", "tql.input.page");
        return TqlException.builder(VALIDATION)
                .message("Invalid " + name + " parameter: " + raw)
                .details(Map.of("fields", List.of(field)))
                .build();
    }
}
