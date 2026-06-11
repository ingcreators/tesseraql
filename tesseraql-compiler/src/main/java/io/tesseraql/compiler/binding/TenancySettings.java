package io.tesseraql.compiler.binding;

import io.tesseraql.yaml.config.AppConfig;

/**
 * Resolved multi-tenant settings from the {@code tenancy} configuration block (design ch. 30, 32).
 *
 * <p>Example:
 * <pre>
 * tenancy:
 *   enabled: true
 *   mode: shared-schema          # isolation strategy (design ch. 30.2)
 *   required: true               # reject requests without a resolvable tenant
 *   resolver:
 *     type: header               # header | claim
 *     source: X-Tenant-Id        # header name, or principal path for claim mode
 * </pre>
 *
 * @param enabled  whether tenant resolution is active
 * @param mode     the isolation mode (only {@code shared-schema}/{@code single} are wired so far)
 * @param resolver where the tenant id is read from
 * @param source   the header name (header mode) or {@code principal.*} path (claim mode)
 * @param required whether a missing tenant is rejected (deny-by-default)
 */
public record TenancySettings(
        boolean enabled, String mode, ResolverType resolver, String source, boolean required) {

    /** How the tenant id is located on an inbound request. */
    public enum ResolverType {
        HEADER,
        CLAIM
    }

    private static final TenancySettings DISABLED =
            new TenancySettings(false, "single", ResolverType.HEADER, "X-Tenant-Id", false);

    /** Reads tenancy settings from config, returning a disabled instance when not configured. */
    public static TenancySettings from(AppConfig config) {
        boolean enabled = config.getString("tenancy.enabled").map(Boolean::parseBoolean).orElse(false);
        if (!enabled) {
            return DISABLED;
        }
        String mode = config.getString("tenancy.mode").orElse("shared-schema");
        ResolverType resolver = "claim".equalsIgnoreCase(
                config.getString("tenancy.resolver.type").orElse("header"))
                ? ResolverType.CLAIM : ResolverType.HEADER;
        String source = config.getString("tenancy.resolver.source")
                .orElse(resolver == ResolverType.HEADER ? "X-Tenant-Id" : "tenantId");
        boolean required = config.getString("tenancy.required").map(Boolean::parseBoolean).orElse(true);
        return new TenancySettings(true, mode, resolver, source, required);
    }
}
