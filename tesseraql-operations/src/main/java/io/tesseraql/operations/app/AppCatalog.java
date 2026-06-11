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
 * (design ch. 32.5). Registering an app id replaces any previous entry for that id (upgrade).
 */
public final class AppCatalog {

    private static final TqlErrorCode CATALOG_ERROR = new TqlErrorCode(TqlDomain.APP, 5001);
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
            InstalledApp[] loaded = MAPPER.readValue(Files.readAllBytes(catalogFile), InstalledApp[].class);
            for (InstalledApp app : loaded) {
                apps.put(app.id(), app);
            }
        } catch (IOException ex) {
            throw new TqlException(CATALOG_ERROR, "Failed to read catalog: " + catalogFile);
        }
    }

    /** Adds or replaces the catalog entry for {@code app}, persisting the catalog. */
    public synchronized void register(InstalledApp app) {
        apps.put(app.id(), app);
        persist();
    }

    public synchronized List<InstalledApp> list() {
        return List.copyOf(apps.values());
    }

    public synchronized Optional<InstalledApp> find(String id) {
        return Optional.ofNullable(apps.get(id));
    }

    /** Whether {@code tenantId} is entitled to use the app, false if the app is unknown. */
    public synchronized boolean isEntitled(String id, String tenantId) {
        InstalledApp app = apps.get(id);
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
