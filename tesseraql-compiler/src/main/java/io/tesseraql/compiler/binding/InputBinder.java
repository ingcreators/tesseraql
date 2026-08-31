package io.tesseraql.compiler.binding;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.files.ColumnMapping;
import io.tesseraql.core.files.ColumnValues;
import io.tesseraql.yaml.model.InputField;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Validates and coerces declared route inputs into typed effective values (design ch. 6.3, 33.1).
 *
 * <p>Only declared inputs are produced (input whitelisting). Missing inputs fall back to their
 * declared default; required inputs without a value are rejected. Type coercion and the
 * {@code min}/{@code max}/{@code maxLength}/{@code enum} constraints are applied here.
 *
 * <p>{@code date}/{@code datetime}/{@code number} inputs parse with the negotiated request locale
 * and the field's {@code format} pattern, through the same machinery as file-transfer columns
 * (roadmap Phase 22). Rejections raise {@code TQL-FIELD-2001} with a field-scoped error entry —
 * a stable code and a {@code tql.input.<code>} message key the error renderer localizes — so a
 * bad form value surfaces inline next to its input like a validation violation.
 */
public final class InputBinder {

    private static final TqlErrorCode VALIDATION = new TqlErrorCode(TqlDomain.FIELD, 2001);
    /** an array element's undeclared or non-writable field (the mass-assignment guard). */
    private static final TqlErrorCode FIELD_REJECTED = new TqlErrorCode(TqlDomain.FIELD, 2002);
    private static final System.Logger LOG = System.getLogger(InputBinder.class.getName());

    private InputBinder() {
    }

    /** Binds with the English/default locale (locale-less callers and tests). */
    public static Map<String, Object> bind(Map<String, InputField> inputs,
            Function<String, String> rawLookup) {
        return bind(inputs, rawLookup, Locale.ENGLISH);
    }

    /**
     * Binds raw string-valued request inputs to typed effective values.
     *
     * @param inputs the declared input fields
     * @param rawLookup resolves a raw request value by input name (e.g. an HTTP query parameter)
     * @param locale the negotiated request locale driving date/number parsing
     */
    public static Map<String, Object> bind(Map<String, InputField> inputs,
            Function<String, String> rawLookup, Locale locale) {
        return bind(inputs, rawLookup, name -> null, locale);
    }

    /**
     * Binds with a structured lookup beside the raw one, for values a request carries as
     * something other than text.
     *
     * <p>A JSON body's array arrived here as {@code String.valueOf(list)}, so a declared
     * {@code type: array} input produced the list's {@code toString()} —
     * {@code [{productId=10, quantity=1}]} — as its effective value, and any binding reading
     * {@code params.<name>} put that text into SQL. Not merely unvalidated: corrupted, and
     * silently, because {@code body.<name>} kept the real list and most routes happened to read
     * that instead.
     *
     * @param structuredLookup resolves a value that is already a list or map, or null
     */
    public static Map<String, Object> bind(Map<String, InputField> inputs,
            Function<String, String> rawLookup, Function<String, Object> structuredLookup,
            Locale locale) {
        return bind(inputs, rawLookup, structuredLookup, locale, null);
    }

    /**
     * The same binding with the app's code catalogs available, so a field declaring
     * {@code codes:} is checked against the catalog it names (docs/lookups.md, decision 11).
     */
    public static Map<String, Object> bind(Map<String, InputField> inputs,
            Function<String, String> rawLookup, Function<String, Object> structuredLookup,
            Locale locale, io.tesseraql.core.catalog.CatalogStore catalogs) {
        return bind(inputs, rawLookup, structuredLookup, locale, catalogs,
                ElementRules.defaults());
    }

    /**
     * What binding an object element needs beyond its own declarations: the route's input policy
     * — an element's undeclared or non-writable field is the same guard one level down — and the
     * expression functions an element's {@code requiredWhen} may call.
     */
    public record ElementRules(io.tesseraql.yaml.model.InputPolicy policy,
            io.tesseraql.core.expr.ExpressionFunctions functions) {

        /** Deny-by-default, with the process's standard function set. */
        public static ElementRules defaults() {
            return new ElementRules(io.tesseraql.yaml.model.InputPolicy.defaults(),
                    io.tesseraql.core.expr.ExpressionFunctions.processDefault());
        }
    }

