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

    private final PageSpec spec;

    public PageBinder(PageSpec spec) {
        this.spec = spec;
    }

    @Override
    public void process(Exchange exchange) {
        int size = size(exchange);
        if (PageSpec.KEYSET.equals(spec.effectiveStrategy())) {
            List<String> by = spec.effectiveBy();
            if (by.size() > 1) {
                publishAfterParts(exchange, by);
            }
            exchange.setProperty(TesseraqlProperties.PAGE,
                    new PageRequest(1, size, 0, spec.count(), by));
            return;
        }
        long number = positiveLong(exchange, "page", 1);
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
        long requested = positiveLong(exchange, "size", declared);
        return (int) Math.min(requested, spec.maxSize().longValue());
    }

    private static long positiveLong(Exchange exchange, String name, long fallback) {
        String raw = exchange.request().param(name);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            long value = Long.parseLong(raw.trim());
            if (value < 1) {
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
