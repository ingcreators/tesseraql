package io.tesseraql.test;

import io.tesseraql.test.TestSuite.TestCase;
import java.sql.Connection;
import java.util.List;
import java.util.Map;

/** The {@code decide} case kind: one declared decision, its outputs as the case's row. */
final class DecisionCases {

    private final SuiteContext context;

    DecisionCases(SuiteContext context) {
        this.context = context;
    }

    /**
     * Runs a {@code decide} case (docs/decision-tables.md): evaluates one declared decision
     * against the case's params as input values. The matched row's outputs are the case's
     * single row; a miss or a unique multi-hit becomes one row carrying {@code code}, so a
     * suite can assert the no-silent-null contract as data instead of catching errors. A
     * table-backed decision compiles for the runner's own database vendor and runs its
     * generated SELECT on the test datasource — the same statement production runs.
     */
    List<Map<String, Object>> evaluate(TestCase test) {
        io.tesseraql.yaml.decision.DecisionSets sets = io.tesseraql.yaml.decision.DecisionSets
                .load(context.appHome(), new io.tesseraql.yaml.SimpleYamlParser());
        String name = test.decide().decision();
        io.tesseraql.yaml.model.DecisionsDocument.Decision decision = sets.decisions().get(name);
        if (decision == null) {
            throw new IllegalArgumentException("Test '" + test.name() + "' targets unknown"
                    + " decision '" + name + "' — declare it under decisions/");
        }
        try {
            if (decision.source() == null) {
                return List.of(io.tesseraql.yaml.decision.DecisionSets.compile(name, decision)
                        .evaluate(test.params()));
            }
            String vendor = context.vendor();
            io.tesseraql.core.decision.DecisionTables.TableSource source = io.tesseraql.yaml.decision.DecisionSets
                    .compileSource(name, decision, vendor);
            try (Connection connection = context.dataSource().getConnection()) {
                // An explicit timeoutSeconds(0): the suite runner has no configured bound
                // (docs/contract-sql-execution.md slice 2).
                return List.of(source.evaluate(
                        io.tesseraql.core.sql.SqlStatement.onCallerConnections()
                                .timeoutSeconds(0),
                        connection, test.params(), effectiveAt(test.decide().effectiveAt())));
            } catch (java.sql.SQLException ex) {
                throw new IllegalStateException("Decision lookup failed: " + ex.getMessage(), ex);
            }
        } catch (io.tesseraql.core.error.TqlException ex) {
            String code = ex.code() == null ? "" : ex.code().toString();
            if (code.equals("TQL-DECISION-4720") || code.equals("TQL-DECISION-4721")) {
                return List.of(Map.of("code", code));
            }
            throw ex;
        }
    }

    /** The dated-lookup instant: ISO-8601, {@code yyyy-MM-dd HH:mm:ss}, or the runner's clock. */
    private static java.sql.Timestamp effectiveAt(String declared) {
        if (declared == null || declared.isBlank()) {
            return new java.sql.Timestamp(System.currentTimeMillis());
        }
        try {
            return java.sql.Timestamp.from(java.time.Instant.parse(declared.trim()));
        } catch (java.time.format.DateTimeParseException notAnInstant) {
            return java.sql.Timestamp.valueOf(declared.trim());
        }
    }
}