    /**
     * The full binding: object array elements ({@code items.fields:}) bind, coerce and validate
     * exactly as top-level fields do, and a violation addresses itself by index —
     * {@code lines[2].qty} — riding the field-error contract unchanged
     * (docs/declarative-validation.md, "Line items").
     */
    public static Map<String, Object> bind(Map<String, InputField> inputs,
            Function<String, String> rawLookup, Function<String, Object> structuredLookup,
            Locale locale, io.tesseraql.core.catalog.CatalogStore catalogs,
            ElementRules elements) {
        Map<String, Object> effective = new LinkedHashMap<>();
        for (Map.Entry<String, InputField> entry : inputs.entrySet()) {
            String name = entry.getKey();
            InputField field = entry.getValue();

            if ("array".equals(field.type())) {
                Object structured = structuredLookup.apply(name);
                if (structured instanceof java.util.List<?> list) {
                    effective.put(name,
                            validateElements(name, field, list, locale, catalogs, elements));
                    continue;
                }
                if (structured == null && field.required()) {
                    throw reject(name, "required", Map.of(),
                            "Missing required input '" + name + "'");
                }
                if (structured == null) {
                    continue;
                }
                throw reject(name, "type", Map.of("type", "array"),
                        "Input '" + name + "' must be an array");
            }

            String raw = rawLookup.apply(name);

            if (raw == null || raw.isEmpty()) {
                if (field.required()) {
                    throw reject(name, "required", Map.of(),
                            "Missing required input '" + name + "'");
                }
                if (field.defaultValue() != null) {
                    effective.put(name, field.defaultValue());
                }
                continue;
            }
            effective.put(name, validate(name, field, coerce(name, field, raw, locale),
                    catalogs));
        }
        publishSortFragments(inputs, effective);
        return effective;
    }

    /**
     * For every {@code type: sort} input with a value, publish the validated ORDER BY fragment
     * as the {@code <name>Sql} sibling (docs/list-surface.md decision 7) — the value the
     * authored {@code /*# order by {sort} *&#47;} directive binds, while the param itself keeps
     * the wire form ({@code -ship,order}) for links and saved URLs. A legacy declared
     * {@code dir} param still applies to a bare single-column value, so existing header links
     * keep working unchanged.
     */
    private static void publishSortFragments(Map<String, InputField> inputs,
            Map<String, Object> effective) {
        for (Map.Entry<String, InputField> entry : inputs.entrySet()) {
            InputField field = entry.getValue();
            if (!"sort".equals(field.type())) {
                continue;
            }
            Object wire = effective.get(entry.getKey());
            if (wire == null) {
                continue;
            }
            String dir = effective.get("dir") instanceof String legacy ? legacy : null;
            effective.put(entry.getKey() + "Sql", sortSql(String.valueOf(wire), dir));
        }
    }

    /** Parses and validates a sort wire value; returns the canonical form unchanged. */
    private static String coerceSort(String name, InputField field, String raw) {
        List<String> allowed = field.columns() == null ? List.of() : field.columns();
        StringBuilder canonical = new StringBuilder();
        for (String token : raw.split(",")) {
            String key = token.strip();
            if (key.isEmpty()) {
                continue;
            }
            String column = key.startsWith("-") ? key.substring(1) : key;
            if (!allowed.contains(column)) {
                throw reject(name, "sort", Map.of("column", column),
                        "Input '" + name + "' names a column outside its declared sort set: "
                                + column);
            }
            if (!canonical.isEmpty()) {
                canonical.append(',');
            }
            canonical.append(key);
        }
        if (canonical.isEmpty()) {
            throw reject(name, "sort", Map.of(), "Input '" + name + "' carries no sort key");
        }
        return canonical.toString();
    }

    /** The safe ORDER BY fragment a validated wire value expands to. */
    private static String sortSql(String wire, String legacyDir) {
        StringBuilder sql = new StringBuilder();
        String[] tokens = wire.split(",");
        boolean bareSingle = tokens.length == 1 && !tokens[0].startsWith("-");
        for (String token : tokens) {
            boolean descending = token.startsWith("-")
                    || (bareSingle && "desc".equalsIgnoreCase(legacyDir));
            String column = token.startsWith("-") ? token.substring(1) : token;
            if (!sql.isEmpty()) {
                sql.append(", ");
            }
            sql.append(column).append(descending ? " desc" : " asc");
        }
        return sql.toString();
    }

