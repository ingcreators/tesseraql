package io.tesseraql.yaml.lint;

import io.tesseraql.yaml.model.JobDefinition;
import io.tesseraql.yaml.model.RouteDefinition;
import io.tesseraql.yaml.model.WorkflowDefinition;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Unknown and renamed keys on a document, its fixed-shape blocks and its
 * pipeline steps (TQL-YAML-1043/1044) — reached from every document family, so it is a
 * shared rule rather than a registry entry.
 *
 * <p>Extracted verbatim from {@code AppLinter} (docs/lint-restructure.md decision 1).
 */
final class UnknownKeyRules {

    private UnknownKeyRules() {
    }

    /**
     * Top-level keys renamed before v1 (docs/vocabulary-cleanup.md), per document-family root
     * record. The old spelling deserializes away silently, dropping the whole block — so it is a
     * hard error naming the replacement (TQL-YAML-1044), not a generic unknown-key warning. Only
     * unambiguously top-level renames live here; {@code notify:} is current on routes and renamed
     * only on workflows, so the map is keyed by the record it applies to.
     */
    static final Map<Class<?>, Map<String, String>> REMOVED_TOP_LEVEL_KEYS = Map.of(
            RouteDefinition.class, Map.of("page", "pagination", "policy", "admission",
                    "params", "input"),
            JobDefinition.class, Map.of("params", "input"),
            WorkflowDefinition.class, Map.of("notify", "reminders"));

    /**
     * Top-level blocks whose shape is a fixed record rather than a map of things the author
     * names, checked the same way the document's own keys are (docs/unified-sources.md decision
     * 15: the loose {@code additionalProperties: true} islands get real schemas, so editors
     * validate what the loader enforces — and so does the build).
     *
     * <p>Only fixed-shape blocks belong here. {@code sources:}, {@code steps:} and {@code input:}
     * are maps and arrays of author-named entries, so every key in them is "unknown" by
     * construction; their contents are checked by the lints that understand them.
     */
    static final Map<String, Class<?>> FIXED_SHAPE_BLOCKS = Map.of(
            "export", io.tesseraql.yaml.model.ExportSpec.class,
            "import", io.tesseraql.yaml.model.ImportSpec.class,
            "outbox", io.tesseraql.yaml.model.OutboxSpec.class,
            "errors", io.tesseraql.yaml.model.ErrorsSpec.class);

    /**
     * The output and processing blocks a pipeline step carries, checked the same way. A step's
     * own keys are the step record's; these are the blocks hanging off it, and they drifted for
     * the same reason the document-level ones did.
     */
    static final Map<String, Class<?>> STEP_BLOCKS = Map.of(
            "export", io.tesseraql.yaml.model.ExportSpec.class,
            "notify", io.tesseraql.yaml.model.NotifySpec.class,
            "push", io.tesseraql.yaml.model.PushSpec.class,
            "chunk", io.tesseraql.yaml.model.ChunkSpec.class);

    /**
     * Keys the unified source model moved out of a block, per block (docs/unified-sources.md
     * decision 7). Like the top-level renames these deserialize away in silence, so they are an
     * error naming where the key went rather than a generic unknown-key warning — an
     * {@code export.sql:} produced an export that wrote nothing at all.
     */
    static final Map<String, Map<String, String>> REMOVED_BLOCK_KEYS = Map.of(
            "export", Map.of("sql", "sources", "queries", "sources", "http", "sources"),
            "import", Map.of("sql", "steps"));

    static final Map<Class<?>, Set<String>> acceptedKeyCache = new java.util.HashMap<>();

    /**
     * The YAML keys a record accepts — a document-family root, a fixed-shape block, or a
     * pipeline step — derived from its {@code @JsonCreator} factory when it has one and from its
     * record components otherwise, honoring {@code @JsonProperty} either way (so
     * {@code notify}/{@code import}/{@code export} map correctly). Cached per class.
     *
     * <p>The creator comes first because the authoring form is what the author writes, and the
     * two part company exactly where a record is a folded shape: {@link
     * io.tesseraql.yaml.model.PipelineStep} holds a {@code Binding} but is authored with the
     * arms spread across the step, so its components list neither {@code when} nor {@code http}
     * nor {@code enrich}. Reading components alone would call three legal keys unknown.
     */
    static Set<String> acceptedKeys(Class<?> recordClass) {
        return acceptedKeyCache.computeIfAbsent(recordClass, cls -> {
            Set<String> keys = new java.util.TreeSet<>();
            List<String> authored = creatorKeys(cls);
            if (authored != null) {
                keys.addAll(authored);
                return keys;
            }
            for (java.lang.reflect.RecordComponent component : cls.getRecordComponents()) {
                keys.add(yamlName(cls, component));
            }
            return keys;
        });
    }

