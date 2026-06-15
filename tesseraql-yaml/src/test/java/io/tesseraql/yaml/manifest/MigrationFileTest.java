package io.tesseraql.yaml.manifest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MigrationFileTest {

    private static Path path(String name) {
        return Path.of("/app/db/migration", name);
    }

    @Test
    void parsesAVersionedMigrationFilename() {
        MigrationFile migration = MigrationFile.parse("main", null, path("V1__create_items.sql"));

        assertThat(migration).isNotNull();
        assertThat(migration.datasource()).isEqualTo("main");
        assertThat(migration.vendor()).isNull();
        assertThat(migration.version()).isEqualTo("1");
        assertThat(migration.description()).isEqualTo("create_items");
        assertThat(migration.path()).isEqualTo(path("V1__create_items.sql"));
    }

    @Test
    void parsesADottedVersionAndCarriesTheVendor() {
        MigrationFile migration = MigrationFile.parse("orders", "postgresql",
                path("V2.1__add_index.sql"));

        assertThat(migration.version()).isEqualTo("2.1");
        assertThat(migration.description()).isEqualTo("add_index");
        assertThat(migration.vendor()).isEqualTo("postgresql");
    }

    @Test
    void parsesARepeatableMigrationWithNoVersion() {
        MigrationFile migration = MigrationFile.parse("main", null, path("R__refresh_view.sql"));

        assertThat(migration.version()).isNull();
        assertThat(migration.description()).isEqualTo("refresh_view");
    }

    @Test
    void returnsNullForFilesThatAreNotMigrations() {
        assertThat(MigrationFile.parse("main", null, path("readme.md"))).isNull();
        assertThat(MigrationFile.parse("main", null, path("schema.sql"))).isNull();
        // A single underscore is not the Flyway separator.
        assertThat(MigrationFile.parse("main", null, path("V1_create.sql"))).isNull();
    }

    @Test
    void naturalOrderGroupsByDatasourceThenVendorThenNumericVersion() {
        MigrationFile mainV2 = new MigrationFile("main", null, "2", "b", path("V2__b.sql"));
        MigrationFile mainV10 = new MigrationFile("main", null, "10", "j", path("V10__j.sql"));
        MigrationFile mainRepeatable = new MigrationFile("main", null, null, "r", path("R__r.sql"));
        MigrationFile mainPgV1 = new MigrationFile("main", "postgresql", "1", "p",
                path("V1__p.sql"));
        MigrationFile ordersV1 = new MigrationFile("orders", null, "1", "o", path("V1__o.sql"));

        List<MigrationFile> shuffled = new ArrayList<>(
                List.of(ordersV1, mainRepeatable, mainV10, mainPgV1, mainV2));
        shuffled.sort(null);

        // main before orders; within main the common set before the postgresql overlay; within the
        // common set V2 before V10 (numeric, not lexical) and the repeatable last.
        assertThat(shuffled).containsExactly(
                mainV2, mainV10, mainRepeatable, mainPgV1, ordersV1);
    }
}