    private static Object coerce(String name, InputField field, String raw, Locale locale) {
        String type = field.type() == null ? "string" : field.type();
        return switch (type) {
            case "sort" -> coerceSort(name, field, raw);
            case "integer" -> parseLong(name, raw);
            case "number" -> field.format() == null
                    ? parseDouble(name, raw)
                    : parseFormatted(name, field, "number", raw, locale);
            case "boolean" -> Boolean.parseBoolean(raw);
            case "date", "datetime" -> parseFormatted(name, field, type, raw, locale);
            default -> raw;
        };
    }

    /**
     * Checks each element against {@code items:}, which nothing read before.
     *
     * <p>An app declaring {@code items: {type: integer}} or an element enum believed the elements
     * were checked; they were not, and the declaration was documented with a shipped example.
     */
    private static java.util.List<Object> validateElements(String name, InputField field,
            java.util.List<?> values, Locale locale,
            io.tesseraql.core.catalog.CatalogStore catalogs, ElementRules rules) {
        InputField.InputItems items = field.items();
        java.util.List<Object> checked = new java.util.ArrayList<>(values.size());
        for (int i = 0; i < values.size(); i++) {
            Object element = values.get(i);
            if (items == null || element == null) {
                checked.add(element);
                continue;
            }
            String at = name + "[" + i + "]";
            if (items.hasFields()) {
                checked.add(bindElement(at, items, element, i, locale, catalogs, rules));
                continue;
            }
            if (items.type() != null) {
                element = coerceElement(at, items.type(), element);
            }
            if (!items.enumValues().isEmpty()
                    && !items.enumValues().contains(String.valueOf(element))) {
                throw reject(name, "enum", Map.of("allowed", items.enumValues()),
                        "Element " + at + " is not one of " + items.enumValues());
            }
            checked.add(element);
        }
        return java.util.List.copyOf(checked);
    }

    /**
     * Binds one object element against {@code items.fields:} (docs/declarative-validation.md,
     * "Line items"): the deny-by-default posture {@code input:} holds at the top level applies
     * inside the one place a business form carries most of its data.
     *
     * <p>Every rejection names the element it came from — {@code lines[2].qty} — so a grid form
     * can address the offending cell through the field-error contract it already reads.
     */
    private static Map<String, Object> bindElement(String at, InputField.InputItems items,
            Object element, int index, Locale locale,
            io.tesseraql.core.catalog.CatalogStore catalogs, ElementRules rules) {
        if (!(element instanceof Map<?, ?> raw)) {
            throw reject(at, "type", Map.of("type", "object"),
                    "Element " + at + " must be an object");
        }
        Map<String, Object> supplied = new LinkedHashMap<>();
        raw.forEach((key, value) -> supplied.put(String.valueOf(key), value));
        guardElement(at, items, supplied, rules);

        Map<String, Object> bound = new LinkedHashMap<>();
        items.fields().forEach((fieldName, declared) -> {
            String path = at + "." + fieldName;
            Object value = supplied.get(fieldName);
            String text = value == null ? null : String.valueOf(value);
            if (text == null || text.isEmpty()) {
                if (declared.required()) {
                    throw reject(path, "required", Map.of(),
                            "Missing required input '" + path + "'");
                }
                if (declared.defaultValue() != null) {
                    bound.put(fieldName, declared.defaultValue());
                }
                return;
            }
            bound.put(fieldName,
                    validate(path, declared, coerce(path, declared, text, locale), catalogs));
        });
        requireConditional(at, items, bound, index, rules);
        return bound;
    }

    /**
     * The mass-assignment guard, one level down: an element field nothing declared follows the
     * route's {@code inputPolicy.unknownFields}, and a non-writable one follows its
     * {@code readOnlyFieldBehavior} — the same two answers, at the same two codes, as the
     * top-level guard gives.
     */
    private static void guardElement(String at, InputField.InputItems items,
            Map<String, Object> supplied, ElementRules rules) {
        java.util.List<String> dropped = new java.util.ArrayList<>();
        for (String key : supplied.keySet()) {
            InputField declared = items.fields().get(key);
            if (declared == null) {
                if (rules.policy().rejectsUnknownFields()) {
                    throw new TqlException(FIELD_REJECTED,
                            "Unknown input field '" + at + "." + key + "'");
                }
                dropped.add(key);
                continue;
            }
            if (declared.isWritable()) {
                continue;
            }
            switch (rules.policy().readOnlyBehaviorOrDefault()) {
                case "ignore" -> dropped.add(key);
                case "warn" -> {
                    LOG.log(System.Logger.Level.WARNING,
                            "Ignoring non-writable input field ''{0}''", at + "." + key);
                    dropped.add(key);
                }
                default -> throw new TqlException(FIELD_REJECTED,
                        "Field '" + at + "." + key + "' is not writable");
            }
        }
        dropped.forEach(supplied::remove);
    }

