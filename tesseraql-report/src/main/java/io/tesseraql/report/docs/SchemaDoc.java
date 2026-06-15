package io.tesseraql.report.docs;

import io.tesseraql.yaml.scaffold.CatalogSchema;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The schema-layer model for an application (documentation portal v3): the introspected catalog of
 * each datasource the application's database holds. Like the {@link ReportDoc report overlay} it is
 * run-dependent — it reflects a live, migrated database rather than the byte-stable source — so it is
 * written as an optional sidecar ({@code schema.json}) that is never packed into the reproducible
 * {@code .tqlapp}.
 *
 * @param schemaVersion the model schema version, for forward compatibility
 * @param generatedAt   ISO-8601 instant the schema was introspected
 * @param datasources   the introspected catalog per datasource name, sorted by name
 */
public record SchemaDoc(int schemaVersion, String generatedAt,
        Map<String, CatalogSchema> datasources) {

    /** The current schema model version. */
    public static final int SCHEMA_VERSION = 1;

    public SchemaDoc {
        datasources = Collections.unmodifiableMap(new LinkedHashMap<>(datasources));
    }
}
