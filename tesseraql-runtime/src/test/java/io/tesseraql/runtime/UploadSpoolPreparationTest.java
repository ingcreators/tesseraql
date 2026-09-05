package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import io.tesseraql.core.error.TqlException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The boot-time preparation of the request-body spool (docs/http-edge-robustness.md decision 7).
 *
 * <p>Every url-encoded and multipart POST spools through this directory, so a runtime that
 * cannot write to it can answer no form at all — sign-in included. Creating it at boot was only
 * half the guarantee: present is not writable, and the case that produces an unwritable spool is
 * a work tree left root-owned by one {@code --user root} run.
 */
class UploadSpoolPreparationTest {

    @Test
    void aMissingSpoolDirectoryIsCreated(@TempDir Path work) {
        Path uploads = work.resolve("tmp/tesseraql/uploads");

        TesseraqlHttpServer.prepareUploadsDirectory(uploads);

        assertThat(uploads).isDirectory();
    }

    /** Preparation is idempotent: a second boot on the same work tree is the ordinary case. */
    @Test
    void anExistingWritableSpoolDirectoryIsAccepted(@TempDir Path work) throws IOException {
        Path uploads = Files.createDirectories(work.resolve("uploads"));

        assertThatCode(() -> TesseraqlHttpServer.prepareUploadsDirectory(uploads))
                .doesNotThrowAnyException();
    }

    /**
     * The probe, and the reason it is not {@code createDirectories} alone.
     *
     * <p>{@code Files.createDirectories} on an existing directory the process cannot write to
     * returns normally, so without the probe the boot succeeds and every form post afterwards
     * fails at request time with the router's untyped 500 and no line naming the directory.
     */
    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "no POSIX permissions to withdraw")
    void anUnwritableSpoolDirectoryRefusesTheBoot(@TempDir Path work) throws IOException {
        assumeNotRoot();
        Path uploads = Files.createDirectories(work.resolve("uploads"));
        Files.setPosixFilePermissions(uploads, PosixFilePermissions.fromString("r-xr-xr-x"));

        try {
            assertThatThrownBy(() -> TesseraqlHttpServer.prepareUploadsDirectory(uploads))
                    .isInstanceOf(TqlException.class)
                    .hasMessageContaining("TQL-YAML-1113")
                    .hasMessageContaining(uploads.toString())
                    .hasMessageContaining("tesseraql.app.work");
        } finally {
            Files.setPosixFilePermissions(uploads, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    /** A leaf that cannot be created because its parent is unwritable refuses the same way. */
    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "no POSIX permissions to withdraw")
    void aSpoolDirectoryUnderAnUnwritableParentRefusesTheBoot(@TempDir Path work)
            throws IOException {
        assumeNotRoot();
        Path parent = Files.createDirectories(work.resolve("tmp"));
        Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("r-xr-xr-x"));

        try {
            assertThatThrownBy(
                    () -> TesseraqlHttpServer.prepareUploadsDirectory(parent.resolve("uploads")))
                    .isInstanceOf(TqlException.class)
                    .hasMessageContaining("TQL-YAML-1113")
                    // The point of the coded refusal: the raw IOException names neither the
                    // leaf nor what it is for.
                    .hasMessageContaining("uploads")
                    .hasMessageContaining("spool");
        } finally {
            Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    /** The probe leaves nothing behind, so a boot does not accumulate marker files. */
    @Test
    void theProbeFileIsRemoved(@TempDir Path work) throws IOException {
        Path uploads = work.resolve("uploads");

        TesseraqlHttpServer.prepareUploadsDirectory(uploads);

        try (var entries = Files.list(uploads)) {
            assertThat(entries.toList()).isEmpty();
        }
    }

    /**
     * Withdrawing write permission does not withdraw it from root, so the two refusal cases
     * would create a writable directory and pass for the wrong reason.
     */
    private static void assumeNotRoot() {
        assumeFalse("root".equals(System.getProperty("user.name")),
                "root ignores the permissions these cases withdraw");
    }
}