    /**
     * An element's {@code requiredWhen:} conditions, evaluated once the element is coerced. The
     * expression sees the element as {@code item.*} and its position as {@code item_index} — a
     * line's requiredness is a property of that line, not of the request around it.
     */
    private static void requireConditional(String at, InputField.InputItems items,
            Map<String, Object> bound, int index, ElementRules rules) {
        Map<String, Object> scope = null;
        for (Map.Entry<String, InputField> entry : items.fields().entrySet()) {
            InputField declared = entry.getValue();
            String condition = declared.requiredWhen();
            if (condition == null || condition.isBlank() || bound.containsKey(entry.getKey())) {
                continue;
            }
            if (scope == null) {
                scope = Map.of("item", bound, "item_index", index);
            }
            // Parsed here rather than memoized by text: a parsed tree captures the functions it
            // resolved against (docs/module-scope.md), and a process-wide text cache would hand
            // one application's expression the functions another application declared. The
            // route binder parses these once at build too, so bad syntax still fails the build.
            io.tesseraql.core.expr.Expr expr = io.tesseraql.core.expr.ExpressionParser
                    .parse(condition, rules.functions());
            if (expr.evalBoolean(new io.tesseraql.core.expr.EvaluationContext(scope))) {
                throw missingRequired(at + "." + entry.getKey());
            }
        }
    }

    /** Element coercion mirrors the scalar rules; an unparseable element names its index. */
    private static Object coerceElement(String at, String type, Object element) {
        String text = String.valueOf(element);
        return switch (type) {
            case "integer" -> parseLong(at, text);
            case "number" -> parseDouble(at, text);
            case "boolean" -> Boolean.parseBoolean(text);
            default -> element;
        };
    }

    private static Object validate(String name, InputField field, Object value,
            io.tesseraql.core.catalog.CatalogStore catalogs) {
        if (value instanceof Number number) {
            // Decimal-exact bounds (roadmap Phase 40): 5.9 violates max: 5, and fractional
            // bounds like min: 0.5 are declarable — no long truncation.
            java.math.BigDecimal decimal = new java.math.BigDecimal(number.toString());
            if (field.min() != null && decimal.compareTo(field.min()) < 0) {
                throw reject(name, "min", Map.of("min", field.min()),
                        "Input '" + name + "' below minimum " + field.min());
            }
            if (field.max() != null && decimal.compareTo(field.max()) > 0) {
                throw reject(name, "max", Map.of("max", field.max()),
                        "Input '" + name + "' above maximum " + field.max());
            }
        }
        if (value instanceof String string) {
            if (field.maxLength() != null && string.length() > field.maxLength()) {
                throw reject(name, "maxLength", Map.of("maxLength", field.maxLength()),
                        "Input '" + name + "' exceeds maxLength " + field.maxLength());
            }
            if (field.minLength() != null && string.length() < field.minLength()) {
                throw reject(name, "minLength", Map.of("minLength", field.minLength()),
                        "Input '" + name + "' is shorter than minLength " + field.minLength());
            }
            if (field.pattern() != null && !compiled(field.pattern()).matcher(string).matches()) {
                throw reject(name, "pattern", Map.of("pattern", field.pattern()),
                        "Input '" + name + "' does not match the declared pattern");
            }
            if (field.hasStringFormat() && !matchesFormat(field.format(), string)) {
                throw reject(name, field.format(), Map.of(),
                        "Input '" + name + "' is not a valid " + field.format());
            }
            if (field.enumValues() != null && !field.enumValues().isEmpty()
                    && !field.enumValues().contains(string)) {
                throw reject(name, "enum",
                        Map.of("options", String.join(", ", field.enumValues())),
                        "Input '" + name + "' is not one of " + field.enumValues());
            }
            validateAgainstCatalog(name, field, string, catalogs);
        }
        return value;
    }

