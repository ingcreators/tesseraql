package io.tesseraql.yaml.apps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClasspathAppSourceTest {

    @TempDir
    Path work;

    @Test
    void extractsIndexedFilesIntoWorkDirectory() throws Exception {
        ClasspathAppSource source = new ClasspathAppSource(
                "test-app", "apps/test-app", getClass().getClassLoader());

        Path root = source.materialize(work);

        assertThat(root).isEqualTo(work.resolve("test-app"));
        assertThat(Files.readString(root.resolve("web/ping/get.yml"))).contains("ping.get");
        assertThat(Files.readString(root.resolve("web/ping/ping.sql"))).contains("select");
        // Re-materializing overwrites cleanly (idempotent boot).
        assertThat(source.materialize(work)).isEqualTo(root);
    }

    @Test
    void missingIndexFails() {
        ClasspathAppSource source = new ClasspathAppSource(
                "ghost", "apps/no-such-app", getClass().getClassLoader());

        assertThatThrownBy(() -> source.materialize(work))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("not found on classpath");
    }

    @Test
    void traversalEntryIsRejected() {
        ClasspathAppSource source = new ClasspathAppSource(
                "evil-app", "apps/evil-app", getClass().getClassLoader());

        assertThatThrownBy(() -> source.materialize(work))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("escapes the app root");
    }
}
