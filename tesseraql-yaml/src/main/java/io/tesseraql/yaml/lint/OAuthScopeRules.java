package io.tesseraql.yaml.lint;

import static io.tesseraql.yaml.lint.LintFinding.Severity.ERROR;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.yaml.manifest.AppManifest;
import java.util.List;

/**
 * The authorization server is the stack's, and the linter is where a misplaced declaration is
 * noticed first (docs/token-issuance.md decision 8).
 *
 * <p>{@code security.oauth.enabled} belongs in {@code tesseraql-stack.yml}: the stack file's
 * {@code security:} subtree grafts onto the stack surface runtime, which is the only
 * configuration that legitimately carries the key. An application declaring it in its own tree
 * would stand up a second issuer beside the stack's — a member meets the same refusal at boot,
 * and this rule is why that refusal is rarely met.
 */
final class OAuthScopeRules implements LintRule {

    /** TQL-OAUTH-3004: an application's own configuration enables the authorization server. */
    private static final TqlErrorCode APP_DECLARED = new TqlErrorCode(TqlDomain.OAUTH, 3004);

    @Override
    public void lint(LintContext context, AppManifest manifest, List<LintFinding> findings) {
        if (manifest.config().getString("tesseraql.security.oauth.enabled").isPresent()) {
            findings.add(new LintFinding(APP_DECLARED.toString(), ERROR, "config",
                    "tesseraql.security.oauth.enabled is declared in this application's"
                            + " configuration, but the authorization server is the stack's —"
                            + " declare security.oauth.enabled in tesseraql-stack.yml, where"
                            + " the issuer's other settings live"));
        }
    }
}
