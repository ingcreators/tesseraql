package io.tesseraql.compiler.binding;

import io.tesseraql.yaml.view.ViewFields;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code f} model a lookup field renders from (docs/reference-lookup.md decision 2) — one
 * builder for the form's first paint ({@link ViewBinding}) and the synthesized resolve route's
 * re-render ({@link LookupResolveProcessor}), so the two can never drift on the fragment
 * contract: code, hidden id, and hint move together or not at all.
 */
final class LookupFieldModel {

    private LookupFieldModel() {
    }

    /**
     * The first paint's extras: no code and no hint yet — a prefilled id (an edit form)
     * self-resolves on load through the same fragment, keyed by id.
     */
    static Map<String, Object> initial(ViewFields.FieldDef field, String resolve,
            String idValue) {
        return state(field, resolve, "", "", false, "",
                idValue != null && !idValue.isEmpty());
    }

    /** One render state of the lookup extras ({@code f.lookup} in {@code field.html}). */
    static Map<String, Object> state(ViewFields.FieldDef field, String resolve, String code,
            String hint, boolean invalid, String message, boolean load) {
        Map<String, Object> lookup = new LinkedHashMap<>();
        lookup.put("param", field.lookup().code());
        lookup.put("resolve", resolve);
        lookup.put("code", code);
        lookup.put("hint", hint);
        lookup.put("invalid", invalid);
        lookup.put("message", message);
        lookup.put("load", load);
        return lookup;
    }

    /**
     * The whole {@code f} map for a standalone fragment render — the shape
     * {@code ViewBinding.formModel} builds per field, reproduced for the one field the resolve
     * route re-renders.
     */
    static Map<String, Object> fragment(ViewFields.FieldDef field, String label, String value,
            Map<String, Object> lookup) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("name", field.name());
        f.put("label", label);
        f.put("widget", field.widget());
        f.put("required", field.required());
        f.put("maxLength", field.maxLength());
        f.put("min", field.min());
        f.put("max", field.max());
        f.put("options", List.of());
        f.put("step", field.step());
        f.put("value", value == null ? "" : value);
        f.put("lookup", lookup);
        return f;
    }
}
