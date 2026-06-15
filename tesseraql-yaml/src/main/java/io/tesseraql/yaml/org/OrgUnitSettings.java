package io.tesseraql.yaml.org;

import io.tesseraql.yaml.config.AppConfig;

/**
 * Organizational-unit configuration (roadmap Phase 29 slice 2), read from
 * {@code tesseraql.orgunit.*}. It expresses the managed/app duality, mirroring the IAM realm model:
 *
 * <ul>
 *   <li>{@code mode: managed} — the runtime provisions and maintains the managed {@code tql_org_unit}
 *       / {@code tql_org_closure} hierarchy; a subtree scope filters by joining the closure.</li>
 *   <li>{@code mode: app} (default) — the application owns its own organization tables; a subtree
 *       scope is written against them with the Phase 29 scope-core directive, so nothing managed is
 *       provisioned.</li>
 * </ul>
 *
 * @param mode {@code managed} or {@code app}
 */
public record OrgUnitSettings(String mode) {

    public OrgUnitSettings {
        mode = mode == null || mode.isBlank() ? "app" : mode;
    }

    /** Whether the managed {@code tql_org_*} hierarchy is provisioned and maintained. */
    public boolean managed() {
        return "managed".equalsIgnoreCase(mode);
    }

    /** Reads the settings from config, defaulting to {@code app} mode. */
    public static OrgUnitSettings from(AppConfig config) {
        return new OrgUnitSettings(config.getString("tesseraql.orgunit.mode").orElse(null));
    }
}
