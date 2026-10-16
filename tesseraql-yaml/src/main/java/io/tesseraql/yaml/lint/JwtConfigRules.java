package io.tesseraql.yaml.lint;

import static io.tesseraql.yaml.lint.LintFinding.Severity.ERROR;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestLoader;
import io.tesseraql.yaml.scaffold.AppScaffolder;
import java.util.List;

/**
 * Bearer JWT configuration — algorithm and key source.
 *
 * <p>Extracted verbatim from {@code AppLinter} (docs/lint-restructure.md decision 1).
 */
final class JwtConfigRules implements LintRule {

    private static final String UNSUPPORTED_JWT_ALGORITHM = "TQL-SEC-4043";

    private static final String JWT_ALGORITHM_CONFUSION = "TQL-SEC-4042";

    private static final String JWT_KEY_SOURCE_MISSING = "TQL-SEC-4040";

    private static final String JWT_KEY_SOURCES_CONFLICT = "TQL-SEC-4041";

    private static final String JWT_AUDIENCE_MISSING = "TQL-SEC-4048";

    private static final String DEVELOPMENT_SECRET = "TQL-SEC-4154";

    private static final String SECRET = "tesseraql.security.jwt.secret";

    @Override
    public void lint(LintContext context, AppManifest manifest,
            List<LintFinding> findings) {
        if (manifest.config().navigate("tesseraql.security.jwt") != null) {
            lintJwtConfig(manifest.config(), findings);
            lintDevelopmentSecret(manifest.config(), ManifestLoader.activeProfile(), findings);
        }
    }

    /**
     * The boot's TQL-SEC-4154 before anything is deployed (docs/deployment-decisions.md S4): under
     * a named profile, a secret that is the development secret the scaffold publishes.
     *
     * <p>The boot judges the secret it resolved. The lint judges the declaration, read with
     * nothing from the environment, so its answer is the same on every machine and never depends
     * on whether the build happens to hold the deployment's secret. A profile that inherits the
     * base configuration's {@code ${JWT_SECRET:dev-only-...}} is reported even where the
     * deployment sets {@code JWT_SECRET}. It still says "the published secret, unless the
     * environment rescues it", the shape the generated profiles exist to remove. Without a
     * profile the fallback is the development loop's, and nothing is reported.
     */
    void lintDevelopmentSecret(AppConfig config, String profile, List<LintFinding> findings) {
        if (profile == null) {
            return;
        }
        String declared;
        try {
            declared = config.withoutEnvironment().getString(SECRET).orElse(null);
        } catch (TqlException unresolved) {
            // ${JWT_SECRET} with no fallback: the secret is the environment's to supply, and the
            // runtime refuses with TQL-YAML-1101 when it does not.
            return;
        }
        if (!AppScaffolder.DEVELOPMENT_JWT_SECRET.equals(declared)) {
            return;
        }
        // Written like the boot's sentence, which it sits beside in the error-code reference.
        findings.add(new LintFinding(DEVELOPMENT_SECRET, ERROR, "config",
                "tesseraql.security.jwt.secret is, or falls back to, the development secret the"
                        + " scaffold publishes, and the '" + profile + "' profile is active, so"
                        + " anyone could mint a token this application accepts and the runtime"
                        + " refuses to start on it. Declare tesseraql.security.jwt.secret in"
                        + " config/env/" + profile + ".yml as ${JWT_SECRET}, with no fallback"));
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
            findings.add(new LintFinding(UNSUPPORTED_JWT_ALGORITHM, ERROR, "config",
                    "Unsupported JWT algorithm '" + algorithm + "'; use HS256 or RS256"));
            return;
        }
        if (algorithm.equals("HS256") && keyMaterial) {
            findings.add(new LintFinding(JWT_ALGORITHM_CONFUSION, ERROR, "config",
                    "JWT algorithm HS256 declares RS256 key material (publicKey/jwksUri); an"
                            + " algorithm-confusion risk - pick one algorithm"));
        }
        // A configuration that can validate a token must say which tokens are for this application
        // (docs/audit-hardening.md Decision 1). Without it, a bearer route verifying against an
        // external IdP's JWKS accepts any token that IdP minted for any relying party — a confused
        // deputy. An error rather than a warning because a warning leaves the hole open by default,
        // which is the thing being fixed; it is the one lint in the campaign that stops an existing
        // app booting until its config gains a value, and pre-1.0 is the only time that is
        // affordable.
        // The reading is the boot's (JwtAudiences): an empty list, a blank string and a list
        // of blanks all say nothing, and used to lint clean while the boot refused two of them
        // and served the third fail-closed (docs/audit-low-leads.md XD-07i).
        if ((secret || keyMaterial)
                && io.tesseraql.yaml.app.JwtAudiences.declared(
                        config.navigate("tesseraql.security.jwt.audience")).isEmpty()) {
            findings.add(new LintFinding(JWT_AUDIENCE_MISSING, ERROR, "config",
                    "tesseraql.security.jwt is configured without an audience, so any token the"
                            + " issuer minted for any other relying party is accepted; declare"
                            + " tesseraql.security.jwt.audience with the identifier(s) this"
                            + " application answers to"));
        }
        if (algorithm.equals("RS256")) {
            if (secret) {
                findings.add(new LintFinding(JWT_ALGORITHM_CONFUSION, ERROR, "config",
                        "JWT algorithm RS256 declares an HS256 secret; an algorithm-confusion risk"
                                + " - pick one algorithm"));
            }
            if (!keyMaterial) {
                findings.add(new LintFinding(JWT_KEY_SOURCE_MISSING, ERROR, "config",
                        "RS256 JWT config must declare a key source (jwksUri or publicKey)"));
            } else if (publicKey && jwksUri) {
                findings.add(new LintFinding(JWT_KEY_SOURCES_CONFLICT, ERROR, "config",
                        "RS256 JWT config declares conflicting key sources; set exactly one of"
                                + " jwksUri/publicKey"));
            }
        }
    }
}
