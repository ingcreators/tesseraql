package io.tesseraql.yaml.lint;

import static io.tesseraql.yaml.lint.LintFinding.Severity.ERROR;

import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.manifest.AppManifest;
import java.util.List;
import java.util.Set;

/**
 * The tenancy block's vocabulary (docs/multi-tenancy.md): {@code tenancy.mode} is one of three
 * isolation modes and {@code tenancy.resolver.type} one of three resolvers, and a misspelling of
 * either is a build error — the {@code tesseraql.orgunit.mode} rule (TQL-SCOPE-3020) and the
 * {@code inputPolicy} rule (TQL-FIELD-2006), for the one block whose typo used to switch tenant
 * isolation off in silence (docs/audit-low-leads.md G25). The runtime refuses the same values at
 * boot (TQL-TENANT-4032); the lint says so before the deploy.
 */
final class TenancyConfigRules implements LintRule {

    private static final String INVALID_TENANCY_VOCABULARY = "TQL-TENANT-3002";

    private static final Set<String> MODES = Set.of("shared-schema", "schema-per-tenant",
            "database-per-tenant");
    private static final Set<String> RESOLVERS = Set.of("header", "claim", "host");

    @Override
    public void lint(LintContext context, AppManifest manifest, List<LintFinding> findings) {
        lintTenancyConfig(manifest.config(), findings);
    }

    void lintTenancyConfig(AppConfig config, List<LintFinding> findings) {
        if (!config.getBoolean("tenancy.enabled", false)) {
            return;
        }
        String mode = config.getString("tenancy.mode").orElse(null);
        if (mode != null && !MODES.contains(mode)) {
            findings.add(new LintFinding(INVALID_TENANCY_VOCABULARY, ERROR, "config",
                    "tenancy.mode must be 'shared-schema', 'schema-per-tenant' or"
                            + " 'database-per-tenant', not '" + mode
                            + "' — the runtime refuses to boot on it, and until 0.18.0 it read as"
                            + " no isolation at all"));
        }
        String type = config.getString("tenancy.resolver.type").orElse(null);
        if (type != null && !RESOLVERS.contains(type)) {
            findings.add(new LintFinding(INVALID_TENANCY_VOCABULARY, ERROR, "config",
                    "tenancy.resolver.type must be 'header', 'claim' or 'host', not '" + type
                            + "' — the runtime refuses to boot on it, and until 0.18.0 it read as"
                            + " a header resolver"));
        }
        if (MODES.contains(mode) && !"shared-schema".equals(mode)
                && config.navigate("tenancy.datasources") == null) {
            findings.add(new LintFinding(INVALID_TENANCY_VOCABULARY, ERROR, "config",
                    "tenancy.mode '" + mode + "' isolates tenants by pool but"
                            + " tenancy.datasources declares none — the runtime refuses to boot,"
                            + " since every tenant would be refused (TQL-TENANT-4031)"));
        }
    }
}
