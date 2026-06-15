package io.tesseraql.studio;

import io.tesseraql.yaml.scaffold.CatalogSchema;
import java.util.Map;

/**
 * The studio-side envelope for the build's {@code schema.json} (documentation portal v3 schema
 * layer), read back from the app home's reserved {@code .tesseraql/docs/} namespace. It is the
 * schema counterpart to {@link ReportOverlay}: the introspected catalog of each datasource the
 * application's database holds.
 *
 * <p>Unlike {@code ReportOverlay} (which fully mirrors a model from {@code tesseraql-report}, a
 * module Studio does not depend on), the inner catalog model is reused directly from
 * {@link CatalogSchema} in {@code tesseraql-yaml}, which Studio already depends on — so only the
 * run-dependent envelope is mirrored here. Null-tolerant so a partial or forward-compatible file
 * still deserializes; a corrupt file degrades to {@code null} in {@link DocService#schema()}.
 *
 * @param schemaVersion the model schema version
 * @param generatedAt   ISO-8601 instant the schema was introspected
 * @param datasources   the introspected catalog per datasource name
 */
public record SchemaOverlay(int schemaVersion, String generatedAt,
        Map<String, CatalogSchema> datasources) {

    public SchemaOverlay {
        datasources = datasources == null ? Map.of() : Map.copyOf(datasources);
    }

    /** The introspected catalog for one datasource, or {@code null} when none was recorded. */
    public CatalogSchema datasource(String name) {
        return name == null ? null : datasources.get(name);
    }
}
