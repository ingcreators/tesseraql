package io.tesseraql.studio;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ValidationRuleBuilderTest {

    @Test
    void minGeneratesAComparisonExpressionRule() {
        String yaml = ValidationRuleBuilder.generate("min", "body", "age", "18", null,
                "ageAtLeast18", null, "users.age.too-young", null);

        assertThat(yaml).contains("validate:").contains("  ageAtLeast18:")
                .contains("rule: body.age >= 18").contains("field: age")
                .contains("code: ageAtLeast18").contains("message: users.age.too-young");
    }

    @Test
    void requiredAndNotEmptyBuildNullChecks() {
        assertThat(ValidationRuleBuilder.generate("required", "body", "name", null, null, "r", null,
                null, null)).contains("rule: body.name != null");
        assertThat(
                ValidationRuleBuilder.generate("not-empty", "body", "name", null, null, "r", null,
                        null, null))
                .contains("rule: body.name != null && body.name != ''");
    }

    @Test
    void equalsQuotesAStringButNotANumber() {
        assertThat(ValidationRuleBuilder.generate("equals", "body", "status", "ACTIVE", null, "s",
                null, null, null)).contains("rule: body.status == 'ACTIVE'");
        assertThat(ValidationRuleBuilder.generate("equals", "body", "n", "5", null, "s", null, null,
                null)).contains("rule: body.n == 5");
    }

    @Test
    void oneOfJoinsWithOr() {
        assertThat(ValidationRuleBuilder.generate("one-of", "body", "role", "A, B, C", null, "r",
                null, null, null))
                .contains("rule: body.role == 'A' || body.role == 'B' || body.role == 'C'");
    }

    @Test
    void rangeUsesBothValuesAndAWhenGuardIsEmitted() {
        String yaml = ValidationRuleBuilder.generate("range", "params", "n", "1", "10", "r",
                "code1",
                null, "body.checked == true");

        assertThat(yaml).contains("when: body.checked == true")
                .contains("rule: params.n >= 1 && params.n <= 10").contains("code: code1");
    }

    @Test
    void sqlRuleReferencesTheFile() {
        assertThat(ValidationRuleBuilder.generate("sql", "body", "email", "validate-unique.sql",
                null, "emailUnique", "duplicate", "users.email.taken", null))
                .contains("file: validate-unique.sql").contains("field: email")
                .contains("code: duplicate");
    }

    @Test
    void missingRequiredInputsReturnAComment() {
        assertThat(ValidationRuleBuilder.generate("min", "body", "age", null, null, "r", null, null,
                null)).startsWith("#");
        assertThat(ValidationRuleBuilder.generate("min", "body", null, "18", null, "r", null, null,
                null)).startsWith("#");
        assertThat(
                ValidationRuleBuilder.generate("min", "body", "age", "18", null, null, null, null,
                        null))
                .startsWith("#");
    }
}