    /**
     * The {@code codes:} check (docs/lookups.md, decision 11). A catalog is a dynamic enum, so
     * the violation reuses the enum field error rather than teaching clients and suites a
     * second shape.
     *
     * <p>A miss is not an answer. The held copy may simply be older than a code an operator
     * added a minute ago, so a value it does not carry is re-read from the source before it is
     * refused — the cost lands only on the rejection path. Only <em>active</em> codes are
     * accepted, because a retired code must still render on old data and must not be written to
     * new.
     */
    private static void validateAgainstCatalog(String name, InputField field, String value,
            io.tesseraql.core.catalog.CatalogStore catalogs) {
        if (field.codes() == null || field.codes().isBlank() || catalogs == null) {
            return;
        }
        io.tesseraql.core.catalog.CodeCatalog catalog = catalogs.catalog(field.codes());
        if (catalog != null && offers(catalog, value)) {
            return;
        }
        io.tesseraql.core.catalog.CodeCatalog fresh = catalogs.reload(field.codes());
        if (fresh != null && offers(fresh, value)) {
            return;
        }
        throw reject(name, "enum", Map.of("catalog", field.codes()),
                "Input '" + name + "' is not an active code of '" + field.codes() + "'");
    }

    /** Whether the catalog offers this code — present, and not retired. */
    private static boolean offers(io.tesseraql.core.catalog.CodeCatalog catalog, String value) {
        return catalog.options().stream()
                .anyMatch(entry -> value.equals(String.valueOf(entry.key())));
    }

    private static long parseLong(String name, String raw) {
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ex) {
            throw reject(name, "integer", Map.of(),
                    "Input '" + name + "' is not an integer: " + raw);
        }
    }

    private static double parseDouble(String name, String raw) {
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException ex) {
            throw reject(name, "number", Map.of(),
                    "Input '" + name + "' is not a number: " + raw);
        }
    }

    /** Locale-aware parsing through the file-transfer column machinery (mirrors import-side). */
    private static Object parseFormatted(String name, InputField field, String type, String raw,
            Locale locale) {
        try {
            return ColumnValues.parse(
                    new ColumnMapping(name, null, null, type, field.format()), raw, locale);
        } catch (IllegalArgumentException ex) {
            Map<String, Object> params = field.format() == null
                    ? Map.of()
                    : Map.of("format", field.format());
            throw reject(name, type, params,
                    "Input '" + name + "' is not a valid " + type + ": " + raw);
        }
    }

    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.regex.Pattern> PATTERNS = new java.util.concurrent.ConcurrentHashMap<>();

    /** Compiles (and caches) a declared {@code pattern:}; the lint pre-checks the syntax. */
    private static java.util.regex.Pattern compiled(String pattern) {
        return PATTERNS.computeIfAbsent(pattern, java.util.regex.Pattern::compile);
    }

    /**
     * Pragmatic, JDK-only semantic formats (roadmap Phase 40): {@code email} is
     * local@domain.tld shaped, {@code uuid} parses via {@link java.util.UUID}, {@code url}
     * needs an absolute http(s) URI with a host.
     */
    static boolean matchesFormat(String format, String value) {
        return switch (format) {
            case "email" -> value.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
            case "uuid" -> {
                try {
                    java.util.UUID.fromString(value);
                    yield true;
                } catch (IllegalArgumentException ex) {
                    yield false;
                }
            }
            case "url" -> {
                try {
                    java.net.URI uri = java.net.URI.create(value);
                    yield ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                            && uri.getHost() != null;
                } catch (IllegalArgumentException ex) {
                    yield false;
                }
            }
            default -> true;
        };
    }

    /** The conditional-required rejection ({@code requiredWhen}, roadmap Phase 40). */
    static TqlException missingRequired(String name) {
        return reject(name, "required", Map.of(), "Missing required input '" + name + "'");
    }

    /** A field-scoped rejection: stable code, localizable message key, constraint params. */
    private static TqlException reject(String name, String code, Map<String, ?> params,
            String logMessage) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("field", name);
        field.put("code", code);
        field.put("message", "tql.input." + code);
        field.putAll(params);
        return TqlException.builder(VALIDATION)
                .message(logMessage)
                .details(Map.of("fields", List.of(field)))
                .build();
    }
}
