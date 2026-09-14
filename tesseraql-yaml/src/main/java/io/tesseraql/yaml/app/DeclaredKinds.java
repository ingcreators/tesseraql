package io.tesseraql.yaml.app;

import io.tesseraql.core.dialect.TemporalText;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.files.ColumnMapping;
import io.tesseraql.core.files.ColumnValueException;
import io.tesseraql.core.files.ColumnValues;
import io.tesseraql.yaml.model.Binding;
import io.tesseraql.yaml.model.InputField;
import io.tesseraql.yaml.model.RouteDefinition;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The {@code type:} a field declares, judged where it is declared (docs/temporal-semantics.md
 * T3): a request {@code input:} <em>binds</em> a value into its type, a binding's
 * {@code result:} <em>parses</em> a column's text into its kind, and each surface has the
 * vocabulary its reader honours. One predicate on both sides — the linter reports from it and
 * the compiler refuses from it — so a declaration the runtime would silently pass through as
 * text is refused where it was written instead.
 *
 * <p>A result declaration is sparse: an undeclared column keeps the kind the database gave it.
 * A declared one is parsed after the read seam and rendered as a native column of that kind
 * is — a {@code date} stored as text and a {@code date} column become the same case on every
 * surface. A {@code result:} entry reads {@code type}, {@code format}, {@code locale} and
 * {@code domain} (and carries a {@code description}); the domain's constraint keys
 * ({@code maxLength}, {@code pattern}, {@code enum}, {@code min}, {@code max}) are not applied
 * on read — validating what the database returned is a different feature — and one written on
 * the entry itself is refused (decision 24), since the loader merges a domain into an entry
 * by the read keys alone and a constraint key can then only have been written there.
 */
public final class DeclaredKinds {

    /**
     * TQL-YAML-1064: a declared field key its surface does not honour — a {@code type:} no
     * request binds on {@code input:} ({@code json}, or a name outside the input vocabulary), a
     * {@code locale:} written on an {@code input:} entry (a request parses in its own locale),
     * a {@code type:} no read parses on {@code result:} (anything but json, date, datetime and
     * number), a {@code format:} the kind's parser refuses, a {@code locale:} the JDK cannot
     * format, a constraint or operational key written on a {@code result:} entry (applied
     * nowhere on a read), or a {@code result:} on a binding that holds no rows to apply it to.
     * Reported at lint and refused at boot from the same predicate.
     */
    public static final TqlErrorCode UNSUPPORTED = new TqlErrorCode(TqlDomain.YAML, 1064);

    /**
     * TQL-SQL-2503: a column's value could not be parsed into the kind its {@code result:}
     * entry declares — text that is not JSON, not a date in the declared format, not a number
     * in the declared pattern. Names the source, the column, the row and the kind; the text
     * never passes through silently, because a consumer's {@code payload.sku} would then be
     * null with no signal.
     */
    public static final TqlErrorCode UNPARSEABLE = new TqlErrorCode(TqlDomain.SQL, 2503);

    /** The kinds a {@code result:} entry parses a column's text into. */
    public static final Set<String> RESULT_KINDS = Set.of("json", "date", "datetime", "number");

    /** The types a request binds; {@code json}, the read-only kind, is deliberately not one. */
    public static final Set<String> INPUT_TYPES = Set.of("string", "integer", "number", "boolean",
            "date", "datetime", "array", "sort");

    /** The kind whose declaration reads no {@code format:}. */
    private static final String JSON = "json";

    /** The mode of a binding that publishes rows a declaration can apply to. */
    private static final Set<String> ROW_MODES = Set.of("query");

    private DeclaredKinds() {
    }

    /** One finding; {@code key} is the declaration's path ({@code sources.main.result.payload.type}). */
    public record Violation(String key, String message) {
    }

    /**
     * Every {@code input:} field whose {@code type:} no request binds. A {@code json} domain
     * is legal — it is what a {@code result:} entry reads — but a route that binds it as an
     * input has declared a value the binder would pass through as its raw text.
     */
    public static List<Violation> inputViolations(RouteDefinition route) {
        List<Violation> out = new ArrayList<>();
        route.input().forEach((name, field) -> inputField(route, "input." + name, field, out));
        return out;
    }

