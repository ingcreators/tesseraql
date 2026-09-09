package io.tesseraql.test;

import io.tesseraql.test.TestReport.TestResult;
import io.tesseraql.test.TestSuite.TestCase;
import java.sql.Connection;

/**
 * The {@code sql} case kind, and the {@code verify:} read-back step every transactional kind
 * shares (a verify step is a SQL file too, run on the case's own connection).
 */
final class SqlCases {

    private final SuiteContext context;

    SqlCases(SuiteContext context) {
        this.context = context;
    }

    /**
     * Runs a {@code sql} case — the target file plus its {@code verify:} read-backs — on one
     * connection, inside a manual-commit transaction that is always rolled back. A write file
     * (an {@code UPDATE}/{@code INSERT}/{@code DELETE}) therefore executes for real and is
     * asserted through {@code expect.updateCount} and the verify steps, yet a test run never
     * commits anything to the database — pass or fail.
     *
     * <p>The case's {@code lock:} is seeded on the target alone (docs/edit-conflict.md). A
     * {@code verify:} step is a read-back, and a read-back cannot carry a lock directive at
     * all — so the seeding stops at the write, and a locked read-back keeps failing loudly.
     */
    TestResult run(TestCase test) {
        try (Connection connection = context.dataSource().getConnection()) {
            connection.setAutoCommit(false);
            try {
                SqlOutcome outcome = context.executeSql(connection,
                        context.appHome().resolve(test.sql().file()),
                        context.withLock(
                                SuiteContext.withPrincipal(test.params(), test.principal()),
                                test.lock()));
                String failure = Expectations.assertOutcome(test.expect(), outcome);
                for (int i = 0; failure == null && i < test.verify().size(); i++) {
                    failure = runVerifyStep(connection, test.verify().get(i), i,
                            test.principal());
                }
                return failure == null
                        ? TestResult.pass(test.name())
                        : TestResult.fail(test.name(), failure);
            } finally {
                connection.rollback();
                connection.setAutoCommit(true);
            }
        } catch (java.sql.SQLException ex) {
            throw new IllegalStateException("SQL execution failed: " + ex.getMessage(), ex);
        }
    }

    /** Runs one read-back on the case's transaction; null on pass, else the failure message. */
    String runVerifyStep(Connection connection, TestSuite.VerifyStep step, int index,
            TestSuite.PrincipalSpec principal) {
        String label = "verify[" + index + "]";
        if (step.sql() == null || step.sql().file() == null) {
            throw new IllegalArgumentException(label + " needs a sql.file target");
        }
        SqlOutcome outcome = context.executeSql(connection,
                context.appHome().resolve(step.sql().file()),
                SuiteContext.withPrincipal(step.params(), principal));
        if (outcome.rows() == null) {
            throw new IllegalArgumentException(label + " (" + step.sql().file()
                    + ") is a write; verify steps are read-backs and must return rows");
        }
        String failure = Expectations.assertOutcome(step.expect(), outcome);
        return failure == null ? null : label + " (" + step.sql().file() + "): " + failure;
    }
}
