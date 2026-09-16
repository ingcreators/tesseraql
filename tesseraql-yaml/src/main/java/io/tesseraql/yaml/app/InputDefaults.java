package io.tesseraql.yaml.app;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.yaml.app.ExportDeclarations.Kind;
import io.tesseraql.yaml.app.ExportDeclarations.Violation;
import io.tesseraql.yaml.model.InputField;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A declared input's {@code default:}, held to the input's own rules (docs/audit-low-leads.md
 * XD-07b): the literal is the value the binder hands every request that omits the field, so it
 * is judged as a caller's value would be — parsed into the declared type, then held to the
 * constraints — once, where the declaration is read, instead of on the request that first
 * reaches the statement it is bound into.
 *
 * <p>The default used to be judged by nobody: {@code type: number, default: abc} linted clean,
 * booted, and answered 200 to a request that omitted the input (400 to one that sent the same
 * text) until the text met a numeric compare and became a 500 on every omission; an
 * {@code enum} default outside its enum reached the sort fragment authors are told to rely on.
 * Reported at lint and refused at boot from this one predicate, with {@code TQL-YAML-1072}, and
 * the binder binds what {@link #typed} parsed rather than the raw literal.
 *
 * <p>The author's literal parses in the root locale — it is written once, not negotiated per
 * request — and a {@code codes:} catalog is not judged here (it needs the store the binder has).
 */
public final class InputDefaults {

    /**
     * TQL-YAML-1072: a declared input's {@code default:} is not a value the input accepts — it
     * does not parse as the declared type, or the parsed value breaks a constraint the field
     * declares ({@code enum}, {@code min}/{@code max}, a length, {@code pattern}, {@code format},
     * the {@code sort} column set) — or it sits on an {@code array} input, which never binds a
     * default. Reported at lint and refused at boot from the same predicate.
     */
    public static final TqlErrorCode UNACCEPTABLE = new TqlErrorCode(TqlDomain.YAML, 1072);

    private InputDefaults() {
    }

    /**
     * Every default the document's {@code input:} block (top level and {@code items.fields:})
     * refuses; {@code subject} is {@code route 'x'} or {@code job 'y'}, the words the message
     * opens with.
     */
    public static List<Violation> violations(String app, String subject,
            Map<String, InputField> inputs) {
        List<Violation> out = new ArrayList<>();
        if (inputs == null) {
            return out;
        }
        inputs.forEach((name, field) -> field(app, subject, "input." + name, name, field, out));
        return out;
    }

    private static void field(String app, String subject, String key, String name,
            InputField field, List<Violation> out) {
        if (field.items() != null && field.items().hasFields()) {
            field.items().fields().forEach((element, spec) -> field(app, subject,
                    key + ".items.fields." + element, name + "[]." + element, spec, out));
        }
        if (field.defaultValue() == null) {
            return;
        }
        String at = key + ".default";
        String head = "app '" + ExportDeclarations.bounded(app) + "': " + subject + " " + at
                + ": ";
        if ("array".equals(field.type())) {
            out.add(new Violation(UNACCEPTABLE, Kind.INVALID, at, head + "an array input binds"
                    + " no default: - a request that omits it binds nothing"));
            return;
        }
        try {
            typed(name, field);
        } catch (InputValues.Refusal refusal) {
            out.add(new Violation(UNACCEPTABLE, Kind.INVALID, at, head + "'"
                    + ExportDeclarations.bounded(String.valueOf(field.defaultValue()))
                    + "' is not a value this input accepts - " + refusal.getMessage()));
        }
    }

    /**
     * The value a request that omits the field binds: the default's text parsed into the
     * declared type and held to the constraints, exactly as a caller's text is — the binder
     * used to put the literal in raw, so a quoted {@code "5"} on an integer input reached the
     * statement as text. Throws the {@link InputValues.Refusal} the predicate reports.
     */
    public static Object typed(String name, InputField field) {
        Object literal = field.defaultValue();
        if (literal == null) {
            return null;
        }
        return InputValues.constrain(name, field,
                InputValues.coerce(name, field, String.valueOf(literal), Locale.ROOT));
    }
}
