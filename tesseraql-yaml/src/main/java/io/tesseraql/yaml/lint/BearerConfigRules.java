package io.tesseraql.yaml.lint;

import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.model.RouteDefinition;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Bearer-token configuration for the routes that require it.
 *
 * <p>Extracted verbatim from {@code AppLinter} (docs/lint-restructure.md decision 1).
 */
final class BearerConfigRules implements LintRule {

    /** The run's memoized IO and cross-rule state, set at the top of {@link #lint}. */
    private LintContext context;

    @Override
    public void lint(LintContext context, AppManifest manifest,
            List<LintFinding> findings) {
        this.context = context;
        lintBearerConfig(context.appHome(), manifest, manifest.config(), findings);
    }

    /**
     * A bearer caller with nothing to verify its token (TQL-SEC-4047).
     *
     * <p>{@code lintJwtConfig} runs only when {@code tesseraql.security.jwt} is present, so an
     * app that declares no JWT block at all was checked by nothing. That app still mounts the
     * operations API, which authenticates every read with {@code auth: bearer} — and with no
     * authenticator bound, no credential can succeed. The runtime answers 500 and says so in
     * its message, deliberately (a 401 would invite a client into refresh retries against a
     * server where nothing could work), but nobody sees that until they call it.
     *
     * <p>Scoped to what the author <em>declared</em>. The always-mounted operations API is
     * deliberately <b>not</b> reported: it is framework-mounted rather than authored, it would
     * fire on every application that has not configured bearer auth, and a finding that appears
     * everywhere is one everybody learns to scroll past. The operator who actually calls that
     * API gets {@code TQL-SEC-4001}, which names the unbound authenticator.
     *
     * <p>A <b>warning</b>, where the api-key ({@code TQL-SEC-4044}) and mTLS
     * ({@code TQL-SEC-4060}) versions of this check are errors. The difference is real rather
     * than convenient: those blocks <em>are</em> the client registry, so a route without one is
     * definitionally unusable and the author forgot. A JWT block is verification material —
     * usually a secret, and the scaffolded form is {@code secret: ${JWT_SECRET:...}} — so it is
     * the one auth configuration that legitimately lives in a {@code config/env/} overlay. The
     * lint runs with no profile, so an error here would fail the build of an application that
     * is correctly configured for every environment it actually runs in.
     */
    void lintBearerConfig(Path appHome, AppManifest manifest, AppConfig config,
            List<LintFinding> findings) {
        if (config.navigate("tesseraql.security.jwt") != null) {
            return;
        }
        for (Map.Entry<Path, RouteDefinition> document : LintSupport.authoringDocuments(manifest)) {
            io.tesseraql.yaml.model.SecuritySpec security = document.getValue().security();
            if (security == null || !"bearer".equals(security.auth())) {
                continue;
            }
            String source = appHome.relativize(document.getKey()).toString().replace('\\', '/');
            findings.add(new LintFinding("TQL-SEC-4047", "warning", source,
                    "'" + document.getValue().id() + "' declares auth: bearer but no"
                            + " tesseraql.security.jwt is configured — no token can be verified,"
                            + " so every call fails as a server fault"));
        }
    }
}
