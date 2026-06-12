package io.tesseraql.yaml.scaffold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Edit-detection contract of the scaffold writer (roadmap Phase 23, design ch. 22.20):
 * regeneration is idempotent, pristine files regenerate, edited and foreign files are skipped.
 */
class ScaffoldWriterTest {

    private final ScaffoldWriter writer = new ScaffoldWriter();

    @Test
    void stampsByCommentSyntaxAndKeepsTheDoctypeFirst() {
        assertThat(ScaffoldChecksum.stamp("a/route.yml", "version: tesseraql/v1\n"))
                .startsWith("# tesseraql-scaffold-checksum: sha256:");
        assertThat(ScaffoldChecksum.stamp("a/query.sql", "select 1;\n"))
                .startsWith("-- tesseraql-scaffold-checksum: sha256:");
        assertThat(ScaffoldChecksum.stamp("a/page.html", "<!DOCTYPE html>\n<html></html>\n"))
                .startsWith("<!DOCTYPE html>\n<!-- tesseraql-scaffold-checksum: sha256:");
    }

    @Test
    void appliedFilesArePristineAndReapplyingIsIdempotent(@TempDir Path home) throws Exception {
        List<ScaffoldedFile> files = List.of(
                new ScaffoldedFile("web/items/get.yml", "version: tesseraql/v1\n"),
                new ScaffoldedFile("web/items/search.sql", "select 1;\n"));

        ScaffoldWriter.Report first = writer.apply(home, files, false);
        assertThat(first.written()).containsExactly("web/items/get.yml", "web/items/search.sql");
        assertThat(ScaffoldChecksum.status(Files.readString(home.resolve("web/items/get.yml"))))
                .isEqualTo(ScaffoldChecksum.Status.PRISTINE);

        ScaffoldWriter.Report second = writer.apply(home, files, false);
        assertThat(second.written()).isEmpty();
        assertThat(second.unchanged())
                .containsExactly("web/items/get.yml", "web/items/search.sql");
        assertThat(second.blocked()).isFalse();
    }

    @Test
    void pristineFilesRegenerateWhenTheGenerationChanges(@TempDir Path home) {
        writer.apply(home, List.of(new ScaffoldedFile("a.yml", "old: 1\n")), false);

        ScaffoldWriter.Report report = writer.apply(home,
                List.of(new ScaffoldedFile("a.yml", "new: 2\n")), false);

        assertThat(report.written()).containsExactly("a.yml");
    }

    @Test
    void editedFilesAreSkippedUntilForced(@TempDir Path home) throws Exception {
        writer.apply(home, List.of(new ScaffoldedFile("a.yml", "value: 1\n")), false);
        Path file = home.resolve("a.yml");
        Files.writeString(file, Files.readString(file) + "extra: edited-by-hand\n");
        assertThat(ScaffoldChecksum.status(Files.readString(file)))
                .isEqualTo(ScaffoldChecksum.Status.EDITED);

        ScaffoldWriter.Report skippedReport = writer.apply(home,
                List.of(new ScaffoldedFile("a.yml", "value: 2\n")), false);
        assertThat(skippedReport.skipped()).containsExactly("a.yml");
        assertThat(skippedReport.blocked()).isTrue();
        assertThat(Files.readString(file)).contains("edited-by-hand");

        ScaffoldWriter.Report forcedReport = writer.apply(home,
                List.of(new ScaffoldedFile("a.yml", "value: 2\n")), true);
        assertThat(forcedReport.written()).containsExactly("a.yml");
        assertThat(Files.readString(file)).contains("value: 2")
                .doesNotContain("edited-by-hand");
    }

    @Test
    void foreignFilesWithoutAMarkerAreNeverOverwritten(@TempDir Path home) throws Exception {
        Path file = home.resolve("a.yml");
        Files.writeString(file, "hand-written: true\n");

        ScaffoldWriter.Report report = writer.apply(home,
                List.of(new ScaffoldedFile("a.yml", "value: 1\n")), false);

        assertThat(report.skipped()).containsExactly("a.yml");
        assertThat(Files.readString(file)).isEqualTo("hand-written: true\n");
    }

    @Test
    void pathsEscapingTheAppHomeAreRejected(@TempDir Path home) {
        assertThatThrownBy(() -> writer.apply(home,
                List.of(new ScaffoldedFile("../outside.yml", "x: 1\n")), false))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-APP-5202");
    }
}
