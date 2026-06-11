package io.tesseraql.core.tenant;

import java.util.Map;
import java.util.Objects;

/**
 * The tenant a request, job, or telemetry span is executing on behalf of (design ch. 30).
 *
 * <p>Resolved once at the route head and propagated through the execution context so that 2-way SQL
 * can bind {@code tenant.id} (and {@code tenant.attributes.<name>}) for shared-schema isolation.
 * The component is named {@code id} so the source expression {@code tenant.id} resolves directly.
 *
 * @param id         the tenant identifier (never null or blank)
 * @param attributes additional tenant metadata (for example region), exposed as
 *                   {@code tenant.attributes.<name>}
 */
public record TenantContext(String id, Map<String, String> attributes) {

    public TenantContext {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("tenant id must not be blank");
        }
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    /** A tenant with no additional attributes. */
    public static TenantContext of(String id) {
        return new TenantContext(id, Map.of());
    }
}
