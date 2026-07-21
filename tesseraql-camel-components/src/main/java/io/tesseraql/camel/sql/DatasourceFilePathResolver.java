package io.tesseraql.camel.sql;

import java.util.Map;

/**
 * The datasource-aware face of {@link io.tesseraql.core.sql.FilePathResolver} (docs/duckdb.md):
 * the runtime binds one instance covering every duckdb datasource's declared file scopes, and the
 * SQL producer narrows it to its endpoint's datasource before handing it to the renderer. Kept
 * beside the producer because the datasource name is an endpoint concern the core renderer never
 * sees.
 */
public interface DatasourceFilePathResolver {

    /**
     * Resolves a file placeholder against the named datasource's declared scopes.
     *
     * @param datasource the endpoint's datasource name
     * @param channel    {@code scope} or {@code dataset}
     * @param name       the scope name or dataset parameter
     * @param suffix     the parser-validated relative path ({@code /a/b.parquet} or empty)
     * @param context    the request execution context (carries {@code tenant} and {@code params})
     * @return the absolute path to bind
     */
    String resolve(String datasource, String channel, String name, String suffix,
            Map<String, Object> context);
}
