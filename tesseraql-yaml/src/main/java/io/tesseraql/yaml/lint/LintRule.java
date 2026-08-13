package io.tesseraql.yaml.lint;

import io.tesseraql.yaml.manifest.AppManifest;
import java.util.List;

/**
 * One rule family (docs/lint-restructure.md decision 1). A family is the unit the test suite
 * already splits on — {@code AppLinterDecisionsTest}, {@code AppLinterMessagingTest}, … — and
 * now the unit the production side splits on too.
 *
 * <p>Families are framework-internal and deliberately not an SPI: {@link AppLinter} holds the
 * registry as an explicit ordered list, so inserting a family is a reviewed diff rather than a
 * discovered classpath entry. Rules are independent — they share only what {@link LintContext}
 * memoizes — but the registry's order is the emission order of the report, and one test pins it.
 */
interface LintRule {

    /**
     * Runs the family, appending to {@code findings}.
     *
     * @param context  the run's memoized IO ({@code content}/{@code tree}/{@code sqlNodes}), the
     *                 app home, and the cross-rule state a run accumulates
     * @param manifest the loaded app — its documents and its configuration
     * @param findings the report being built, in registry order
     */
    void lint(LintContext context, AppManifest manifest, List<LintFinding> findings);
}
