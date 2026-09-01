package io.tesseraql.yaml.view;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.model.InputField;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Derives a form view's renderable fields from its action route's {@code input:} block
 * (docs/declarative-views.md): every writable input in declared order, or — when the view's
 * {@code fields:} list is present — that selection and order, each entry merged over its derived
 * definition. Shared by the render-time binding and the eject generator so both agree on the
 * widget/constraint semantics.
 */
public final class ViewFields {

    /** TQL-VIEW-3304: a fields: entry names an input the action route does not declare. */
    public static final TqlErrorCode UNKNOWN_FIELD = new TqlErrorCode(TqlDomain.VIEW, 3304);
    /** TQL-VIEW-3305: unknown widget name. */
    public static final TqlErrorCode UNKNOWN_WIDGET = new TqlErrorCode(TqlDomain.VIEW, 3305);
    /** TQL-VIEW-3323: a filters: entry names an input the route does not declare. */
    public static final TqlErrorCode UNKNOWN_FILTER = new TqlErrorCode(TqlDomain.VIEW, 3323);

    private ViewFields() {
    }

    /** A form field ready to render: the derived input constraints plus presentation. */
    public record FieldDef(String name, String labelKey, String labelFallback, String widget,
            boolean required, Integer maxLength, java.math.BigDecimal min,
            java.math.BigDecimal max, List<String> options, String codes,
            String column, String step, String policy, InputField.LookupSpec lookup) {

        /** The pre-{@code lookup:} shape every earlier caller constructs. */
        public FieldDef(String name, String labelKey, String labelFallback, String widget,
                boolean required, Integer maxLength, java.math.BigDecimal min,
                java.math.BigDecimal max, List<String> options, String codes,
                String column, String step, String policy) {
            this(name, labelKey, labelFallback, widget, required, maxLength, min, max,
                    options, codes, column, step, policy, null);
        }

        /**
         * The result-set column the prefill reads: explicit, else the input name — which under
         * the verbatim policy (docs/unicode-identifiers.md) <em>is</em> the column name; the
         * camel-to-snake guessing bridge is gone.
         */
        public Object valueFrom(Map<String, Object> row) {
            return row.get(column != null ? column : name);
        }
    }

    /** Derives the field definitions for a form view (see class doc). */
    public static List<FieldDef> derive(String viewRef, ViewSpec spec,
            Map<String, InputField> inputs) {
        List<FieldDef> defs = new ArrayList<>();
        if (spec.fields().isEmpty()) {
            inputs.forEach((name, input) -> {
                if (input.isWritable()) {
                    defs.add(fieldDef(spec, name, input, null));
                }
            });
            return List.copyOf(defs);
        }
        for (ViewSpec.Field override : spec.fields()) {
            InputField input = inputs.get(override.name());
            if (input == null) {
                throw new TqlException(UNKNOWN_FIELD, "View " + viewRef + ": field "
                        + override.name() + " is not declared by the action route's input: block");
            }
            defs.add(fieldDef(spec, override.name(), input, override));
        }
        return List.copyOf(defs);
    }

    /**
     * Derives the dialog fields for a grid page's {@code filters:} (docs/list-surface.md
     * decision 6): the same widget/constraint semantics a form field gets, except nothing is
     * required — a required <em>input</em> is not a required <em>filter</em>, and an empty
     * dialog field simply applies no condition.
     */
    public static List<FieldDef> deriveFilters(String viewRef, ViewSpec spec,
            Map<String, InputField> inputs) {
        List<FieldDef> defs = new ArrayList<>();
        for (ViewSpec.Filter filter : spec.filters()) {
            InputField input = inputs == null ? null : inputs.get(filter.name());
            if (input == null) {
                throw new TqlException(UNKNOWN_FILTER, "View " + viewRef + ": filter "
                        + filter.name() + " is not a declared input of the route");
            }
            FieldDef derived = fieldDef(spec, filter.name(), input, null);
            defs.add(new FieldDef(derived.name(),
                    filter.label() != null ? filter.label() : derived.labelKey(),
                    filter.label() != null ? filter.label() : derived.labelFallback(),
                    // A boolean filter is three-valued (any/yes/no) — a checkbox cannot say
                    // "any", so it renders as a select like every fixed value set. A lookup
                    // filters as plain text: the dialog condition is the route's own SQL arm,
                    // and the resolve machinery belongs to the form field, not the filter.
                    "checkbox".equals(derived.widget())
                            ? "select"
                            : "lookup".equals(derived.widget()) ? "text" : derived.widget(),
                    false, derived.maxLength(), derived.min(), derived.max(),
                    "checkbox".equals(derived.widget())
                            ? List.of("true", "false")
                            : derived.options(),
                    derived.codes(), null, derived.step(), null, null));
        }
        return List.copyOf(defs);
    }

