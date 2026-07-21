package io.tesseraql.runtime;

import io.tesseraql.camel.sql.DatasourceFilePathResolver;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.sql.FilePathResolver;
import io.tesseraql.core.tenant.TenantContext;
import io.tesseraql.yaml.config.AppConfig;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resolves {@code ${scope.name}/rel/path} file placeholders against the file scopes declared under
 * {@code tesseraql.datasources.<name>.duckdb.fileScopes} (docs/duckdb.md). The resolved path is
 * always root-anchored: declared root, then the request tenant when the scope partitions by
 * tenant, then the parser-validated relative suffix — and a final normalize-and-prefix check makes
 * escaping the root structurally impossible even if an earlier layer regressed.
 */
final class FileScopes implements DatasourceFilePathResolver {

    private record ResolvedScope(Path root, boolean partitionByTenant) {
    }

    private final Map<String, Map<String, ResolvedScope>> byDatasource;
    private final java.util.Set<String> remoteLakes;
    private volatile io.tesseraql.core.attachment.AttachmentStore attachmentStore;
    private volatile DatasetSpool spool;

    private FileScopes(Map<String, Map<String, ResolvedScope>> byDatasource,
            java.util.Set<String> remoteLakes) {
        this.byDatasource = byDatasource;
        this.remoteLakes = remoteLakes;
    }

    /**
     * Wires the dataset channel (docs/duckdb.md): attachments become addressable as datasets once
     * the managed attachment store and the spool exist. Left unwired, every {@code ${dataset.*}}
     * fails loudly.
     */
    void wireDatasets(io.tesseraql.core.attachment.AttachmentStore attachmentStore,
            DatasetSpool spool) {
        this.attachmentStore = attachmentStore;
        this.spool = spool;
    }

    /** Builds the resolver over every duckdb datasource's declared scopes (may be empty). */
    static FileScopes fromConfig(Path appHome, AppConfig config) {
        Map<String, Map<String, ResolvedScope>> byDatasource = new LinkedHashMap<>();
        java.util.Set<String> remoteLakes = new java.util.LinkedHashSet<>();
        Object declared = config.navigate("tesseraql.datasources");
        if (declared instanceof Map<?, ?> datasources) {
            for (Object name : datasources.keySet()) {
                String datasource = String.valueOf(name);
                if (!DuckDbDatasources.isDuckDb(config, datasource)) {
                    continue;
                }
                DuckDbDatasources.Lake lake = DuckDbDatasources.lake(config, datasource);
                if (lake != null && lake.isRemote()) {
                    remoteLakes.add(datasource);
                    byDatasource.put(datasource, Map.of());
                    continue;
                }
                Map<String, ResolvedScope> scopes = new LinkedHashMap<>();
                DuckDbDatasources.fileScopes(config, datasource)
                        .forEach((scopeName, scope) -> scopes.put(scopeName, new ResolvedScope(
                                DuckDbDatasources.resolveRoot(appHome, scope.root()),
                                scope.partitionByTenant())));
                byDatasource.put(datasource, scopes);
            }
        }
        return new FileScopes(byDatasource, remoteLakes);
    }

    /** Whether any duckdb datasource is declared (the runtime binds the resolver only then). */
    boolean anyDuckDbDatasource() {
        return !byDatasource.isEmpty();
    }

    @Override
    public String resolve(String datasource, String channel, String name, String suffix,
            Map<String, Object> context) {
        if ("dataset".equals(channel)) {
            if (remoteLakes.contains(datasource)) {
                throw new TqlException(FilePathResolver.UNSUPPORTED_CODE,
                        "A remote-lake datasource has no governed local-file surface;"
                                + " ${dataset.*} resolves on a local duckdb datasource"
                                + " (docs/duckdb.md)");
            }
            return resolveDataset(name, context);
        }
        Map<String, ResolvedScope> scopes = byDatasource.getOrDefault(datasource, Map.of());
        ResolvedScope scope = scopes.get(name);
        if (scope == null) {
            throw new TqlException(FilePathResolver.UNSUPPORTED_CODE,
                    "File scope '" + name + "' is not declared under tesseraql.datasources."
                            + datasource + ".duckdb.fileScopes");
        }
        Path path = scope.root();
        if (scope.partitionByTenant()) {
            path = path.resolve(tenantSegment(name, context));
        }
        if (!suffix.isEmpty()) {
            path = path.resolve(suffix.substring(1));
        }
        Path normalized = path.normalize();
        if (!normalized.startsWith(scope.root())) {
            throw new TqlException(FilePathResolver.UNSUPPORTED_CODE,
                    "File scope '" + name + "' resolved outside its root");
        }
        return normalized.toString();
    }

    /**
     * Resolves a dataset reference — an attachment id the caller supplied — under the caller's
     * identity: the attachment must exist, belong to the authenticated principal, and have passed
     * the scanners. Every refusal is the same neutral message, so the channel never confirms
     * whether a guessed id exists.
     */
    private String resolveDataset(String reference, Map<String, Object> context) {
        if (attachmentStore == null || spool == null) {
            throw new TqlException(FilePathResolver.UNSUPPORTED_CODE,
                    "${dataset.*} placeholders need managed attachments"
                            + " (tesseraql.attachments.mode: managed)");
        }
        String subject = context.get(
                "principal") instanceof io.tesseraql.security.Principal principal
                        ? principal.subject()
                        : null;
        io.tesseraql.core.attachment.AttachmentStore.Attachment attachment = reference == null
                || reference.isBlank() || subject == null
                        ? null
                        : attachmentStore.find(reference).orElse(null);
        if (attachment == null || !subject.equals(attachment.createdBy())
                || !"clean".equals(attachment.scanStatus())) {
            throw new TqlException(FilePathResolver.UNSUPPORTED_CODE,
                    "Dataset is not available to this caller");
        }
        return spool.localize(attachment).toString();
    }

    /** The current tenant as a single path segment; a tenant-partitioned scope requires one. */
    private static String tenantSegment(String scopeName, Map<String, Object> context) {
        Object tenant = context.get("tenant");
        String id = switch (tenant) {
            case TenantContext tenantContext -> tenantContext.id();
            case String s -> s;
            case null, default -> null;
        };
        if (id == null || id.isBlank()) {
            throw new TqlException(FilePathResolver.UNSUPPORTED_CODE,
                    "File scope '" + scopeName + "' partitions by tenant but the request has no"
                            + " tenant");
        }
        if (!id.matches("[A-Za-z0-9._-]+") || id.equals(".") || id.equals("..")) {
            throw new TqlException(FilePathResolver.UNSUPPORTED_CODE,
                    "Tenant id '" + id + "' is not a safe path segment");
        }
        return id;
    }
}
