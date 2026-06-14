package io.tesseraql.compiler.binding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.model.SqlBinding;
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
        Map<String, SqlBinding> steps = new LinkedHashMap<>();
        steps.put("lines", step(sql("lines.sql"), Map.of("orderId", "steps.header.keys.id")));
        steps.put("header", step(sql("header.sql"), Map.of()));

        assertThatThrownBy(() -> processor(null, steps))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-CAMEL-3102")
                .hasMessageContaining("references step 'header'");
    }

    @Test
    void rejectsStepWithBothFileAndSequence() {
        Map<String, SqlBinding> steps = Map.of("orderNo",
                new SqlBinding("a.sql", null, null, null, null, null, "order-number", null, null));

        assertThatThrownBy(() -> processor(null, steps))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("exactly one of file: or sequence:");
    }

    @Test
    void rejectsReservedAuditBindName() throws Exception {
        Map<String, SqlBinding> steps = Map.of("header",
                step(sql("header.sql"), Map.of("audit", "body.user")));

        assertThatThrownBy(() -> processor(null, steps))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("'audit' is reserved");
    }

    @Test
    void rejectsInvalidOnMismatch() throws Exception {
        Map<String, SqlBinding> steps = Map.of("header", new SqlBinding(sql("header.sql"), null,
                "update", null, null, null, null, null, new SqlBinding.Expect(1, "explode")));

        assertThatThrownBy(() -> processor(null, steps))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("expect.onMismatch must be conflict or error");
    }

    @Test
    void rejectsExpectWithoutRows() throws Exception {
        Map<String, SqlBinding> steps = Map.of("header", new SqlBinding(sql("header.sql"), null,
                "update", null, null, null, null, null, new SqlBinding.Expect(null, "conflict")));

        assertThatThrownBy(() -> processor(null, steps))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("expect.rows is required");
    }

    @Test
    void rejectsContractBindingInsideSteps() {
        Map<String, SqlBinding> steps = Map.of("header", new SqlBinding(null,
                "identity.create-user", null, null, null, null, null, null, null));

        assertThatThrownBy(() -> processor(null, steps))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("contract/service bindings are not supported");
    }

    @Test
    void rejectsKeysOnASequenceStep() {
        Map<String, SqlBinding> steps = Map.of("orderNo", new SqlBinding(null, null, null, null,
                null, null, "order-number", List.of("id"), null));

        assertThatThrownBy(() -> processor(null, steps))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("keys/expect do not apply to a sequence step");
    }

    @Test
    void rejectsExpectOnAQueryModeStep() throws Exception {
        Map<String, SqlBinding> steps = Map.of("check", new SqlBinding(sql("check.sql"), null,
                "query", null, null, null, null, null, new SqlBinding.Expect(1, null)));

        assertThatThrownBy(() -> processor(null, steps))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("expect/keys need an update statement");
    }

    @Test
    void rejectsBothSqlAndSteps() throws Exception {
        SqlBinding sql = step(sql("single.sql"), Map.of());
        Map<String, SqlBinding> steps = Map.of("header", step(sql("header.sql"), Map.of()));

        assertThatThrownBy(() -> processor(sql, steps))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("either sql: or steps:, not both");
    }

    @Test
    void rejectsValidationRuleWithBothExpressionAndFile() throws Exception {
        Map<String, ValidationRule> validate = Map.of("uniqueEmail", new ValidationRule(
                null, "body.email != null", "check-email.sql", null, "email", null, null));

        assertThatThrownBy(() -> processor(step(sql("single.sql"), Map.of()), Map.of(), validate))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-CAMEL-3102")
                .hasMessageContaining("exactly one of rule: or file:");
    }

    @Test
    void rejectsParamsOnAnExpressionRule() throws Exception {
        Map<String, ValidationRule> validate = Map.of("dateOrder", new ValidationRule(
                null, "body.endDate >= body.startDate", null, Map.of("email", "body.email"),
                "endDate", null, null));

        assertThatThrownBy(() -> processor(step(sql("single.sql"), Map.of()), Map.of(), validate))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("params apply to SQL rules only");
    }

    @Test
    void rejectsValidationSqlThatWrites() throws Exception {
        Map<String, ValidationRule> validate = Map.of("uniqueEmail", new ValidationRule(
                null, null, sql("check-email.sql"), Map.of(), "email", null, null));

        assertThatThrownBy(() -> processor(step(sql("single.sql"), Map.of()), Map.of(), validate))
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
                "body.endDate >= body.startDate", null, null, "endDate", null, null));
        validate.put("uniqueEmail", new ValidationRule(null, null, "check-email.sql",
                Map.of("email", "body.email"), "email", "duplicate", "members.email.duplicate"));

        assertThat(processor(step(sql("single.sql"), Map.of()), Map.of(), validate)).isNotNull();
    }

    @Test
    void acceptsOrderedStepsBindingEarlierResults() throws Exception {
        Map<String, SqlBinding> steps = new LinkedHashMap<>();
        steps.put("orderNo", new SqlBinding(null, null, null, null, null, null,
                "order-number", null, null));
        steps.put("header", step(sql("header.sql"), Map.of("no", "steps.orderNo.value")));
        steps.put("lines", step(sql("lines.sql"), Map.of("orderId", "steps.header.keys.id")));

        assertThat(processor(null, steps)).isNotNull();
    }

    @Test
    void acceptsANotifyBlockAndRejectsAChannellessNotification() throws Exception {
        Map<String, io.tesseraql.yaml.model.NotifySpec> valid = Map.of("confirmation",
                new io.tesseraql.yaml.model.NotifySpec("member-mail", null,
                        Map.of("email", "body.email")));
        assertThat(new TransactionalCommandProcessor("orders.create",
                step(sql("single.sql"), Map.of()), Map.of(), Map.of(), valid,
                file -> dir.resolve(file), "main", "postgres", null, null, null, "orders"))
                .isNotNull();

        Map<String, io.tesseraql.yaml.model.NotifySpec> channelless = Map.of("confirmation",
                new io.tesseraql.yaml.model.NotifySpec(null, null, Map.of()));
        assertThatThrownBy(() -> new TransactionalCommandProcessor("orders.create",
                step(sql("single.sql"), Map.of()), Map.of(), Map.of(), channelless,
                file -> dir.resolve(file), "main", "postgres", null, null, null, "orders"))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-FIELD-2004");
    }

    private TransactionalCommandProcessor processor(SqlBinding sql, Map<String, SqlBinding> steps) {
        return processor(sql, steps, Map.of());
    }

    private TransactionalCommandProcessor processor(SqlBinding sql, Map<String, SqlBinding> steps,
            Map<String, ValidationRule> validate) {
        return new TransactionalCommandProcessor("orders.create", sql, steps, validate, Map.of(),
                file -> dir.resolve(file), "main", "postgres", null, null, null, "orders");
    }

    private static SqlBinding step(String file, Map<String, String> params) {
        return new SqlBinding(file, null, "update", params, null, null, null, null, null);
    }

    private String sql(String name) throws Exception {
        Files.writeString(dir.resolve(name), "update t set a = /* a */1 where id = /* id */0\n");
        return name;
    }
}
