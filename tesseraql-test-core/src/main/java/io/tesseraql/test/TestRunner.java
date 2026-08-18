package io.tesseraql.test;

import io.tesseraql.core.expr.ExpressionFunctions;
import io.tesseraql.coverage.SqlCoverage;
import io.tesseraql.identity.IdentityService;
import io.tesseraql.identity.RealmConfig;
import io.tesseraql.test.TestReport.TestResult;
import io.tesseraql.test.TestSuite.TestCase;
import io.tesseraql.yaml.manifest.AppManifest;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

/**
 * Runs a declarative {@link TestSuite} against a database, executing SQL files, Identity SQL
 * Contracts, and route validation rules and checking the result rows (design ch. 13, 13.2;
 * roadmap Phase 19 — a validation case's violations are its rows).
 *
 * <p>A {@code sql} case runs inside a manual-commit transaction that is always rolled back, so a
 * write file (an {@code UPDATE}/{@code INSERT}/{@code DELETE}) is a first-class target: its
 * affected-row count is asserted with {@code expect.updateCount}, and {@code verify:} read-back
 * steps observe the uncommitted write on the same connection before the rollback. A test run
 * never commits anything to the database.
 *
 * <p>The runner itself is the dispatcher: each case kind is one evaluator ({@link SqlCases},
 * {@link WorkflowCases}, {@link ValidationCases}, {@link DecisionCases}, {@link NotifyCases},
 * {@link HttpCallCases}, {@link MessageCases}, {@link IdentityCases}) over the shared
 * {@link SuiteContext}, and the real-send infrastructure a case may need is its own
 * ({@link CaptureServer}, {@link MailCapture}).
 */
public final class TestRunner {

    private final SuiteContext context;
    private final SqlCases sqlCases;
    private final WorkflowCases workflowCases;
    private final ValidationCases validationCases;
    private final DecisionCases decisionCases;
    private final NotifyCases notifyCases;
    private final HttpCallCases httpCallCases;
    private final MessageCases messageCases;
    private final IdentityCases identityCases;

    public TestRunner(DataSource dataSource, Path appHome) {
        this(dataSource, appHome, null, null, null);
    }

    public TestRunner(DataSource dataSource, Path appHome, IdentityService identity,
            RealmConfig realm) {
        this(dataSource, appHome, identity, realm, null);
    }

    public TestRunner(DataSource dataSource, Path appHome, IdentityService identity,
            RealmConfig realm,
            SqlCoverage coverage) {
        this(dataSource, appHome, identity, realm, coverage,
                ExpressionFunctions.processDefault());
    }

    /** As the five-argument form, resolving custom expression calls against {@code functions}. */
    public TestRunner(DataSource dataSource, Path appHome, IdentityService identity,
            RealmConfig realm,
            SqlCoverage coverage, ExpressionFunctions functions) {
        this.context = new SuiteContext(dataSource, appHome, identity, realm, coverage, functions);
        this.sqlCases = new SqlCases(context);
        this.workflowCases = new WorkflowCases(context, sqlCases);
        this.validationCases = new ValidationCases(context);
        this.decisionCases = new DecisionCases(context);
        this.notifyCases = new NotifyCases(context, new MailCapture(appHome));
        this.httpCallCases = new HttpCallCases(context);
        this.messageCases = new MessageCases(context);
        this.identityCases = new IdentityCases(context);
    }

    /** Runs all cases and returns a report. */
    public TestReport run(TestSuite suite) {
        ensureManagedSchemas();
        List<TestResult> results = new ArrayList<>();
        for (TestCase test : suite.tests()) {
            results.add(runCase(test));
        }
        return new TestReport(results);
    }

    /**
     * Provisions the managed framework tables the runtime would provision at startup
     * (docs/approval-workflow.md, docs/data-scoping.md), so app SQL that legitimately reads
     * them — an inbox scope over the task table, a rule reading {@code tql_workflow_instance}
     * — runs in a suite against the same schema it sees on a request. Without a datasource
     * (pure decide/messages suites) there is nothing to provision.
     */
    private void ensureManagedSchemas() {
        if (context.dataSource() == null) {
            return;
        }
        AppManifest loaded = context.manifest();
        if (!loaded.workflows().isEmpty()) {
            new io.tesseraql.operations.workflow.JdbcWorkflowStore(context.dataSource())
                    .ensureSchema();
            new io.tesseraql.operations.workflow.JdbcWorkflowTaskStore(context.dataSource())
                    .ensureSchema();
        }
        if (io.tesseraql.yaml.org.OrgUnitSettings.from(loaded.config()).managed()) {
            new io.tesseraql.operations.org.JdbcOrgUnitStore(context.dataSource()).ensureSchema();
        }
    }

    private TestResult runCase(TestCase test) {
        try {
            if (!test.given().isEmpty() && test.transition() == null
                    && test.dispatch() == null) {
                throw new IllegalArgumentException("Test '" + test.name()
                        + "' declares given: steps, which require a transition or"
                        + " dispatch target");
            }
            if (test.sql() != null && test.sql().file() != null) {
                return sqlCases.run(test);
            }
            if (test.transition() != null) {
                return workflowCases.runTransition(test);
            }
            if (test.dispatch() != null) {
                return workflowCases.runDispatch(test);
            }
            if (!test.verify().isEmpty()) {
                throw new IllegalArgumentException("Test '" + test.name()
                        + "' declares verify: steps, which require a sql target");
            }
            List<Map<String, Object>> rows = resultRows(test);
            return assertExpectation(test, rows);
        } catch (RuntimeException ex) {
            return TestResult.fail(test.name(), ex.getMessage());
        }
    }

    private List<Map<String, Object>> resultRows(TestCase test) {
        if (test.validate() != null) {
            return validationCases.evaluate(test);
        }
        if (test.decide() != null) {
            return decisionCases.evaluate(test);
        }
        if (test.notifications() != null) {
            return notifyCases.evaluate(test);
        }
        if (test.httpCall() != null) {
            return httpCallCases.evaluate(test);
        }
        if (test.messages() != null) {
            return messageCases.evaluate(test);
        }
        if (test.contract() != null && !test.contract().isBlank()) {
            return identityCases.evaluate(test);
        }
        throw new IllegalArgumentException(
                "Test '" + test.name() + "' has no sql, contract, validate, or decide target");
    }

    private TestResult assertExpectation(TestCase test, List<Map<String, Object>> rows) {
        String failure = Expectations.assertOutcome(test.expect(), new SqlOutcome(rows, null));
        return failure == null
                ? TestResult.pass(test.name())
                : TestResult.fail(test.name(), failure);
    }
}
