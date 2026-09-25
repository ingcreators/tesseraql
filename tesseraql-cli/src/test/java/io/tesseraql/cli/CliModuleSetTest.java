package io.tesseraql.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.core.files.ExportModel;
import io.tesseraql.core.files.FileCodec;
import io.tesseraql.core.files.FileCodecs;
import io.tesseraql.core.files.FileReadSpec;
import io.tesseraql.core.files.FileWriteSpec;
import io.tesseraql.core.files.RowHandler;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * The developer CLI's module view is the runtime's (docs/codec-discovery.md decision 4): an
 * application that declares no modules but carries a jar under {@code work/modules} — the
 * directory {@code tesseraql dev}, {@code host} and the deployment CLI all load — is read the
 * same way by {@code lint}, {@code job run} and every other verb that composes the context
 * loader. The developer CLI used to return nothing for an undeclared application on its
 * resolver branch, so {@code lint} warned {@code TQL-YAML-1408} about a format the runtime was
 * serving.
 */
class CliModuleSetTest {

    @Test
    void anUndeclaredWorkModulesJarJoinsTheContextLoader(@TempDir Path dir) throws Exception {
        Path app = app(dir);
        ClassLoader before = Thread.currentThread().getContextClassLoader();
        try {
            CliModules.installAppExtensions(app, null);

            FileCodecs codecs = FileCodecs.discover(Thread.currentThread().getContextClassLoader());
            assertThat(codecs.supports("fixedwidth")).as("the jar's codec is in the set").isTrue();
        } finally {
            release(before);
        }
    }

    @Test
    void lintReadsTheSameSetAndIsSilentOnTheFormatItServes(@TempDir Path dir) throws Exception {
        Path app = app(dir);
        ClassLoader before = Thread.currentThread().getContextClassLoader();
        PrintStream out = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            int exit = new CommandLine(new TesseraqlCli()).execute("lint", "--app",
                    app.toString(), "--format", "json");
            assertThat(exit).isZero();
            String stdout = captured.toString(StandardCharsets.UTF_8);
            assertThat(io.tesseraql.yaml.JsonMappers.constrained().readTree(stdout).get("findings"))
                    .noneMatch(finding -> "TQL-YAML-1408".equals(finding.get("code").asString()));
        } finally {
            System.setOut(out);
            release(before);
        }
    }

    /**
     * Restores the context loader and closes the one the command installed over the jar: a CLI
     * process exits and never closes it, but a test's temp directory cannot be deleted on
     * Windows while the loader holds the jar open.
     */
    private static void release(ClassLoader before) throws IOException {
        ClassLoader installed = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(before);
        if (installed != before && installed instanceof URLClassLoader loader) {
            loader.close();
        }
    }

    /**
     * An application declaring nothing, with a query-export over a format only the jar under
     * its {@code work/modules} serves — the jar carries the services entry alone, the provider
     * class sits on the test classpath (the fixture shape the runtime's module tests use).
     */
    private static Path app(Path dir) throws Exception {
        Path app = dir.resolve("app");
        Files.createDirectories(app.resolve("config"));
        Files.writeString(app.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Path route = Files.createDirectories(app.resolve("web/api/items/dump"));
        Files.writeString(route.resolve("get.yml"), """
                version: tesseraql/v1
                id: items.dump
                kind: route
                recipe: query-export
                security:
                  auth: public
                export:
                  format: fixedwidth
                sources:
                  main:
                    sql:
                      file: dump.sql
                """);
        Files.writeString(route.resolve("dump.sql"), "select 1 as id\n");
        Path modules = Files.createDirectories(app.resolve("work/modules"));
        try (ZipOutputStream zip = new ZipOutputStream(
                Files.newOutputStream(modules.resolve("fixed-width.jar")))) {
            zip.putNextEntry(new ZipEntry("META-INF/services/io.tesseraql.core.files.FileCodec"));
            zip.write((FixedWidth.class.getName() + "\n").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return app;
    }

    /** A codec by name only, registered through the jar's services entry. */
    public static final class FixedWidth implements FileCodec {

        @Override
        public String format() {
            return "fixedwidth";
        }

        @Override
        public String contentType() {
            return "text/plain";
        }

        @Override
        public String extension() {
            return ".txt";
        }

        @Override
        public void read(InputStream in, FileReadSpec spec, RowHandler handler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void write(OutputStream out, FileWriteSpec spec, ExportModel model) {
            throw new UnsupportedOperationException();
        }
    }
}