    private static FieldDef fieldDef(ViewSpec spec, String name, InputField input,
            ViewSpec.Field override) {
        // Widget precedence (docs/view-composition.md wave 3a): the per-view fields: override,
        // else the field's own declared widget (usually merged in from its domain — "an SKU is
        // a code input", said once), else the type-derived default.
        String widget = override != null && override.widget() != null
                ? override.widget()
                : input.widget() != null ? input.widget() : defaultWidget(input);
        if (!ViewSpec.WIDGETS.contains(widget)) {
            throw new TqlException(UNKNOWN_WIDGET, "View " + spec.id() + ": unknown widget "
                    + widget + " on field " + name + " (known: " + ViewSpec.WIDGETS + ")");
        }
        String labelKey = override != null && override.label() != null
                ? override.label()
                : "view." + spec.id() + "." + name;
        String fallback = override != null && override.label() != null
                ? override.label()
                : humanize(name);
        List<String> options = input.enumValues() == null
                ? List.of()
                : List.copyOf(input.enumValues());
        return new FieldDef(name, labelKey, fallback, widget, input.required(),
                input.maxLength(), input.min(), input.max(), options, input.codes(),
                override == null ? null : override.column(),
                "number".equals(input.type()) ? "any" : null, input.policy(), input.lookup());
    }

    /**
     * One field's derived definition — how a lookup field's synthesized resolve route
     * (docs/reference-lookup.md decision 2) renders the same fragment the form renders,
     * honoring the view's own {@code fields:} override for the field when it has one.
     */
    public static FieldDef deriveField(ViewSpec spec, String name, InputField input) {
        ViewSpec.Field override = spec.fields().stream()
                .filter(candidate -> name.equals(candidate.name()))
                .findFirst().orElse(null);
        return fieldDef(spec, name, input, override);
    }

    /** The widget an input renders as when the view does not say otherwise. */
    private static String defaultWidget(InputField input) {
        // A master reference renders as the lookup field (docs/reference-lookup.md): code
        // entry plus hidden id — a select cannot carry a business master's row count.
        if (input.lookup() != null) {
            return "lookup";
        }
        // A fixed value set renders as a select whether the set is declared (enum:) or held in
        // a code catalog (codes:) — the source differs, the control does not.
        if (input.enumValues() != null && !input.enumValues().isEmpty()) {
            return "select";
        }
        if (input.codes() != null && !input.codes().isBlank()) {
            return "select";
        }
        String type = input.type() == null ? "string" : input.type();
        return switch (type) {
            case "boolean" -> "checkbox";
            case "integer", "number" -> "number";
            case "date" -> "date";
            case "datetime" -> "datetime-local";
            default -> "text";
        };
    }

    /** {@code login_id} / {@code unitPrice} &rarr; {@code Login id} / {@code Unit price}. */
    public static String humanize(String name) {
        String spaced = name.replaceAll("[_\\-]+", " ")
                .replaceAll("([a-z0-9])([A-Z])", "$1 $2").trim().toLowerCase(Locale.ROOT);
        return spaced.isEmpty()
                ? name
                : Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }
}
