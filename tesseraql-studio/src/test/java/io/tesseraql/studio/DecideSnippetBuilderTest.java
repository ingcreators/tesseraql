package io.tesseraql.studio;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DecideSnippetBuilderTest {

    @Test
    void aReferenceWiresTheWholeContract() {
        String yaml = DecideSnippetBuilder.generate("approvalRoute",
                java.util.List.of("amount", "category", "dept"), false);

        assertThat(yaml).startsWith("decide:\n  approvalRoute:\n")
                .contains("use: approvalRoute");
        // Every input laid out, because a reference must wire the contract exactly: an
        // omission fails the load (TQL-DECISION-4706), and a snippet that fails the load is
        // worse than none.
        assertThat(yaml).contains("params:").contains("amount: params.amount")
                .contains("category: params.category").contains("dept: params.dept");
        // Not a dated decision, so the effectiveAt: hint stays out.
        assertThat(yaml).doesNotContain("effectiveAt");
    }

    @Test
    void aDatedDecisionCarriesTheEffectiveAtHintAsAComment() {
        String yaml = DecideSnippetBuilder.generate("feeSchedule",
                java.util.List.of("region"), true);

        // The default is audit.now; pinning the instant is the author's choice, so the line
        // is a comment, not live YAML.
        assertThat(yaml)
                .contains("# effectiveAt: params.postingDate  (default audit.now)");
    }

    @Test
    void missingDecisionReturnsAComment() {
        assertThat(DecideSnippetBuilder.generate(null, java.util.List.of(), false))
                .startsWith("#");
        assertThat(DecideSnippetBuilder.generate("  ", java.util.List.of(), false))
                .startsWith("#");
    }

    @Test
    void anInputlessContractOmitsTheParamsBlock() {
        assertThat(DecideSnippetBuilder.generate("x", java.util.List.of(), false))
                .doesNotContain("params:");
    }
}
