package io.tesseraql.studio;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.studio.Declarations.Located;
import io.tesseraql.studio.StudioService.DecisionColumn;
import io.tesseraql.studio.StudioService.DecisionGrid;
import io.tesseraql.studio.StudioService.GridColumn;
import io.tesseraql.studio.StudioService.SharedDecision;
import io.tesseraql.studio.StudioService.SharedRule;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The decision-table grid the Studio edits: a named decision read out of its {@code decisions/}
 * document as a rectangular grid, and the edited grid written back as a draft.
 *
 * <p>Extracted from {@code StudioService}; the records the view renders stay declared there.
 */
final class DecisionForms {

    /** A decision-rows grid save that cannot even reach the decision compile (shape/target). */
    private static final TqlErrorCode DECISION_ROWS = new TqlErrorCode(TqlDomain.STUDIO, 4237);

    private final Declarations declarations;

    DecisionForms(Declarations declarations) {
        this.declarations = declarations;
    }

    /**
     * Declared decisions with their input contracts, for the decide-snippet builder — the
     * contract has to travel with the name (the {@link SharedRule} reasoning): a reference must
     * wire the inputs exactly, and the author cannot be expected to remember them.
     */
    List<SharedDecision> sharedDecisions() {
        var declared = io.tesseraql.yaml.decision.DecisionSets.load(declarations.appHome(),
                declarations.parser());
        List<SharedDecision> decisions = new ArrayList<>();
        declared.decisions().forEach((name, decision) -> decisions.add(new SharedDecision(name,
                List.copyOf(decision.inputs().keySet()),
                decision.source() != null && !decision.source().effective().isEmpty(),
                decision.source() == null)));
        decisions.sort(java.util.Comparator.comparing(SharedDecision::name));
        return decisions;
    }

    /**
     * Loads the rows-grid model for a YAML-backed decision (docs/decision-tables.md "Studio":
     * the table-shaped editor): one column per input then per output, one row of cell text per
     * authored row. A blank condition cell is the wildcard; an {@code in} cell joins its
     * members with commas. Reads the pending draft when one exists, like the route form.
     */
    DecisionGrid decisionGrid(String name) {
        Located located = locateDecision(name);
        if (located == null) {
            return new DecisionGrid(name, null, false, false, false, List.of(), List.of(),
                    "No decision named '" + name + "' is declared under decisions/");
        }
        Map<String, io.tesseraql.yaml.model.InputField> domains = io.tesseraql.yaml.domain.FieldDomains
                .load(declarations.appHome()).domains();
        Map<String, Object> decision = located.node();
        boolean yamlBacked = decision.get("source") == null;
        List<GridColumn> columns = new ArrayList<>();
        StudioService.anyMap(decision.get("inputs")).forEach((key, spec) -> {
            Map<String, Object> field = StudioService.anyMap(spec);
            String match = StudioService.scalar(field.get("match"));
            String type = fieldType(field, domains);
            columns.add(new GridColumn(key, "in",
                    (match == null ? "eq" : match) + (type == null ? "" : " · " + type)));
        });
        StudioService.anyMap(decision.get("outputs")).forEach((key, spec) -> {
            Map<String, Object> field = StudioService.anyMap(spec);
            String enums = StudioService.csvOf(field.get("enum"));
            String type = fieldType(field, domains);
            columns.add(new GridColumn(key, "out", enums != null ? "enum: " + enums : type));
        });
        List<List<String>> rows = new ArrayList<>();
        for (Object entry : anyList(decision.get("rows"))) {
            Map<String, Object> row = StudioService.anyMap(entry);
            Map<String, Object> when = StudioService.anyMap(row.get("when"));
            Map<String, Object> out = StudioService.anyMap(row.get("outputs"));
            List<String> cells = new ArrayList<>();
            for (GridColumn column : columns) {
                Object value = "in".equals(column.kind())
                        ? when.get(column.key())
                        : out.get(column.key());
                cells.add(cellText(value));
            }
            rows.add(cells);
        }
        // One trailing add-row must still fit the fixed slots, so 20 authored rows already
        // exceed the grid — refusing beats silently truncating on save.
        boolean tooLarge = columns.size() > StudioService.DECISION_GRID_COLUMNS
                || rows.size() >= StudioService.DECISION_GRID_ROWS;
        return new DecisionGrid(name, located.path(), yamlBacked, located.fromDraft(), tooLarge,
                columns, rows, null);
    }

