package io.tesseraql.yaml.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.yaml.JsonMappers;
import java.util.AbstractList;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A JSON value parsed from a column declared {@code type: json} (docs/temporal-semantics.md
 * T3, decision 7 for what it is not): an object is a {@link Map}, an array a {@link List}, so
 * an expression, a template or a response binding navigates it ({@code payload.sku}) and the
 * JSON mapper writes it as a structure — and every container's {@code toString()} is compact
 * JSON, so the surfaces that print a value ({@code String.valueOf}: an HTML cell, a CSV field,
 * Studio, a suite expectation) print JSON text and never {@code {sku=A-1}}. A scalar document
 * is the scalar itself: a string, a number ({@link java.math.BigDecimal} for a fraction, so
 * {@code 1.10} stays {@code 1.10}), a boolean, or {@code null}.
 *
 * <p>The parser is Jackson's, so it lives here rather than in {@code tesseraql-core}, which
 * is dependency-free by an enforced rule; the containers are plain collections, which is what
 * lets core's own navigation read them.
 */
public final class JsonValues {

    private static final ObjectMapper MAPPER = JsonMappers.constrained()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private JsonValues() {
    }

    /**
     * The value of {@code text}: a container, a scalar, or {@code null} for the JSON literal.
     *
     * @throws IllegalArgumentException when the text is not a JSON document, with the parser's
     *                                  own reason
     */
    public static Object parse(String text) {
        Object tree;
        try {
            tree = MAPPER.readValue(text, Object.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException(ex.getOriginalMessage(), ex);
        }
        return wrap(tree);
    }

    /** Every container in a parsed tree replaced by its canonical-text twin, scalars kept. */
    public static Object wrap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> members = new LinkedHashMap<>();
            map.forEach((key, member) -> members.put(String.valueOf(key), wrap(member)));
            return new JsonObject(members);
        }
        if (value instanceof List<?> list) {
            List<Object> elements = new ArrayList<>(list.size());
            for (Object element : list) {
                elements.add(wrap(element));
            }
            return new JsonArray(elements);
        }
        return value;
    }

    /** The compact JSON text of a value, as the mapper writes it. */
    static String text(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** A JSON object: a map whose text is its JSON. */
    public static final class JsonObject extends AbstractMap<String, Object> {

        private final Map<String, Object> members;

        JsonObject(Map<String, Object> members) {
            this.members = members;
        }

        @Override
        public Set<Entry<String, Object>> entrySet() {
            return java.util.Collections.unmodifiableMap(members).entrySet();
        }

        @Override
        public Object get(Object key) {
            return members.get(key);
        }

        @Override
        public boolean containsKey(Object key) {
            return members.containsKey(key);
        }

        @Override
        public int size() {
            return members.size();
        }

        @Override
        public String toString() {
            return text(this);
        }
    }

    /** A JSON array: a list whose text is its JSON. */
    public static final class JsonArray extends AbstractList<Object> {

        private final List<Object> elements;

        JsonArray(List<Object> elements) {
            this.elements = elements;
        }

        @Override
        public Object get(int index) {
            return elements.get(index);
        }

        @Override
        public int size() {
            return elements.size();
        }

        @Override
        public String toString() {
            return text(this);
        }
    }
}