    private static void inputField(RouteDefinition route, String key, InputField field,
            List<Violation> out) {
        if (field.type() != null && !INPUT_TYPES.contains(field.type())) {
            out.add(new Violation(key + ".type", prefix(route, key + ".type") + "'"
                    + ExportDeclarations.bounded(field.type()) + "' is not a type a request"
                    + " binds (" + sorted(INPUT_TYPES) + ")"
                    + (JSON.equals(field.type())
                            ? " - json is a kind a result: declaration parses, not one an"
                                    + " input binds"
                            : "")));
        }
        // A domain's locale is never merged into an input, so one here was written here.
        if (field.locale() != null) {
            out.add(new Violation(key + ".locale", prefix(route, key + ".locale")
                    + "locale: is not applied on an input, which parses in the request's own"
                    + " locale - declare it on the result: entry or the domain that reads the"
                    + " column back"));
        }
        if (field.items() != null && field.items().hasFields()) {
            field.items().fields().forEach((element, spec) -> inputField(route,
                    key + ".items.fields." + element, spec, out));
        }
    }

    /**
     * Every {@code result:} entry of the route's {@code sources:} and {@code steps:} the
     * runtime could not honour: a kind outside {@link #RESULT_KINDS}, a {@code format:} the
     * kind's parser refuses (or one on {@code json}, which reads none), or a declaration on a
     * binding whose mode publishes no rows.
     */
    public static List<Violation> resultViolations(RouteDefinition route) {
        List<Violation> out = new ArrayList<>();
        route.sources().forEach((name, binding) -> binding(route, "sources." + name, binding,
                out));
        route.steps().forEach((name, binding) -> binding(route, "steps." + name, binding, out));
        return out;
    }

    private static void binding(RouteDefinition route, String key, Binding binding,
            List<Violation> out) {
        if (binding.result().isEmpty()) {
            return;
        }
        if (!publishesRows(binding)) {
            out.add(new Violation(key + ".result", prefix(route, key + ".result")
                    + "a result: declaration applies to the rows a binding publishes, and this"
                    + " one publishes none (mode: " + ExportDeclarations.bounded(
                            binding.effectiveMode())
                    + ")"));
            return;
        }
        binding.result().forEach((column, field) -> entry(route, key + ".result." + column,
                field, out));
    }

    /** Whether the binding's rows land in the context, where a declaration can reach them. */
    private static boolean publishesRows(Binding binding) {
        if (binding.isSequence() || binding.isSpool()) {
            return false;
        }
        return ROW_MODES.contains(binding.effectiveMode());
    }

    private static void entry(RouteDefinition route, String key, InputField field,
            List<Violation> out) {
        for (String written : unreadKeys(field)) {
            out.add(new Violation(key + "." + written, prefix(route, key + "." + written)
                    + "'" + written + "' is not a key a result: declaration reads (" + READ_KEYS
                    + ") - a read says what the column's text is, not what it may be"));
        }
        String type = field.type();
        if (type == null || !RESULT_KINDS.contains(type)) {
            out.add(new Violation(key + ".type", prefix(route, key + ".type") + "'"
                    + ExportDeclarations.bounded(type) + "' is not a kind a result: declaration"
                    + " parses (" + sorted(RESULT_KINDS) + ")"));
            return;
        }
        if (field.locale() != null) {
            ExportDeclarations.localeProblem(field.locale()).ifPresent(problem -> out.add(
                    new Violation(key + ".locale", prefix(route, key + ".locale") + problem)));
        }
        if (field.format() == null) {
            return;
        }
        if (JSON.equals(type)) {
            out.add(new Violation(key + ".format", prefix(route, key + ".format")
                    + "a json declaration reads no format: - the text is parsed as JSON"));
            return;
        }
        String problem = ExportDeclarations.patternProblem(type, field.format());
        if (problem != null) {
            out.add(new Violation(key + ".format", prefix(route, key + ".format") + "'"
                    + ExportDeclarations.bounded(field.format()) + "' " + problem));
        }
    }

    /** The keys a {@code result:} entry reads, in the words the refusal lists them. */
    private static final String READ_KEYS = "type, format, locale, domain, description";

