package io.tesseraql.operations.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The catalog of installed apps, persisted as {@code catalog.json} under the install root
 * (design ch. 32.5), keyed by the application's name.
 *
 * <p>The name is the stack's contract — it is what the deployment addresses, entitles and grants
 * against (docs/stack-architecture.md Decision 23) — so {@link #register} refuses to replace an
 * entry that already holds it rather than silently swapping one team's application for another's.
 * Replacement is an explicit act: {@link #replace} is what an upgrade calls, after its preflight
 * has established that the newcomer is a newer version of the same application.
 */
public final class AppCatalog {

    private static final TqlErrorCode CATALOG_ERROR = new TqlErrorCode(TqlDomain.APP, 5001);

    /** TQL-APP-4213: the catalogue already holds an application with this name. */
    private static final TqlErrorCode NAME_TAKEN = new TqlErrorCode(TqlDomain.APP, 4213);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path catalogFile;
    private final Map<String, InstalledApp> apps = new LinkedHashMap<>();

    public AppCatalog(Path installRoot) {
        this.catalogFile = installRoot.resolve("catalog.json");
        load();
    }

    private void load() {
        if (!Files.isRegularFile(catalogFile)) {
            return;
        }
        try {
            InstalledApp[] loaded = MAPPER.readValue(Files.readAllBytes(catalogFile),
                    InstalledApp[].class);
            for (InstalledApp app : loaded) {
                apps.put(app.name(), app);
            }
        } catch (IOException ex) {
            // The cause is worth carrying: an entry keyed "id" (the pre-rename format) fails
            // construction with a message naming the rename, and this is where it surfaces.
            throw new TqlException(CATALOG_ERROR,
                    "Failed to read catalog: " + catalogFile + " (" + ex.getMessage() + ")");
        }
    }

    /**
     * Adds the catalog entry for {@code app}, persisting the catalog.
     *
     * <p>A name the catalogue already holds is refused, not replaced: two applications declaring
     * one name is a collision between what two teams shipped, and the second install silently
     * winning was how a stack lost an application without anyone deleting it. Registering an entry
     * identical to the existing one is a no-op, so re-installing the same version stays idempotent.
     * An upgrade — the same application, moving versions — replaces via {@link #replace}.
     */
    public synchronized void register(InstalledApp app) {
        InstalledApp existing = apps.get(app.name());
        if (existing != null) {
            if (existing.equals(app)) {
                return;
            }
            throw new TqlException(NAME_TAKEN, "The catalogue already holds '" + app.name()
                    + "' (v" + existing.version() + " at " + existing.path() + "). The name is the"
                    + " stack's contract, so a second application does not take it by installing;"
                    + " upgrades of the same application replace explicitly.");
        }
        apps.put(app.name(), app);
        persist();
    }

    /** Adds or replaces the catalog entry for {@code app} (upgrade, promote, rollback). */
    public synchronized void replace(InstalledApp app) {
        apps.put(app.name(), app);
        persist();
    }

    public synchronized List<InstalledApp> list() {
        return List.copyOf(apps.values());
    }

    public synchronized Optional<InstalledApp> find(String name) {
        return Optional.ofNullable(apps.get(name));
    }

    /** Whether {@code tenantId} is entitled to use the app, false if the app is unknown. */
    public synchronized boolean isEntitled(String name, String tenantId) {
        InstalledApp app = apps.get(name);
        return app != null && app.isEntitled(tenantId);
    }

    private void persist() {
        try {
            if (catalogFile.getParent() != null) {
                Files.createDirectories(catalogFile.getParent());
            }
            Files.write(catalogFile, MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(List.copyOf(apps.values())));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