    /**
     * The {@code @JsonProperty} names of a class's properties-mode {@code @JsonCreator}, or null
     * when it has none. A delegating creator (a scalar shorthand such as a bare column name)
     * names no properties, so it is not one of these.
     */
    static List<String> creatorKeys(Class<?> cls) {
        for (java.lang.reflect.Method method : cls.getDeclaredMethods()) {
            if (method.getAnnotation(com.fasterxml.jackson.annotation.JsonCreator.class) == null
                    || method.getParameterCount() == 0) {
                continue;
            }
            List<String> names = new ArrayList<>();
            for (java.lang.reflect.Parameter parameter : method.getParameters()) {
                var property = parameter.getAnnotation(
                        com.fasterxml.jackson.annotation.JsonProperty.class);
                if (property == null) {
                    names = null;
                    break;
                }
                names.add(property.value());
            }
            if (names != null) {
                return names;
            }
        }
        return null;
    }

    /**
     * A record component's YAML name, honoring a {@code @JsonProperty} rename.
     *
     * <p>Read from the backing <em>field</em>, not the component mirror: {@code @JsonProperty}
     * cannot target a record component, so javac puts it on the field and
     * {@code RecordComponent.getAnnotation} answers null. Asking the mirror listed the three
     * renamed components under their Java names — so this lint told an author that
     * {@code export:}, {@code import:} and {@code notify:} were unknown keys "silently
     * ignored", naming {@code fileExport}, {@code fileImport} and {@code notifications} as the
     * accepted spellings, none of which the loader accepts. Every application declaring any of
     * the three carried the warning.
     */
    static String yamlName(Class<?> recordClass,
            java.lang.reflect.RecordComponent component) {
        try {
            var renamed = recordClass.getDeclaredField(component.getName())
                    .getAnnotation(com.fasterxml.jackson.annotation.JsonProperty.class);
            if (renamed != null && !renamed.value().isEmpty()) {
                return renamed.value();
            }
        } catch (NoSuchFieldException impossibleForARecord) {
            throw new IllegalStateException(impossibleForARecord);
        }
        return component.getName();
    }

    /**
     * Flags unknown keys on a document (TQL-YAML-1043, warning) and renamed ones
     * (TQL-YAML-1044, error). The model records are {@code @JsonIgnoreProperties(ignoreUnknown)},
     * so without this a typo'd {@code securty:} block drops auth with no diagnostic.
     * {@code extraKeys} carries keys a loader reads from the raw tree rather than the record
     * (e.g. mcp {@code description}/{@code uri}).
     *
     * <p>The document's own keys and its {@link #FIXED_SHAPE_BLOCKS} are checked the same way.
     * Nested blocks were a follow-up for a long time, and the cost showed: {@code export:} took
     * an {@code sql:} for two releases after the extraction moved to {@code sources:}, dropping
     * it in silence, and the documentation taught the dropped spelling because nothing
     * contradicted it.
     */
    static void lintUnknownKeys(LintContext context, Path appHome, Path file, Class<?> recordClass,
            Set<String> extraKeys, List<LintFinding> findings) {
        // A malformed document already failed the manifest load before lint ran; skip it.
        Map<String, Object> tree = context.tree(file);
        if (tree == null) {
            return;
        }
        Set<String> accepted = acceptedKeys(recordClass);
        Map<String, String> renamed = REMOVED_TOP_LEVEL_KEYS.getOrDefault(recordClass, Map.of());
        String source = LintSupport.relative(appHome, file);
        for (String key : tree.keySet()) {
            if (accepted.contains(key) || extraKeys.contains(key)) {
                continue;
            }
            reportUnknownKey(key, renamed.get(key), false, accepted, source, findings);
        }
        for (var block : FIXED_SHAPE_BLOCKS.entrySet()) {
            // Keyed by name, so confirm this document's key really is that record before
            // checking against it — a second family reusing the word would otherwise be linted
            // against a shape it never had.
            if (!declaresBlock(recordClass, block.getKey(), block.getValue())) {
                continue;
            }
            lintBlockKeys(tree.get(block.getKey()), block.getKey(), block.getValue(), source,
                    findings);
        }
        lintPipelineSteps(tree.get("pipeline"), source, findings);
    }

