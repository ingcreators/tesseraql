package io.tesseraql.yaml.model;

import java.util.List;
import java.util.Map;

/**
 * One parsed {@code fragments/*.yml} document (docs/transactional-writes.md, "Shared step
 * fragments"): named step sequences declared once for commands to reference from {@code steps:}
 * via the {@code use:} arm.
 *
 * <p>Domains, rules, decisions, scope fragments, calendars and messages are all shared documents
 * referenced by name — a step sequence was not. The audit note, the counter refresh, the "write
 * the interface row" tail that a dozen commands repeat was copied into each {@code steps:} block,
 * and the copies drift.
 *
 * <p>There is deliberately no {@code include:}/{@code extends:} in the document model, and that
 * stays: a route a reviewer reads must be whole. This is the {@code rules/} shape instead — a
 * named artifact with a typed contract, referenced where it is used and expanded at manifest
 * load, so the transaction, coverage, spans and lints see ordinary steps with no runtime
 * indirection.
 *
 * @param version   the DSL version, {@code tesseraql/v1}
 * @param fragments declared fragments by name
 */
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public record FragmentsDocument(String version, Map<String, Fragment> fragments) {

    public FragmentsDocument {
        fragments = fragments == null
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(fragments));
    }

    /**
     * One shared step sequence: what the sequence <em>is</em>. What stays local at every
     * reference — the id it expands under and the {@code params:} wiring — is the command's use
     * of it, the same split {@code rules/} makes.
     *
     * @param binds the typed bind contract a reference's {@code params:} must satisfy exactly;
     *              a step inside the fragment reads one as {@code binds.<name>}
     * @param steps the sequence, in authored order, in the ordinary step vocabulary
     */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record Fragment(Map<String, String> binds,
            @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = StepsDeserializer.class) Map<String, Binding> steps) {

        public Fragment {
            binds = binds == null
                    ? Map.of()
                    : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(binds));
            steps = steps == null
                    ? Map.of()
                    : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(steps));
        }

        /** The bind names, in declaration order. */
        public List<String> bindNames() {
            return List.copyOf(binds.keySet());
        }
    }
}
