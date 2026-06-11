package io.tesseraql.operations.app;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * A record of an app installed from a {@code .tqlapp} package (design ch. 32.4, 32.5).
 *
 * @param id              the app id (from {@code tesseraql.app.name})
 * @param version         the app version (from {@code tesseraql.app.version}, or {@code 0.0.0})
 * @param path            the install directory, app-relative to the install root
 * @param entitledTenants tenants allowed to use this app; empty means all tenants (ch. 32.8)
 * @param hosts           hostnames that route to this app (from {@code tesseraql.app.hosts}, ch. 32.7)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InstalledApp(String id, String version, String path,
        List<String> entitledTenants, List<String> hosts) {

    public InstalledApp {
        entitledTenants = entitledTenants == null ? List.of() : List.copyOf(entitledTenants);
        hosts = hosts == null ? List.of() : List.copyOf(hosts);
    }

    /** An entry without declared hostnames (host-based routing disabled for this app). */
    public InstalledApp(String id, String version, String path, List<String> entitledTenants) {
        this(id, version, path, entitledTenants, List.of());
    }

    /** Whether {@code tenantId} may use this app (entitled to all, or explicitly listed). */
    public boolean isEntitled(String tenantId) {
        return entitledTenants.isEmpty() || entitledTenants.contains(tenantId);
    }
}
