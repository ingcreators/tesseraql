package io.tesseraql.compiler.binding;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.expr.EvaluationContext;
import io.tesseraql.core.outbox.OutboxEvent;
import io.tesseraql.yaml.model.OutboxSpec;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the {@link OutboxEvent} a command route declares, resolving the {@code outbox.payload}
 * expressions against the execution context (design ch. 39.2).
 */
final class OutboxEvents {

    private static final TqlErrorCode SERIALIZE_ERROR = new TqlErrorCode(TqlDomain.SQL, 2600);

    private final OutboxSpec outbox;
    private final String appName;
    private final ObjectMapper mapper = new ObjectMapper();

    OutboxEvents(OutboxSpec outbox, String appName) {
        this.outbox = outbox;
        this.appName = appName;
    }

    /** Builds the insertable event from the (post-execution) context. */
    OutboxEvent build(Map<String, Object> context) {
        EvaluationContext evaluation = new EvaluationContext(context == null ? Map.of() : context);
        String aggregateId = stringValue(evaluation, outbox.aggregateId());
        Map<String, Object> payload = new LinkedHashMap<>();
        Map<String, Map<String, Object>> arrays = new LinkedHashMap<>();
        outbox.payload().forEach((key, expr) -> {
            Object value = evaluation.resolve(Arrays.asList(expr.split("\\.")));
            int marker = key.indexOf("[]");
            if (marker < 0) {
                putNested(payload, key, value);
            } else {
                String arrayPath = key.substring(0, marker);
                String remainder = key.substring(marker + 2);
                String subfield = remainder.startsWith(".") ? remainder.substring(1) : null;
                arrays.computeIfAbsent(arrayPath, k -> new LinkedHashMap<>()).put(subfield, value);
            }
        });
        arrays.forEach((arrayPath, fields) -> putNested(payload, arrayPath, buildArray(fields)));
        try {
            return OutboxEvent.toInsert(outbox.aggregateType(), aggregateId, outbox.eventType(),
                    mapper.writeValueAsString(payload), appName);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new TqlException(SERIALIZE_ERROR, "Failed to serialize outbox payload");
        }
    }

    /**
     * Builds a payload array from {@code [] } payload keys. A single sub-field-less entry
     * ({@code members[]}) yields the resolved list as-is; otherwise each sub-field ({@code
     * members[].value}, {@code members[].display}) contributes a column that is zipped by index into
     * an array of objects. This lets a command route emit, for example, SCIM group members as
     * {@code [{"value":"100"},{"value":"200"}]} from a list of member ids.
     */
    private static List<Object> buildArray(Map<String, Object> fields) {
        if (fields.size() == 1 && fields.containsKey(null)) {
            return toList(fields.get(null));
        }
        Map<String, List<Object>> columns = new LinkedHashMap<>();
        int length = 0;
        for (Map.Entry<String, Object> field : fields.entrySet()) {
            List<Object> column = toList(field.getValue());
            columns.put(field.getKey(), column);
            length = Math.max(length, column.size());
        }
        List<Object> elements = new java.util.ArrayList<>();
        for (int i = 0; i < length; i++) {
            Map<String, Object> element = new LinkedHashMap<>();
            for (Map.Entry<String, List<Object>> column : columns.entrySet()) {
                if (i < column.getValue().size()) {
                    element.put(column.getKey(), column.getValue().get(i));
                }
            }
            elements.add(element);
        }
        return elements;
    }

    private static List<Object> toList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            return new java.util.ArrayList<>(list);
        }
        if (value instanceof java.util.Collection<?> collection) {
            return new java.util.ArrayList<>(collection);
        }
        return List.of(value);
    }

    /**
     * Places {@code value} into {@code root} at a dotted payload key, creating nested objects as
     * needed (e.g. {@code name.givenName} yields {@code {"name":{"givenName":...}}}). This lets a
     * command route emit a structured event payload — such as a SCIM resource for a provisioning
     * event — directly from its {@code outbox.payload} declaration. Flat keys (no dot) are unchanged.
     */
    @SuppressWarnings("unchecked")
    private static void putNested(Map<String, Object> root, String dottedKey, Object value) {
        String[] parts = dottedKey.split("\\.");
        Map<String, Object> node = root;
        for (int i = 0; i < parts.length - 1; i++) {
            Object child = node.get(parts[i]);
            if (!(child instanceof Map)) {
                child = new LinkedHashMap<String, Object>();
                node.put(parts[i], child);
            }
            node = (Map<String, Object>) child;
        }
        node.put(parts[parts.length - 1], value);
    }

    private static String stringValue(EvaluationContext evaluation, String expression) {
        if (expression == null) {
            return null;
        }
        Object value = evaluation.resolve(Arrays.asList(expression.split("\\.")));
        return value == null ? null : String.valueOf(value);
    }
}
