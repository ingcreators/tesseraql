package io.tesseraql.yaml.lint;

import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.manifest.AppManifest;
import java.util.List;

/**
 * Bearer JWT configuration — algorithm and key source.
 *
 * <p>Extracted verbatim from {@code AppLinter} (docs/lint-restructure.md decision 1).
 */
final class JwtConfigRules implements LintRule {

    /** The run's memoized IO and cross-rule state, set at the top of {@link #lint}. */
    private LintContext context;

    @Override
    public void lint(LintContext context, AppManifest manifest,
            List<LintFinding> findings) {
        this.context = context;
        if (manifest.config().navigate("tesseraql.security.jwt") != null) {
            lintJwtConfig(manifest.config(), findings);
        }
    }

    void lintJwtConfig(AppConfig config, List<LintFinding> findings) {
        Object rawAlgorithm = config.navigate("tesseraql.security.jwt.algorithm");
        String algorithm = rawAlgorithm == null
                ? "HS256"
                : String.valueOf(rawAlgorithm).toUpperCase(java.util.Locale.ROOT);
        boolean secret = config.navigate("tesseraql.security.jwt.secret") != null;
        boolean publicKey = config.navigate("tesseraql.security.jwt.publicKey") != null;
        boolean jwksUri = config.navigate("tesseraql.security.jwt.jwksUri") != null;
        boolean keyMaterial = publicKey || jwksUri;
        if (!algorithm.equals("HS256") && !algorithm.equals("RS256")) {
            findings.add(new LintFinding("TQL-SEC-4043", "error", "config",
                    "Unsupported JWT algorithm '" + algorithm + "'; use HS256 or RS256"));
            return;
        }
        if (algorithm.equals("HS256") && keyMaterial) {
            findings.add(new LintFinding("TQL-SEC-4042", "error", "config",
                    "JWT algorithm HS256 declares RS256 key material (publicKey/jwksUri); an"
                            + " algorithm-confusion risk - pick one algorithm"));
        }
        if (algorithm.equals("RS256")) {
            if (secret) {
                findings.add(new LintFinding("TQL-SEC-4042", "error", "config",
                        "JWT algorithm RS256 declares an HS256 secret; an algorithm-confusion risk"
                                + " - pick one algorithm"));
            }
            if (!keyMaterial) {
                findings.add(new LintFinding("TQL-SEC-4040", "error", "config",
                        "RS256 JWT config must declare a key source (jwksUri or publicKey)"));
            } else if (publicKey && jwksUri) {
                findings.add(new LintFinding("TQL-SEC-4041", "error", "config",
                        "RS256 JWT config declares conflicting key sources; set exactly one of"
                                + " jwksUri/publicKey"));
            }
        }
    }
}