    /**
     * The keys written on a {@code result:} entry that no read applies. Exact, because the
     * loader merges a domain into an entry by the read keys alone ({@code mergedForRead}): a
     * constraint or operational key on the merged entry was written on the entry.
     */
    private static List<String> unreadKeys(InputField field) {
        List<String> written = new ArrayList<>();
        if (field.required()) {
            written.add("required");
        }
        if (field.requiredWhen() != null) {
            written.add("requiredWhen");
        }
        if (field.defaultValue() != null) {
            written.add("default");
        }
        if (field.writable() != null) {
            written.add("writable");
        }
        if (field.policy() != null) {
            written.add("policy");
        }
        if (field.min() != null) {
            written.add("min");
        }
        if (field.max() != null) {
            written.add("max");
        }
        if (field.minLength() != null) {
            written.add("minLength");
        }
        if (field.maxLength() != null) {
            written.add("maxLength");
        }
        if (field.pattern() != null) {
            written.add("pattern");
        }
        if (field.enumValues() != null) {
            written.add("enum");
        }
        if (field.items() != null) {
            written.add("items");
        }
        if (field.columns() != null) {
            written.add("columns");
        }
        if (field.classification() != null) {
            written.add("classification");
        }
        if (field.mask() != null) {
            written.add("mask");
        }
        if (field.widget() != null) {
            written.add("widget");
        }
        if (field.codes() != null) {
            written.add("codes");
        }
        if (field.lookup() != null) {
            written.add("lookup");
        }
        return written;
    }

    /** The boot backstop: the first violation as the refusal, every one first through {@code warn}. */
    public static void require(List<Violation> violations, Consumer<String> warn) {
        for (Violation violation : violations) {
            warn.accept(violation.message());
        }
        if (!violations.isEmpty()) {
            throw new TqlException(UNSUPPORTED, violations.get(0).message());
        }
    }

    private static String prefix(RouteDefinition route, String key) {
        return "route '" + ExportDeclarations.bounded(route.id()) + "' " + key + ": ";
    }

    private static String sorted(Set<String> names) {
        return String.join(", ", new java.util.TreeSet<>(names));
    }

    // ---- the read ----------------------------------------------------------------------------

    /**
     * The column's value in its declared kind, as the bindable paths carry that kind: a JSON
     * value ({@link JsonValues}), a date or a wall clock as its canonical wire text (what a
     * native column of that kind is after the read seam — never a {@code java.time} object,
     * which the JSON mapper has no module for), a number as a {@link java.math.BigDecimal}.
     * Null stays null. A value that is not text is already in a kind: a number stays a
     * number, and a JSON container an outbound call produced is re-wrapped so its text is JSON.
     * Text that is not in the declared format is tried as the canonical form, since a native
     * column declared for the sake of its domain arrives that way already. A pattern parses in
     * the entry's {@code locale:}, else the root locale.
     *
     * @throws ColumnValueException when the text cannot be parsed into the kind, its
     *                              {@code complaint()} saying why
     */
    public static Object read(String column, InputField declared, Object value) {
        if (value == null) {
            return null;
        }
        String type = declared.type();
        if (JSON.equals(type)) {
            if (!(value instanceof String text)) {
                return JsonValues.wrap(value);
            }
            try {
                return JsonValues.parse(text);
            } catch (IllegalArgumentException ex) {
                throw new ColumnValueException(column, text, "is not JSON: " + ex.getMessage());
            }
        }
        if (!(value instanceof String text)) {
            return value;
        }
        ColumnMapping mapping = new ColumnMapping(column, null, null, type, declared.format());
        // The column's own locale when the entry declares one (decision 23); the root locale -
        // 1,234.50, English month names - otherwise, never the platform's or the request's.
        Locale locale = declared.locale() == null
                ? Locale.ROOT
                : Locale.forLanguageTag(declared.locale());
        try {
            Object parsed = ColumnValues.parse(mapping, text, locale);
            return parsed instanceof Number ? parsed : TemporalText.wire(parsed);
        } catch (ColumnValueException notInTheDeclaredFormat) {
            String canonical = canonical(type, text);
            if (canonical == null) {
                throw notInTheDeclaredFormat;
            }
            return canonical;
        }
    }

    /** The text if it already is the kind's wire form, else null. */
    private static String canonical(String type, String text) {
        try {
            return switch (type) {
                case "date" -> TemporalText.wire(LocalDate.parse(text.trim()));
                case "datetime" -> TemporalText.wire(LocalDateTime.parse(text.trim()));
                default -> null;
            };
        } catch (DateTimeParseException notCanonical) {
            return null;
        }
    }

    /**
     * The one message a read failure carries, then thrown as {@link #UNPARSEABLE}:
     * {@code source} is the binding ({@code main}, {@code steps.header}), {@code row} is
     * 0-based.
     */
    public static TqlException unparseable(String source, String column, int row, String kind,
            ColumnValueException cause) {
        return TqlException.builder(UNPARSEABLE)
                .message("Source '" + source + "' column '" + column + "' row index " + row
                        + ": the value '" + ExportDeclarations.bounded(cause.value())
                        + "' " + cause.complaint() + " - declared " + kind)
                .details(Map.of("source", source, "column", column, "row", row,
                        "kind", kind))
                .cause(cause)
                .build();
    }
}
