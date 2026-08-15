package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the rule registry's order (docs/lint-restructure.md decision 6).
 *
 * <p>The registry's order is the report's order: it is what a reader scans top to bottom, and a
 * handful of family tests assert emission order within their own family. While the rules lived
 * in one 6,000-line method, where a family ran was wherever its call happened to sit; now it is
 * a list, and this test makes moving or inserting an entry a deliberate, reviewed edit rather
 * than a side effect of adding a method.
 *
 * <p>Adding a family is expected — add it here, in the position it runs.
 */
class LintRegistryOrderTest {

    @Test
    void theRegistryRunsTheFamiliesInThisOrder() {
        assertThat(AppLinter.rules().stream().map(rule -> rule.getClass().getSimpleName()).toList())
                .containsExactly(
                        // The document families, one loop each, in load order.
                        "RouteRules",
                        "CalendarRules",
                        "JobRules",
                        "JobChainingRules",
                        "ToolRules",
                        "ResourceRules",
                        "UiResourceRules",
                        "PromptRules",
                        "ConsumerRules",
                        "DuplicateMcpNameRules",
                        "McpReadFloorRules",
                        "ToolUiLinkRules",
                        "I18nRules",
                        // Security and shared definitions, which need every document loaded.
                        "JwtConfigRules",
                        "ApiKeyConfigRules",
                        "BearerConfigRules",
                        "AuthWithoutPolicyRules",
                        "BatchHeartbeatRules",
                        "MtlsConfigRules",
                        "OidcSamlRules",
                        "SecurityDefaultRules",
                        "FieldDomainRules",
                        "ResponseHeaderRules",
                        "AmbientPrincipalRules",
                        "ComponentPolicyRules",
                        "RuleSetRules",
                        "DecisionRules",
                        "ScopeRules",
                        // Configuration and the whole-app views over it.
                        "PreferenceRules",
                        "OrgUnitRules",
                        "WorkflowRules",
                        "AttachmentRules",
                        "MailRules",
                        "CatalogLocaleRules",
                        "ObjectStorageEgressRules",
                        "ViewRules",
                        "BasePathRules",
                        "DuckDbRules",
                        "InputRules",
                        // Last: the sweep for files no loader claims at all.
                        "UnclaimedFileRules");
    }

    @Test
    void everyRegistryEntryIsADistinctFamily() {
        List<String> families = AppLinter.rules().stream()
                .map(rule -> rule.getClass().getName()).toList();
        assertThat(families).doesNotHaveDuplicates();
    }
}
