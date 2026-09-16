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
 *     type: header               # header | claim | host
 *     source: X-Tenant-Id        # header name, principal path, or {tenant}.example.com
 * </pre>
 *
 * @param enabled  whether tenant resolution is active
 * @param mode     the isolation mode (only {@code shared-schema}/{@code single} are wired so far)
 * @param resolver where the tenant id is read from
 * @param source   the header name (header mode), {@code principal.*} path (claim mode), or
 *                 the host pattern such as {@code {tenant}.example.com} (host mode)
 * @param required whether a missing tenant is rejected (deny-by-default)
 */
public record TenancySettings(
        boolean enabled, String mode, ResolverType resolver, String source, boolean required) {

    /** How the tenant id is located on an inbound request. */
    public enum ResolverType {
        HEADER, CLAIM, HOST
    }

    private static final TenancySettings DISABLED = new TenancySettings(false, "single",
            ResolverType.HEADER, "X-Tenant-Id", false);

    /** TQL-TENANT-4032: the tenancy block names a resolver type (or mode) outside the vocabulary. */
    private static final io.tesseraql.core.error.TqlErrorCode INVALID_RESOLVER_TYPE = new io.tesseraql.core.error.TqlErrorCode(
            io.tesseraql.core.error.TqlDomain.TENANT, 4032);

    /** Reads tenancy settings from config, returning a disabled instance when not configured. */
    public static TenancySettings from(AppConfig config) {
        boolean enabled = config.getString("tenancy.enabled").map(Boolean::parseBoolean)
                .orElse(false);
        if (!enabled) {
            return DISABLED;
        }
        String mode = config.getString("tenancy.mode").orElse("shared-schema");
        String type = config.getString("tenancy.resolver.type").orElse("header");
        // Three names, not "anything else is a header": `claims` or `Header ` read as a header
        // resolver and every request resolved (or was refused) by the wrong source, silently
        // (docs/audit-low-leads.md G25). Fail at load, as the host pattern below does.
        ResolverType resolver = switch (type) {
            case "header" -> ResolverType.HEADER;
            case "claim" -> ResolverType.CLAIM;
            case "host" -> ResolverType.HOST;
            default -> throw new io.tesseraql.core.error.TqlException(INVALID_RESOLVER_TYPE,
                    "tenancy.resolver.type '" + type + "' is not a resolver; use header, claim"
                            + " or host");
        };
        String source = config.getString("tenancy.resolver.source")
                .orElse(resolver == ResolverType.HEADER ? "X-Tenant-Id" : "tenantId");
        if (resolver == ResolverType.HOST && !source.startsWith("{tenant}.")) {
            // Fail at load, not per request: a host pattern without the tenant label can
            // never resolve anything (docs/multi-tenancy.md).
            throw new IllegalStateException("tenancy.resolver.source must be a host pattern"
                    + " of the form {tenant}.example.com, got '" + source + "'");
        }
        boolean required = config.getBoolean("tenancy.required", true);
        return new TenancySettings(true, mode, resolver, source, required);
    }
}
