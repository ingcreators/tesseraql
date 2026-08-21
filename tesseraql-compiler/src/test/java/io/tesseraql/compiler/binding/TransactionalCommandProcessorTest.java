package io.tesseraql.compiler.binding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.model.Binding;
import io.tesseraql.yaml.model.ValidationRule;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Fail-fast validation of the Phase 18 steps declaration: a misdeclared command route must fail
 * at route build time, not on the first request. Execution semantics (transaction, generated
 * keys, expectations, constraint mapping) are covered by the runtime integration tests.
 */
class TransactionalCommandProcessorTest {

    @TempDir
    Path dir;

    @Test
    void rejectsStepReferencingALaterStep() throws Exception {
        Map<String, Binding> steps = new LinkedHashMap<>();
        steps.put("lines", step(sql("lines.sql"), Map.of("orderId", "steps.header.keys.id")));
        steps.put("header", step(sql("header.sql"), Map.of()));

        assertThatThrownBy(() -> processor(steps))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-ROUTE-3102")
                .hasMessageContaining("references step 'header'");
    }

    @Test
    void rejectsStepWithBothFileAndSequence() {
        Map<String, Binding> steps = Map.of("orderNo",
                new Binding("a.sql", null, null, null, null, null, null, "order-number", null,
                        null, null, null, null));

        assertThatThrownBy(() -> processor(steps))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("exactly one of file: or sequence:");
    }

    @Test
    void rejectsReservedAuditBindName() throws Exception {
        Map<String, Binding> steps = Map.of("header",
                step(sql("header.sql"), Map.of("audit", "body.user")));

        assertThatThrownBy(() -> processor(steps))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("'audit' is reserved");
    }

    @Test
    void rejectsInvalidOnMismatch() throws Exception {
        Map<String, Binding> steps = Map.of("header", new Binding(sql("header.sql"), null,
                "update", null, null, null, null, null, null, new Binding.Expect(1, "explode"),
                null, null, null));

        assertThatThrownBy(() -> processor(steps))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("expect.onMismatch must be conflict or error");
    }

    @Test
    void rejectsExpectWithoutRows() throws Exception {
        Map<String, Binding> steps = Map.of("header", new Binding(sql("header.sql"), null,
                "update", null, null, null, null, null, null,
                new Binding.Expect(null, "conflict"), null, null, null));

        assertThatThrownBy(() -> processor(steps))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("expect.rows is required");
    }

    @Test
    void rejectsContractBindingInsideSteps() {
        Map<String, Binding> steps = Map.of("header", new Binding(null,
                "identity.create-user", null, null, null, null, null, null, null, null, null,
                null, null));

        assertThatThrownBy(() -> processor(steps))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("contract/service bindings are not supported");
    }

    @Test
    void rejectsKeysOnASequenceStep() {
        Map<String, Binding> steps = Map.of("orderNo", new Binding(null, null, null, null,
                null, null, null, "order-number", List.of("id"), null, null, null, null));

        assertThatThrownBy(() -> processor(steps))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("keys/expect do not apply to a sequence step");
    }

    @Test
    void rejectsExpectOnAQueryModeStep() throws Exception {
        Map<String, Binding> steps = Map.of("check", new Binding(sql("check.sql"), null,
                "query", null, null, null, null, null, null, new Binding.Expect(1, null), null,
                null, null));

        assertThatThrownBy(() -> processor(steps))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("expect/keys need an update statement");
    }

    @Test
    void rejectsACommandThatDeclaresNoStatement() {
        assertThatThrownBy(() -> processor(Map.of()))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("needs a steps: declaration");
    }

    @Test
    void rejectsValidationRuleWithBothExpressionAndFile() throws Exception {
        Map<String, ValidationRule> validate = Map.of("uniqueEmail", new ValidationRule(
                null, "body.email != null", "check-email.sql", null, "email", null, null, null));

        assertThatThrownBy(
                () -> processor(Map.of("main", step(sql("single.sql"), Map.of())), validate))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-ROUTE-3102")
                .hasMessageContaining("exactly one of rule: or file:");
    }

    @Test
    void rejectsParamsOnAnExpressionRule() throws Exception {
        Map<String, ValidationRule> validate = Map.of("dateOrder", new ValidationRule(
                null, "body.endDate >= body.startDate", null, Map.of("email", "body.email"),
                "endDate", null, null, null));

        assertThatThrownBy(
                () -> processor(Map.of("main", step(sql("single.sql"), Map.of())), validate))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("params apply to SQL rules only");
    }

