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

    private ViewFields() {
    }

    /** A form field ready to render: the derived input constraints plus presentation. */
    public record FieldDef(String name, String labelKey, String labelFallback, String widget,
            boolean required, Integer maxLength, Integer min, Integer max, List<String> options) {
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

    private static FieldDef fieldDef(ViewSpec spec, String name, InputField input,
            ViewSpec.Field override) {
        String widget = override == null || override.widget() == null
                ? defaultWidget(input)
                : override.widget();
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
                input.maxLength(), input.min(), input.max(), options);
    }

    /** The widget an input renders as when the view does not say otherwise. */
    private static String defaultWidget(InputField input) {
        if (input.enumValues() != null && !input.enumValues().isEmpty()) {
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
