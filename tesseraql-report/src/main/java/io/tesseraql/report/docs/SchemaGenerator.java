package io.tesseraql.report.docs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.scaffold.CatalogIntrospector;
import io.tesseraql.yaml.scaffold.CatalogSchema;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.TreeMap;
import javax.sql.DataSource;

/**
 * Builds the schema-layer model ({@link SchemaDoc} / {@code schema.json}, documentation portal v3) by
 * introspecting each datasource's catalog through {@link CatalogIntrospector}. The result is
 * run-dependent (it reflects a live, migrated database) and carries a {@code generatedAt}; it is the
 * schema sidecar, not the byte-stable {@code spec.json}. Errors are raised in the
 * {@link TqlDomain#REPORT} domain.
 */
public final class SchemaGenerator {

    private static final TqlErrorCode GEN_ERROR = new TqlErrorCode(TqlDomain.REPORT, 2007);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CatalogIntrospector introspector = new CatalogIntrospector();

    /**
     * Introspects each named datasource's catalog into a deterministic schema model.
     *
     * @param datasources the datasources to introspect, keyed by datasource name
     * @param generatedAt the ISO-8601 instant stamped on the model
     */
    public SchemaDoc generate(Map<String, DataSource> datasources, String generatedAt) {
        Map<String, CatalogSchema> catalogs = new TreeMap<>();
        for (Map.Entry<String, DataSource> entry : datasources.entrySet()) {
            catalogs.put(entry.getKey(), introspect(entry.getValue()));
        }
        return new SchemaDoc(SchemaDoc.SCHEMA_VERSION, generatedAt, catalogs);
    }

    private CatalogSchema introspect(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            return introspector.introspect(connection);
        } catch (SQLException ex) {
            throw new TqlException(GEN_ERROR,
                    "Failed to open a connection for schema introspection: " + ex.getMessage());
        }
    }

    /** Serializes the schema model as pretty JSON (the run-dependent sidecar, not byte-stable). */
    public String toJson(SchemaDoc schema) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(schema);
        } catch (JsonProcessingException ex) {
            throw new TqlException(GEN_ERROR,
                    "Failed to serialize schema.json: " + ex.getMessage());
        }
    }
}