    /**
     * A job's steps, each checked as the fixed shape it is: the step's own keys, then the output
     * and processing blocks hanging off it ({@link #STEP_BLOCKS}). A route's {@code steps:} are
     * bindings whose arms the binding lints already walk; a pipeline step is where the output
     * blocks live, which is why this side needed the check.
     */
    static void lintPipelineSteps(Object pipeline, String source, List<LintFinding> findings) {
        if (!(pipeline instanceof List<?> steps)) {
            return;
        }
        Set<String> stepKeys = acceptedKeys(io.tesseraql.yaml.model.PipelineStep.class);
        for (Object item : steps) {
            if (!(item instanceof Map<?, ?> step)) {
                continue;
            }
            String id = step.get("id") == null ? "?" : String.valueOf(step.get("id"));
            for (Object key : step.keySet()) {
                String name = String.valueOf(key);
                if (stepKeys.contains(name)) {
                    continue;
                }
                reportUnknownKey("step '" + id + "' " + name, null, false, stepKeys, source,
                        findings);
            }
            for (var block : STEP_BLOCKS.entrySet()) {
                lintBlockKeys(step.get(block.getKey()), "step '" + id + "' " + block.getKey(),
                        block.getValue(), source, findings);
            }
        }
    }

    /** One fixed-shape block's keys against the record that holds it. */
    static void lintBlockKeys(Object block, String path, Class<?> blockClass, String source,
            List<LintFinding> findings) {
        if (!(block instanceof Map<?, ?> declared)) {
            return;
        }
        Set<String> blockKeys = acceptedKeys(blockClass);
        String name = path.substring(path.lastIndexOf(' ') + 1);
        Map<String, String> moved = REMOVED_BLOCK_KEYS.getOrDefault(name, Map.of());
        for (Object key : declared.keySet()) {
            String declaredKey = String.valueOf(key);
            if (blockKeys.contains(declaredKey)) {
                continue;
            }
            reportUnknownKey(path + "." + declaredKey, moved.get(declaredKey), true, blockKeys,
                    source, findings);
        }
    }

    /** Whether a document family declares {@code key} and holds it as exactly {@code blockClass}. */
    static boolean declaresBlock(Class<?> recordClass, String key, Class<?> blockClass) {
        for (java.lang.reflect.RecordComponent component : recordClass.getRecordComponents()) {
            if (yamlName(recordClass, component).equals(key)) {
                return component.getType() == blockClass;
            }
        }
        return false;
    }

    /**
     * One unknown key, named by its path so {@code export.sql} does not read as a top-level
     * {@code sql}. A key with a known replacement is an error, because the author wrote
     * something meaningful and the loader threw it away. A top-level key was renamed in place;
     * a block key moved to a different block, and saying so is the whole diagnostic.
     */
    static void reportUnknownKey(String path, String replacement, boolean moved,
            Set<String> accepted, String source, List<LintFinding> findings) {
        if (replacement != null && moved) {
            findings.add(new LintFinding("TQL-YAML-1044", "error", source,
                    "'" + path + ":' moved to '" + replacement + ":' before v1 and is now"
                            + " silently dropped — declare it there"));
        } else if (replacement != null) {
            findings.add(new LintFinding("TQL-YAML-1044", "error", source,
                    "'" + path + ":' was renamed to '" + replacement + ":' before v1 and is now "
                            + "silently dropped — rename it"));
        } else {
            findings.add(new LintFinding("TQL-YAML-1043", "warning", source,
                    "Unknown key '" + path + ":' (accepted: " + accepted
                            + ") — it is silently ignored"));
        }
    }
}
