package io.tesseraql.studio;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ValidationRuleBuilderTest {

    /** The nine-argument form every pre-`use:` case uses; the contract is empty for those. */
    private static String generate(String operation, String source, String field, String value,
            String value2, String id, String code, String message, String when) {
        return ValidationRuleBuilder.generate(operation, source, field, value, value2, id, code,
                message, when, java.util.List.of());
    }

    /**
     * The builder can reference a shared rule instead of copying one.
     *
     * <p>It emitted only inline rules, so an author generating a SQL rule got an inline copy with
     * unbound non-ambient binds — nudged into exactly the duplication `rules/` exists to
     * eliminate, by the tool meant to help.
     */
    @Test
    void aSharedRuleReferenceWiresTheWholeContract() {
        String yaml = ValidationRuleBuilder.generate("use", "body", "name", "itemsNameIsFree",
                null, "nameFree", null, null, null,
                java.util.List.of("name", "excludeId"));

        assertThat(yaml).contains("use: itemsNameIsFree");
        // Every bind laid out, because a reference must wire the contract exactly: an omission
        // fails the load (TQL-FIELD-4607), and a snippet that fails the load is worse than none.
        assertThat(yaml).contains("params:").contains("name: body.name")
                .contains("excludeId: body.excludeId");
        assertThat(yaml).contains("field: name");
        // code:/message: are the shared rule's defaults unless overridden, so they stay out.
        assertThat(yaml).doesNotContain("code:");
    }

    @Test
    void aReferenceWithoutARuleNameAsksForOne() {
        assertThat(ValidationRuleBuilder.generate("use", "body", "name", null, null, "r", null,
                null, null, java.util.List.of()))
                .contains("Choose the shared rule");
    }

    @Test
    void minGeneratesAComparisonExpressionRule() {
        String yaml = generate("min", "body", "age", "18", null,
                "ageAtLeast18", null, "users.age.too-young", null);

        assertThat(yaml).contains("validate:").contains("  ageAtLeast18:")
                .contains("rule: body.age >= 18").contains("field: age")
                .contains("code: ageAtLeast18").contains("message: users.age.too-young");
    }

    @Test
    void requiredAndNotEmptyBuildNullChecks() {
        assertThat(generate("required", "body", "name", null, null, "r", null,
                null, null)).contains("rule: body.name != null");
        assertThat(
                generate("not-empty", "body", "name", null, null, "r", null,
                        null, null))
                .contains("rule: body.name != null && body.name != ''");
    }

    @Test
    void equalsQuotesAStringButNotANumber() {
        assertThat(generate("equals", "body", "status", "ACTIVE", null, "s",
                null, null, null)).contains("rule: body.status == 'ACTIVE'");
        assertThat(generate("equals", "body", "n", "5", null, "s", null, null,
                null)).contains("rule: body.n == 5");
    }

    @Test
    void oneOfJoinsWithOr() {
        assertThat(generate("one-of", "body", "role", "A, B, C", null, "r",
                null, null, null))
                .contains("rule: body.role == 'A' || body.role == 'B' || body.role == 'C'");
    }

    @Test
    void rangeUsesBothValuesAndAWhenGuardIsEmitted() {
        String yaml = generate("range", "params", "n", "1", "10", "r",
                "code1",
                null, "body.checked == true");

        assertThat(yaml).contains("when: body.checked == true")
                .contains("rule: params.n >= 1 && params.n <= 10").contains("code: code1");
    }

    @Test
    void sqlRuleReferencesTheFile() {
        assertThat(generate("sql", "body", "email", "validate-unique.sql",
                null, "emailUnique", "duplicate", "users.email.taken", null))
                .contains("file: validate-unique.sql").contains("field: email")
                .contains("code: duplicate");
    }

    @Test
    void missingRequiredInputsReturnAComment() {
        assertThat(generate("min", "body", "age", null, null, "r", null, null,
                null)).startsWith("#");
        assertThat(generate("min", "body", null, "18", null, "r", null, null,
                null)).startsWith("#");
        assertThat(
                generate("min", "body", "age", "18", null, null, null, null,
                        null))
                .startsWith("#");
    }
}