    @Test
    void rejectsValidationSqlThatWrites() throws Exception {
        Map<String, ValidationRule> validate = Map.of("uniqueEmail", new ValidationRule(
                null, null, sql("check-email.sql"), Map.of(), "email", null, null, null));

        assertThatThrownBy(
                () -> processor(Map.of("main", step(sql("single.sql"), Map.of())), validate))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-FIELD-2003")
                .hasMessageContaining("must be a SELECT");
    }

    @Test
    void acceptsExpressionAndSelectValidationRules() throws Exception {
        Files.writeString(dir.resolve("check-email.sql"),
                "select 'email' as field from t where email = /* email */'x'\n");
        Map<String, ValidationRule> validate = new LinkedHashMap<>();
        validate.put("dateOrder", new ValidationRule("body.endDate != null",
                "body.endDate >= body.startDate", null, null, "endDate", null, null, null));
        validate.put("uniqueEmail", new ValidationRule(null, null, "check-email.sql",
                Map.of("email", "body.email"), "email", "duplicate", "members.email.duplicate",
                null));

        assertThat(processor(Map.of("main", step(sql("single.sql"), Map.of())), validate))
                .isNotNull();
    }

    @Test
    void acceptsOrderedStepsBindingEarlierResults() throws Exception {
        Map<String, Binding> steps = new LinkedHashMap<>();
        steps.put("orderNo", Binding.sequence("order-number"));
        steps.put("header", step(sql("header.sql"), Map.of("no", "steps.orderNo.value")));
        steps.put("lines", step(sql("lines.sql"), Map.of("orderId", "steps.header.keys.id")));

        assertThat(processor(steps)).isNotNull();
    }

    @Test
    void acceptsANotifyBlockAndRejectsAChannellessNotification() throws Exception {
        Map<String, io.tesseraql.yaml.model.NotifySpec> valid = Map.of("confirmation",
                new io.tesseraql.yaml.model.NotifySpec("member-mail", null,
                        Map.of("email", "body.email")));
        assertThat(new TransactionalCommandProcessor("orders.create",
                new CommandDeclaration(Map.of("main", step(sql("single.sql"), Map.of())),
                        Map.of(), Map.of(), valid, null, null, null),
                file -> dir.resolve(file), "main", "postgres", "orders", null, UNBOUNDED))
                .isNotNull();

        Map<String, io.tesseraql.yaml.model.NotifySpec> channelless = Map.of("confirmation",
                new io.tesseraql.yaml.model.NotifySpec(null, null, Map.of()));
        assertThatThrownBy(() -> new TransactionalCommandProcessor("orders.create",
                new CommandDeclaration(Map.of("main", step(sql("single.sql"), Map.of())),
                        Map.of(), Map.of(), channelless, null, null, null),
                file -> dir.resolve(file), "main", "postgres", "orders", null, UNBOUNDED))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-FIELD-2004");
    }

    /** A when: guard selects among steps (docs/decision-tables.md "Acting on the result"). */
    @Test
    void aStepAcceptsAWhenGuard() throws Exception {
        Map<String, Binding> steps = new java.util.LinkedHashMap<>();
        steps.put("approve", new Binding(sql("approve.sql"), null, "update", Map.of(), null,
                null, null, null, java.util.List.of(), null, null, null,
                "decision.approvalRoute.level == 1"));

        assertThat(processor(steps)).isNotNull();
    }

    /** These tests cover construction and step compilation, not execution bounds. */
    private static final ExecutionBounds UNBOUNDED = new ExecutionBounds(0, -1, "fail");

    private TransactionalCommandProcessor processor(Map<String, Binding> steps) {
        return processor(steps, Map.of());
    }

    private TransactionalCommandProcessor processor(Map<String, Binding> steps,
            Map<String, ValidationRule> validate) {
        return new TransactionalCommandProcessor("orders.create",
                new CommandDeclaration(steps, validate, Map.of(), Map.of(), null, null, null),
                file -> dir.resolve(file), "main", "postgres", "orders", null, UNBOUNDED);
    }

    private static Binding step(String file, Map<String, String> params) {
        return Binding.sql(file, "update", params);
    }

    private String sql(String name) throws Exception {
        Files.writeString(dir.resolve(name), "update t set a = /* a */1 where id = /* id */0\n");
        return name;
    }
}