    /**
     * Rebuilds a YAML-backed decision's {@code rows:} from the posted grid and saves the
     * re-serialized document as a draft (the {@code routeFormSave} persistence contract): the
     * draft/apply flow supplies conflict detection and compile-before-write on apply, and the
     * document is validated here first — {@code parseDecisions} plus
     * {@link io.tesseraql.yaml.decision.DecisionSets#compile}, so a bad cell, an overlap, or an
     * enum typo rejects with its {@code TQL-DECISION} code and nothing is written. Comments and
     * hand formatting are not preserved (canonical re-serialization, like the route form).
     */
    Path saveDecisionRows(String name, List<DecisionColumn> columns,
            java.util.Set<Integer> deletes, String actor) {
        if (declarations.readOnly()) {
            throw new TqlException(StudioService.READ_ONLY,
                    "Studio is read-only; editing decisions is"
                            + " disabled");
        }
        Located located = locateDecision(name);
        if (located == null) {
            throw new TqlException(StudioService.NOT_FOUND,
                    "No decision named '" + name + "' is declared under decisions/");
        }
        Map<String, Object> decision = located.node();
        if (decision.get("source") != null) {
            throw new TqlException(DECISION_ROWS, "Decision '" + name + "' is table-backed —"
                    + " its rows live in the app table (edit them in the data browser)");
        }
        if (columns.isEmpty()) {
            throw new TqlException(DECISION_ROWS, "The grid posted no columns");
        }
        Map<String, io.tesseraql.yaml.model.InputField> domains = io.tesseraql.yaml.domain.FieldDomains
                .load(declarations.appHome()).domains();
        Map<String, Object> inputs = StudioService.anyMap(decision.get("inputs"));
        Map<String, Object> outputs = StudioService.anyMap(decision.get("outputs"));
        int posted = columns.stream().mapToInt(column -> column.cells().size()).max().orElse(0);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < posted; i++) {
            if (deletes.contains(i)) {
                continue;
            }
            Map<String, Object> when = new LinkedHashMap<>();
            Map<String, Object> out = new LinkedHashMap<>();
            for (DecisionColumn column : columns) {
                String raw = i < column.cells().size()
                        ? StudioService.trimToNull(column.cells().get(i))
                        : null;
                if (raw == null) {
                    // Blank = wildcard for a condition; for an output, the compile below
                    // rejects a half-set row (a row must set every output).
                    continue;
                }
                if ("out".equals(column.kind())) {
                    out.put(column.key(),
                            decisionScalar(
                                    fieldType(StudioService.anyMap(outputs.get(column.key())),
                                            domains),
                                    raw));
                } else {
                    Map<String, Object> spec = StudioService.anyMap(inputs.get(column.key()));
                    String type = fieldType(spec, domains);
                    if ("in".equals(StudioService.scalar(spec.get("match")))) {
                        List<Object> values = new ArrayList<>();
                        for (String part : raw.split(",")) {
                            String member = StudioService.trimToNull(part);
                            if (member != null) {
                                values.add(decisionScalar(type, member));
                            }
                        }
                        when.put(column.key(), values);
                    } else {
                        when.put(column.key(), decisionScalar(type, raw));
                    }
                }
            }
            if (when.isEmpty() && out.isEmpty()) {
                continue; // the blank add-row (or an emptied one)
            }
            Map<String, Object> row = new LinkedHashMap<>();
            if (!when.isEmpty()) {
                row.put("when", when);
            }
            row.put("outputs", out);
            rows.add(row);
        }
        decision.put("rows", rows);
        String yaml = declarations.parser().write(located.tree());
        validateDecisionDraft(name, yaml);
        Path draft = declarations.saveDraft(located.path(), yaml);
        declarations.audit(actor, "decision-rows", name);
        return draft;
    }

    /** The draft-aware locate over {@code decisions/*.yml} ({@code decisions:} documents). */
    private Located locateDecision(String name) {
        return declarations.locate("decisions", "decisions", name);
    }

    /**
     * Validates the rebuilt decisions document before anything is persisted: the serialized
     * text must parse as a decisions document, and the edited decision must compile — the
     * exact checks the manifest load applies, so a bad row dies here with its
     * {@code TQL-DECISION} code instead of landing in a draft that can never apply. The text
     * is parsed from a temp file because the decisions declarations.parser() reads files.
     */
    private void validateDecisionDraft(String name, String yaml) {
        Path temp;
        try {
            temp = Files.createTempFile("tql-decisions-", ".yml");
            Files.writeString(temp, yaml);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        try {
            io.tesseraql.yaml.model.DecisionsDocument document = declarations.parser()
                    .parseDecisions(temp);
            io.tesseraql.yaml.model.DecisionsDocument.Decision rebuilt = document.decisions()
                    .get(name);
            if (rebuilt == null) {
                throw new TqlException(DECISION_ROWS, "The rebuilt document no longer declares"
                        + " decision '" + name + "'");
            }
            io.tesseraql.yaml.decision.DecisionSets.compile(name, rebuilt);
        } finally {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // best-effort cleanup
            }
        }
    }

    /** A cell's display text: absent = blank (wildcard), a list joined with commas. */
    private static String cellText(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf)
                    .collect(java.util.stream.Collectors.joining(", "));
        }
        return String.valueOf(value);
    }

    /**
     * A field declaration's effective type: its inline {@code type:}, else its referenced
     * domain's (docs/field-domains.md) — the merge {@code DecisionSets.load} applies, needed
     * here so a cell of a domain-typed input coerces like an inline-typed one.
     */
    private static String fieldType(Map<String, Object> spec,
            Map<String, io.tesseraql.yaml.model.InputField> domains) {
        String type = StudioService.scalar(spec.get("type"));
        if (type != null) {
            return type;
        }
        String domain = StudioService.scalar(spec.get("domain"));
        if (domain == null) {
            return null;
        }
        io.tesseraql.yaml.model.InputField field = domains.get(domain);
        return field == null ? null : field.type();
    }

    /**
     * Coerces one grid cell into the YAML scalar the rows carry (the {@code routeFormSave}
     * {@code decimalOrNull} reasoning, widened for cells): numeric- and boolean-looking text
     * becomes a number or boolean — unless the field's declared type is {@code string}, where
     * a numeric-looking value must stay a string. Comparator cells ({@code >= 10},
     * {@code 1..5}) look like neither and stay text, which is what a {@code between} match
     * parses. A value that fails its declared type survives to the compile step, which rejects
     * it with {@code TQL-DECISION-4708}.
     */
    private static Object decisionScalar(String type, String value) {
        if ("string".equals(type)) {
            return value;
        }
        if ("true".equals(value) || "false".equals(value)) {
            return Boolean.valueOf(value);
        }
        if (value.matches("-?\\d{1,18}")) {
            return Long.valueOf(value);
        }
        if (value.matches("-?\\d+\\.\\d+")) {
            return new java.math.BigDecimal(value);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> anyList(Object value) {
        return value instanceof List ? (List<Object>) value : new ArrayList<>();
    }

}
