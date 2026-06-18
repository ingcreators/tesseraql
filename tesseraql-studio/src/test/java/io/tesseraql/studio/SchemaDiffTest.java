package io.tesseraql.studio;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.yaml.scaffold.CatalogSchema;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SchemaDiffTest {

    private static CatalogSchema.Column col(String name, int jdbc, String type, int size,
            boolean nullable, String def) {
        return new CatalogSchema.Column(name, jdbc, type, size, nullable, false, def);
    }

    private static CatalogSchema.Table table(String name, List<String> pk,
            CatalogSchema.Column... columns) {
        return new CatalogSchema.Table(name, "TABLE", "public", List.of(columns), pk, List.of(),
                List.of());
    }

    private static SchemaOverlay overlay(CatalogSchema.Table... tables) {
        return new SchemaOverlay(1, "t", Map.of("main", new CatalogSchema(List.of(tables))));
    }

    private static final CatalogSchema.Column ID = col("id", Types.BIGINT, "int8", 19, false,
            "nextval('s')");
    private static final CatalogSchema.Column NAME = col("name", Types.VARCHAR, "varchar", 100,
            true,
            null);

    @Test
    void anAddedTableBecomesACreateTableStatement() {
        SchemaOverlay baseline = overlay(table("users", List.of("id"), ID, NAME));
        SchemaOverlay current = overlay(table("users", List.of("id"), ID, NAME),
                table("orders", List.of("id"), ID));

        String ddl = SchemaDiff.generate(baseline, current);

        assertThat(ddl).contains("CREATE TABLE orders (").contains("id int8 NOT NULL")
                .contains("PRIMARY KEY (id)");
        // The sequence-backed default is dropped; the literal-free column keeps NOT NULL only.
        assertThat(ddl).doesNotContain("nextval");
    }

    @Test
    void anAddedColumnBecomesAnAlterAddColumn() {
        SchemaOverlay baseline = overlay(table("users", List.of("id"), ID, NAME));
        CatalogSchema.Column email = col("email", Types.VARCHAR, "varchar", 320, true, null);
        SchemaOverlay current = overlay(table("users", List.of("id"), ID, NAME, email));

        assertThat(SchemaDiff.generate(baseline, current))
                .isEqualTo("ALTER TABLE users ADD COLUMN email varchar(320);\n");
    }

    @Test
    void removedTablesAndColumnsAreCommentedNotDropped() {
        SchemaOverlay baseline = overlay(table("users", List.of("id"), ID, NAME),
                table("legacy", List.of("id"), ID));
        SchemaOverlay current = overlay(table("users", List.of("id"), ID));

        String ddl = SchemaDiff.generate(baseline, current);

        // Destructive changes are surfaced as comments for review, never auto-applied.
        assertThat(ddl).contains("-- DROP TABLE legacy;")
                .contains("-- ALTER TABLE users DROP COLUMN name;");
        assertThat(ddl).doesNotContain("DROP TABLE legacy;\n"); // i.e. only the commented form
    }

    @Test
    void aColumnTypeChangeIsSurfacedAsAComment() {
        SchemaOverlay baseline = overlay(table("t", List.of(),
                col("amount", Types.INTEGER, "int4", 10, true, null)));
        SchemaOverlay current = overlay(table("t", List.of(),
                col("amount", Types.BIGINT, "int8", 19, true, null)));

        assertThat(SchemaDiff.generate(baseline, current)).contains("-- ALTER TABLE t ALTER COLUMN")
                .contains("amount").contains("int4 -> int8");
    }

    @Test
    void identicalSchemasProduceNoDdl() {
        SchemaOverlay schema = overlay(table("users", List.of("id"), ID, NAME));
        assertThat(SchemaDiff.generate(schema, schema)).isEmpty();
        assertThat(SchemaDiff.generate(null, null)).isEmpty();
    }

    @Test
    void aNullBaselineMakesEveryTableACreate() {
        assertThat(SchemaDiff.generate(null, overlay(table("users", List.of("id"), ID))))
                .startsWith("CREATE TABLE users (");
    }
}
