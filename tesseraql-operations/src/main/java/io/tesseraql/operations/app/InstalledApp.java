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
 * @param basePath        the prefix this app is addressed under and serves at, or {@code null} for
 *                        the {@code /apps/<id>} default (docs/suite-architecture.md Decision 12).
 *                        A suite of one may declare {@code /} and answer at the origin root, which
 *                        is the single-application shape without a second mechanism for it.
 *                        <b>Absent means the default; present means an address</b> — {@code ""} is
 *                        the origin root, not a second spelling of absent (see {@code normalize})
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InstalledApp(String id, String version, String path,
        List<String> entitledTenants, String basePath) {

    public InstalledApp {
        entitledTenants = entitledTenants == null ? List.of() : List.copyOf(entitledTenants);
        basePath = normalize(basePath, id);
    }

    /** An entry taking the default address, {@code /apps/<id>}. */
    public InstalledApp(String id, String version, String path, List<String> entitledTenants) {
        this(id, version, path, entitledTenants, null);
    }

    /**
     * The prefix, as a leading-slash, no-trailing-slash string — {@code ""} for the origin root, so
     * that concatenating it with a route path is always well-formed
     * ({@code io.tesseraql.core.http.BasePaths} holds the same rule for an application's own view).
     *
     * <p><b>Only {@code null} is "not declared".</b> A blank value is the origin root, because this
     * function has to be idempotent: the catalogue is JSON on disk, an entry is normalised on the
     * way in and written back out in that same normalised form, and reading {@code ""} as absent
     * made the round trip lossy. Measured — an entry declaring {@code /} was stored as
     * {@code "basePath": ""} and came back as {@code /apps/<id>}, so a suite of one silently
     * reacquired the prefix it had declared away, one {@code AppCatalog.register} later.
     */
    private static String normalize(String declared, String id) {
        if (declared == null) {
            return "/apps/" + id;
        }
        String trimmed = declared.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        if (!trimmed.startsWith("/")) {
            trimmed = "/" + trimmed;
        }
        while (trimmed.length() > 1 && trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return "/".equals(trimmed) ? "" : trimmed;
    }

    /** Whether {@code tenantId} may use this app (entitled to all, or explicitly listed). */
    public boolean isEntitled(String tenantId) {
        return entitledTenants.isEmpty() || entitledTenants.contains(tenantId);
    }
}
