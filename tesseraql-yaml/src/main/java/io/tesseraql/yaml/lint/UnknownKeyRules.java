package io.tesseraql.yaml.lint;

import io.tesseraql.yaml.model.AcceptedKeys;
import io.tesseraql.yaml.model.ExportSpec;
import io.tesseraql.yaml.model.ImportSpec;
import io.tesseraql.yaml.model.JobDefinition;
import io.tesseraql.yaml.model.RouteDefinition;
import io.tesseraql.yaml.model.WorkflowDefinition;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Unknown and renamed keys, at every depth of a document (TQL-YAML-1043/1044) — reached from
 * every document family, so it is a shared rule rather than a registry entry.
 *
 * <p>The walk recurses into whatever the model says is a shape (docs/lint-restructure.md
 * decision 3). It used to check the document's own keys plus the blocks someone had registered
 * in a map, which meant the interiors of {@code security:}, {@code response:}, {@code consume:},
 * {@code publish:} and {@code webhook:} stayed silently tolerant until a campaign noticed them.
 * A registration map can only ever describe the blocks somebody remembered; recursion covers a
 * nested block the day its record lands.
 */
final class UnknownKeyRules {

    private UnknownKeyRules() {
    }

    /**
     * Top-level keys renamed before v1 (docs/vocabulary-cleanup.md), per document-family root
     * record. The old spelling deserializes away silently, dropping the whole block — so it is a
     * hard error naming the replacement (TQL-YAML-1044), not a generic unknown-key warning. Only
     * unambiguously top-level renames live here; {@code notify:} is current on routes and renamed
     * only on workflows, and {@code params:} is a live key inside every binding, so the map is
     * keyed by the record it applies to.
     */
    static final Map<Class<?>, Map<String, String>> RENAMED_KEYS = Map.of(
            RouteDefinition.class, Map.of("page", "pagination", "policy", "admission",
                    "params", "input"),
            JobDefinition.class, Map.of("params", "input"),
            WorkflowDefinition.class, Map.of("notify", "reminders"));

    /**
     * Keys the unified source model moved out of a block, per block record
     * (docs/unified-sources.md decision 7). Like the renames these deserialize away in silence,
     * so they are an error naming where the key went rather than a generic unknown-key warning —
     * an {@code export.sql:} produced an export that wrote nothing at all.
     */
    static final Map<Class<?>, Map<String, String>> MOVED_KEYS = Map.of(
            ExportSpec.class, Map.of("sql", "sources", "queries", "sources", "http", "sources"),
            ImportSpec.class, Map.of("sql", "steps"));

    /**
     * The name key of an entry authored as a sequence item. An ordered collection is an array
     * whose items carry {@code id:} (docs/unified-sources.md decision 9), and the id is the
     * entry's name rather than one of the shape's own keys — a command step is authored as
     * {@code - id: header} plus a {@link io.tesseraql.yaml.model.Binding}, which knows nothing
     * of ids.
     */
    private static final Set<String> ENTRY_NAME = Set.of("id");

    /**
     * Flags unknown keys on a document and everything nested inside it (TQL-YAML-1043, warning)
     * and renamed ones (TQL-YAML-1044, error). The model records are
     * {@code @JsonIgnoreProperties(ignoreUnknown)}, so without this a typo'd {@code securty:}
     * block drops auth with no diagnostic. {@code extraKeys} carries document keys a loader reads
     * from the raw tree rather than the record (e.g. mcp {@code description}/{@code uri}).
     */
    static void lintUnknownKeys(LintContext context, Path appHome, Path file, Class<?> recordClass,
            Set<String> extraKeys, List<LintFinding> findings) {
        // A malformed document already failed the manifest load before lint ran; skip it.
        Map<String, Object> tree = context.tree(file);
        if (tree == null) {
            return;
        }
        lintShape(tree, recordClass, "", extraKeys, LintSupport.relative(appHome, file), findings);
    }

    /**
     * One shape's declared keys, and a walk into each value that is itself a shape.
     *
     * <p>{@code prefix} is what a key of this shape is called in a diagnostic, terminator
     * included, so a nested key reads as the path an author would follow to it.
     */
    private static void lintShape(Map<?, ?> declared, Class<?> shape, String prefix,
            Set<String> extraKeys, String source, List<LintFinding> findings) {
        Map<String, Type> properties = AcceptedKeys.properties(shape);
        Set<String> accepted = AcceptedKeys.of(shape);
        Map<String, String> renamed = RENAMED_KEYS.getOrDefault(shape, Map.of());
        Map<String, String> moved = MOVED_KEYS.getOrDefault(shape, Map.of());
        for (Map.Entry<?, ?> entry : declared.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Type type = properties.get(key);
            if (type == null) {
                if (!extraKeys.contains(key)) {
                    reportUnknownKey(prefix + key, renamed.get(key), moved.get(key), accepted,
                            source, findings);
                }
                continue;
            }
            descend(entry.getValue(), type, prefix, key, source, findings);
        }
    }

