package io.tesseraql.studio;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.studio.StudioService.FormInput;
import io.tesseraql.studio.StudioService.RouteForm;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A route document's security and input declarations, as a form (Studio Track J1).
 *
 * <p>The text editor stays the escape hatch: the form writes the keys it manages back through the
 * document tree, so unknown keys and unmanaged field attributes survive, and the result must still
 * parse as a route before it is saved as a draft.
 */
final class RouteForms {

    private static final TqlErrorCode ROUTE_FORM = new TqlErrorCode(TqlDomain.STUDIO, 4230);

    private final Declarations declarations;

    RouteForms(Declarations declarations) {
        this.declarations = declarations;
    }

    /** Loads the structured form model for a route document (Track J1). */
    RouteForm routeForm(String relativePath) {
        requireRouteDoc(relativePath);
        Declarations.Read read = declarations.readRequired(relativePath);
        try {
            Map<String, Object> tree = declarations.parser().parseTree(read.text());
            Map<String, Object> security = StudioService.anyMap(tree.get("security"));
            List<FormInput> inputs = new ArrayList<>();
            StudioService.anyMap(tree.get("input")).forEach((name, spec) -> {
                Map<String, Object> field = StudioService.anyMap(spec);
                inputs.add(
                        new FormInput(String.valueOf(name), StudioService.scalar(field.get("type")),
                                Boolean.TRUE.equals(field.get("required")),
                                StudioService.scalar(field.get("min")),
                                StudioService.scalar(field.get("max")),
                                StudioService.scalar(field.get("maxLength")),
                                StudioService.scalar(field.get("minLength")),
                                StudioService.scalar(field.get("pattern")),
                                StudioService.csvOf(field.get("enum")),
                                StudioService.scalar(field.get("domain"))));
            });
            return new RouteForm(relativePath, StudioService.scalar(tree.get("id")),
                    StudioService.scalar(tree.get("recipe")),
                    StudioService.scalar(security.get("auth")),
                    StudioService.scalar(security.get("policy")),
                    StudioService.scalar(security.get("csrf")), inputs, read.fromDraft(), null);
        } catch (RuntimeException ex) {
            return new RouteForm(relativePath, null, null, null, null, null, List.of(),
                    read.fromDraft(), StudioService.rootMessage(ex));
        }
    }

    /**
     * Applies the structured form onto the route document and saves the result as a draft
     * (Track J1) — the text editor stays the escape hatch, and apply/reload stays the existing
     * draft flow. The document is re-serialized canonically: unknown keys and unmanaged field
     * attributes are preserved through the tree, but comments and hand formatting are not.
     * The mutated document must still parse as a route, or the save is rejected.
     */
    Path routeFormSave(String relativePath, String recipe, String auth, String policy,
            String csrf, List<FormInput> inputs) {
        if (declarations.readOnly()) {
            throw new TqlException(StudioService.READ_ONLY,
                    "Studio is read-only; editing routes is disabled");
        }
        requireRouteDoc(relativePath);
        String text = declarations.readRequired(relativePath).text();
        Map<String, Object> tree;
        try {
            tree = declarations.parser().parseTree(text);
        } catch (RuntimeException ex) {
            throw new TqlException(ROUTE_FORM, "The document does not parse as YAML; fix it in "
                    + "the text editor first: " + StudioService.rootMessage(ex));
        }
        if (recipe != null && !recipe.isBlank()) {
            tree.put("recipe", recipe.trim());
        }
        Map<String, Object> security = StudioService.childMap(tree, "security");
        StudioService.putOrRemove(security, "auth", StudioService.trimToNull(auth));
        StudioService.putOrRemove(security, "policy", StudioService.trimToNull(policy));
        // The csrf enum (auto|required|off); blank clears the key so defaults rules apply.
        StudioService.putOrRemove(security, "csrf", StudioService.trimToNull(csrf));
        if (security.isEmpty()) {
            tree.remove("security");
        }
        Map<String, Object> existing = StudioService.anyMap(tree.get("input"));
        Map<String, Object> rebuilt = new LinkedHashMap<>();
        for (FormInput row : inputs) {
            String name = StudioService.trimToNull(row.name());
            if (name == null) {
                continue;
            }
            // Surviving fields keep their unmanaged attributes (writable, mask, format, ...).
            Map<String, Object> field = new LinkedHashMap<>(
                    StudioService.anyMap(existing.get(name)));
            // A field domain reference (docs/field-domains.md) leads the entry; the row's own
            // keys then tighten it (route-local always wins at manifest load).
            StudioService.putOrRemove(field, "domain", StudioService.trimToNull(row.domain()));
            StudioService.putOrRemove(field, "type", StudioService.trimToNull(row.type()));
            if (row.required()) {
                field.put("required", true);
            } else {
                field.remove("required");
            }
            StudioService.putOrRemove(field, "min", decimalOrNull(name, "min", row.min()));
            StudioService.putOrRemove(field, "max", decimalOrNull(name, "max", row.max()));
            StudioService.putOrRemove(field, "maxLength",
                    integerOrNull(name, "maxLength", row.maxLength()));
            StudioService.putOrRemove(field, "minLength",
                    integerOrNull(name, "minLength", row.minLength()));
            StudioService.putOrRemove(field, "pattern", StudioService.trimToNull(row.pattern()));
            List<String> options = StudioService.csv(row.enumCsv());
            StudioService.putOrRemove(field, "enum", options.isEmpty() ? null : options);
            rebuilt.put(name, field);
        }
        if (rebuilt.isEmpty()) {
            tree.remove("input");
        } else {
            tree.put("input", rebuilt);
        }
        String yaml = declarations.parser().write(tree);
        try {
            declarations.parser().parseRoute(yaml, relativePath);
        } catch (RuntimeException ex) {
            throw new TqlException(ROUTE_FORM,
                    "The change no longer parses as a route document: "
                            + StudioService.rootMessage(ex));
        }
        return declarations.saveDraft(relativePath, yaml);
    }

    private void requireRouteDoc(String path) {
        if (!StudioService.isRouteYaml(path)) {
            throw new TqlException(ROUTE_FORM,
                    "The form editor edits web/**/<method>.yml route documents only: " + path);
        }
    }

    private static java.math.BigDecimal decimalOrNull(String field, String key, String raw) {
        String value = StudioService.trimToNull(raw);
        if (value == null) {
            return null;
        }
        try {
            return new java.math.BigDecimal(value);
        } catch (NumberFormatException ex) {
            throw new TqlException(ROUTE_FORM, "Input " + field + " " + key
                    + " must be a number: " + value);
        }
    }

    private static Integer integerOrNull(String field, String key, String raw) {
        String value = StudioService.trimToNull(raw);
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException ex) {
            throw new TqlException(ROUTE_FORM, "Input " + field + " " + key
                    + " must be an integer: " + value);
        }
    }
}
