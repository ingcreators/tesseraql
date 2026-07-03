package io.tesseraql.compiler.binding;

import io.tesseraql.camel.PageRequest;
import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.model.PageSpec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

/**
 * Computes the page window a paginated query executes under (roadmap Phase 41). The
 * {@code page}/{@code size} request parameters are framework-owned (never declared inputs):
 * {@code page} is 1-based, {@code size} is only honoured up to the declared {@code maxSize}.
 * Rejections are the standard field-scoped {@code TQL-FIELD-2001} shape, so a bad page number
 * renders like any other input error.
 */
public final class PageBinder implements Processor {

    private static final TqlErrorCode VALIDATION = new TqlErrorCode(TqlDomain.FIELD, 2001);

    private final PageSpec spec;

    public PageBinder(PageSpec spec) {
        this.spec = spec;
    }

    @Override
    public void process(Exchange exchange) {
        int size = size(exchange);
        if (PageSpec.KEYSET.equals(spec.effectiveStrategy())) {
            exchange.setProperty(TesseraqlProperties.PAGE,
                    new PageRequest(1, size, 0, spec.count(), spec.by()));
            return;
        }
        long number = positiveLong(exchange, "page", 1);
        exchange.setProperty(TesseraqlProperties.PAGE,
                new PageRequest(number, size, (number - 1) * (long) size, spec.count(), null));
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
        String raw = exchange.getMessage().getHeader(name, String.class);
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
