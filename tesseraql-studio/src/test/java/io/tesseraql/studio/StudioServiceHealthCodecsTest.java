package io.tesseraql.studio;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.core.expr.ExpressionFunctions;
import io.tesseraql.core.files.ExportModel;
import io.tesseraql.core.files.FileCodec;
import io.tesseraql.core.files.FileCodecs;
import io.tesseraql.core.files.FileReadSpec;
import io.tesseraql.core.files.FileWriteSpec;
import io.tesseraql.core.files.RowHandler;
import io.tesseraql.yaml.lint.LintFinding;
import io.tesseraql.yaml.manifest.ManifestLoader;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The health dashboard lints against the application's own codec set
 * (docs/codec-discovery.md decision 3): a format the application serves through a module is
 * not a finding, and the same format is one when the set lacks it — which is what the
 * dashboard reported for every module format while it linted with the process default.
 */
class StudioServiceHealthCodecsTest {

    private static final String CODE = "TQL-YAML-1408";

    @Test
    void theHealthLintJudgesFormatsAgainstTheApplicationsCodecs(@TempDir Path dir)
            throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Path route = Files.createDirectories(dir.resolve("web/api/items/dump"));
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

        List<LintFinding> without = new StudioService(new ManifestLoader().load(dir), false)
                .health();
        List<LintFinding> with = new StudioService(new ManifestLoader().load(dir), false,
                ExpressionFunctions.processDefault(), FileCodecs.of(new FixedWidth())).health();

        assertThat(without).as("the class's own classpath has no such codec")
                .anyMatch(finding -> CODE.equals(finding.code()));
        assertThat(with).as("the application's set carries it")
                .noneMatch(finding -> CODE.equals(finding.code()));
    }

    /** A codec by name only: the lint asks whether it exists and never writes. */
    private static final class FixedWidth implements FileCodec {

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
