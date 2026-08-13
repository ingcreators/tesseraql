package io.tesseraql.test;

import io.tesseraql.core.sql.BoundSql;
import io.tesseraql.core.validation.ValidationRules;
import io.tesseraql.coverage.SqlCoverableLines;
import io.tesseraql.test.TestSuite.TestCase;
import io.tesseraql.yaml.manifest.RouteFile;
import io.tesseraql.yaml.model.ValidationRule;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The {@code validate} case kind: a route's rules, their violations as the case's rows. */
final class ValidationCases {

    private final SuiteContext context;

    ValidationCases(SuiteContext context) {
        this.context = context;
    }

    /**
     * Evaluates a route's {@code validate:} rules against the case's params (the execution
     * context the rules see) and returns the violations as the case's rows (roadmap Phase 19).
     * SQL rules run against the test datasource and record coverage like SQL-file cases.
     */
    List<Map<String, Object>> evaluate(TestCase test) {
        RouteFile route = context.route(test.validate().route());
        Path routeDir = route.source().getParent();
        List<ValidationRules.Rule> rules = new ArrayList<>();
        route.definition().validate().forEach((id, rule) -> {
            if (test.validate().rule() != null && !test.validate().rule().equals(id)) {
                return;
            }
            rules.add(compileRule(routeDir, id, rule));
        });
        if (rules.isEmpty()) {
            throw new IllegalArgumentException("Route '" + test.validate().route()
                    + "' declares no matching validation rule"
                    + (test.validate().rule() == null ? "" : " '" + test.validate().rule() + "'"));
        }
        try (Connection connection = context.dataSource().getConnection()) {
            // The case's principal: (when declared) seeds the scope context and the ambient
            // principal.* paths, so a scoped rule renders exactly as it would on a request.
            return new ValidationRules(rules).evaluate(
                    SuiteContext.withPrincipal(test.params(), test.principal()), connection,
                    context.scopeResolver(),
                    (rule, bound) -> recordRuleCoverage(rule, bound));
        } catch (java.sql.SQLException ex) {
            throw new IllegalStateException("Validation SQL failed: " + ex.getMessage(), ex);
        }
    }

    private ValidationRules.Rule compileRule(Path routeDir, String id, ValidationRule rule) {
        if (rule.isExpression()) {
            return ValidationRules.expression(id, rule.when(), rule.rule(), rule.field(),
                    rule.code(), rule.message());
        }
        Path file = routeDir.resolve(rule.file()).normalize();
        return ValidationRules.sql(id, rule.when(), SuiteContext.read(file), file.toString(),
                rule.params(), rule.field(), rule.code(), rule.message());
    }

    private void recordRuleCoverage(ValidationRules.Rule rule, BoundSql bound) {
        if (context.coverage() != null && rule.sourcePath() != null) {
            String sqlId = context.sqlId(Path.of(rule.sourcePath()));
            context.coverage().record(sqlId, bound.coverageTrace(),
                    SqlCoverableLines.compute(rule.sql()));
        }
    }
}
