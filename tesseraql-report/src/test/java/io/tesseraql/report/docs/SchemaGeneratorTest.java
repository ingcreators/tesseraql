package io.tesseraql.report.docs;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.yaml.scaffold.CatalogSchema;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit test for the schema-layer serializer (documentation portal v3): the model serializes
 * deterministically and round-trips, so {@code schema.json} is reproducible for a given catalog.
 */
class SchemaGeneratorTest {

    private final SchemaGenerator generator = new SchemaGenerator();

    @Test
    void serializesSchemaDeterministicallyWithTheExpectedShape() throws Exception {
        SchemaDoc schema = sampleSchema();

        String json = generator.toJson(schema);

        // Stable across calls for a given model (the sidecar is reproducible from DB state).
        assertThat(generator.toJson(schema)).isEqualTo(json);
        assertThat(json)
                .contains("\"schemaVersion\"")
                .contains("2026-06-15T12:00:00Z")
                .contains("\"main\"")
                .contains("\"orders\"")
                .contains("\"users\"")
                .contains("\"refTable\"");
        // Round-trips back into the model unchanged.
        SchemaDoc back = new ObjectMapper().readValue(json, SchemaDoc.class);
        assertThat(back).isEqualTo(schema);
    }

    private static SchemaDoc sampleSchema() {
        CatalogSchema.Table users = new CatalogSchema.Table("users", "TABLE", "public",
                List.of(new CatalogSchema.Column("id", Types.BIGINT, "bigint", 19, false, true,
                        null),
                        new CatalogSchema.Column("name", Types.VARCHAR, "varchar", 200, true, false,
                                null)),
                List.of("id"), List.of(), List.of());
        CatalogSchema.Table orders = new CatalogSchema.Table("orders", "TABLE", "public",
                List.of(new CatalogSchema.Column("id", Types.BIGINT, "bigint", 19, false, true,
                        null),
                        new CatalogSchema.Column("user_id", Types.BIGINT, "bigint", 19, false,
                                false,
                                null)),
                List.of("id"),
                List.of(new CatalogSchema.ForeignKey("orders_user_id_fkey", List.of("user_id"),
                        "users", List.of("id"))),
                List.of());
        CatalogSchema catalog = new CatalogSchema(List.of(orders, users));
        return new SchemaDoc(SchemaDoc.SCHEMA_VERSION, "2026-06-15T12:00:00Z",
                Map.of("main", catalog));
    }
}