    /**
     * Walks a declared value when the model says it is a shape, and stops when it does not.
     *
     * <p>Three things are a shape: a record, the elements of a collection of records, and the
     * values of a map of records — the author names the keys of that map, so the names are not
     * checked but what hangs under each one is. Everything else stops the walk: a scalar, a
     * {@code Map<String, String>} of binds or headers, a {@code List<String>} of topics, an
     * untyped tree. So does a value whose YAML shape is not the declared one — a string where a
     * record's shorthand form is written, a list where a map was declared: the loader's own
     * error to report, not a pile of unknown keys on top of it.
     */
    private static void descend(Object value, Type type, String prefix, String key, String source,
            List<LintFinding> findings) {
        Class<?> shape = recordOf(type);
        if (shape != null) {
            if (value instanceof Map<?, ?> nested) {
                lintShape(nested, shape, prefix + key + ".", Set.of(), source, findings);
            }
            return;
        }
        Class<?> entryShape = entryRecordOf(type);
        if (entryShape == null) {
            return;
        }
        // An ordered collection is authored as a sequence of id-carrying items, whichever of the
        // two the model holds it as: a job's pipeline is a list of steps, a command's steps: a
        // map keyed by the id each item carries. The item is named for what it is rather than for
        // the collection holding it — "step 'report'", not "pipeline #0" — because that is how an
        // author would say which one.
        if (value instanceof List<?> items) {
            int index = 0;
            for (Object item : items) {
                if (item instanceof Map<?, ?> element) {
                    lintShape(element, entryShape,
                            prefix + entryName(entryShape, element, index) + " ", ENTRY_NAME,
                            source, findings);
                }
                index++;
            }
            return;
        }
        if (value instanceof Map<?, ?> entries) {
            for (Map.Entry<?, ?> entry : entries.entrySet()) {
                if (entry.getValue() instanceof Map<?, ?> element) {
                    lintShape(element, entryShape, prefix + key + "." + entry.getKey() + ".",
                            Set.of(), source, findings);
                }
            }
        }
    }

    /** The record a value of this type is, or null when the type is not one. */
    private static Class<?> recordOf(Type type) {
        return type instanceof Class<?> cls && cls.isRecord() ? cls : null;
    }

    /**
     * The record an entry of this container is, or null when the type holds no records — a
     * {@code Map<String, String>} of binds is a container of values, not of shapes.
     */
    private static Class<?> entryRecordOf(Type type) {
        if (!(type instanceof ParameterizedType parameterized)
                || !(parameterized.getRawType() instanceof Class<?> raw)) {
            return null;
        }
        Type[] arguments = parameterized.getActualTypeArguments();
        if (Map.class.isAssignableFrom(raw) && arguments.length == 2) {
            return recordOf(arguments[1]);
        }
        if (Collection.class.isAssignableFrom(raw) && arguments.length == 1) {
            return recordOf(arguments[0]);
        }
        return null;
    }

    /**
     * What one sequence item is called in a diagnostic: the noun its shape is named after plus
     * the {@code id:} it carries — {@code step 'report'} — so a finding names the item an author
     * can find rather than the position it happens to sit at.
     */
    private static String entryName(Class<?> shape, Map<?, ?> element, int index) {
        Object id = element.get("id");
        String noun = noun(shape);
        return id == null ? noun + " #" + index : noun + " '" + id + "'";
    }

    /**
     * The noun a shape is named after: the last word of its class name, with the {@code Spec} /
     * {@code Definition} / {@code Document} suffix that says "this is a model class" dropped
     * first. {@code PipelineStep} is a step, {@code StateSpec} is a state.
     */
    private static String noun(Class<?> shape) {
        String name = shape.getSimpleName();
        for (String suffix : List.of("Spec", "Definition", "Document")) {
            if (name.length() > suffix.length() && name.endsWith(suffix)) {
                name = name.substring(0, name.length() - suffix.length());
                break;
            }
        }
        int word = 0;
        for (int i = 1; i < name.length(); i++) {
            if (Character.isUpperCase(name.charAt(i))) {
                word = i;
            }
        }
        return Character.toLowerCase(name.charAt(word)) + name.substring(word + 1);
    }

    /**
     * One unknown key, named by its path so {@code export.sql} does not read as a top-level
     * {@code sql}. A key with a known replacement is an error, because the author wrote
     * something meaningful and the loader threw it away. A renamed key was replaced in place; a
     * moved key went to a different block, and saying so is the whole diagnostic.
     */
    private static void reportUnknownKey(String path, String renamed, String moved,
            Set<String> accepted, String source, List<LintFinding> findings) {
        if (moved != null) {
            findings.add(new LintFinding("TQL-YAML-1044", "error", source,
                    "'" + path + ":' moved to '" + moved + ":' before v1 and is now"
                            + " silently dropped — declare it there"));
        } else if (renamed != null) {
            findings.add(new LintFinding("TQL-YAML-1044", "error", source,
                    "'" + path + ":' was renamed to '" + renamed + ":' before v1 and is now "
                            + "silently dropped — rename it"));
        } else {
            findings.add(new LintFinding("TQL-YAML-1043", "warning", source,
                    "Unknown key '" + path + ":' (accepted: " + accepted
                            + ") — it is silently ignored"));
        }
    }
}
