package io.tesseraql.compiler.binding;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.core.files.ExportModel;
import io.tesseraql.core.files.FileCodec;
import io.tesseraql.core.files.FileCodecs;
import io.tesseraql.core.files.FileReadSpec;
import io.tesseraql.core.files.FileWriteSpec;
import io.tesseraql.core.files.RowHandler;
import io.tesseraql.yaml.model.RouteDefinition;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An import page's accepted file types come from the codec set the binding is handed, not from
 * one it discovers on its own (docs/codec-discovery.md decision 1): a format that lives in a
 * module — a workbook codec, an application's own — reaches the page through the same set
 * the transfer service reads with, so the file picker filters on what the upload will parse.
 */
class ViewBindingImportTargetTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A codec set holding a workbook format under the name the route declares. */
    private static final FileCodecs WITH_WORKBOOK = FileCodecs.of(new Workbook());

    /** The compiler's own classpath: the csv codec and nothing else. */
    private static final FileCodecs CLASSPATH_ONLY = FileCodecs
            .discover(ViewBindingImportTargetTest.class.getClassLoader());

    @Test
    void theAcceptListComesFromTheCodecSetTheBindingIsHanded(@TempDir Path dir)
            throws Exception {
        Object accept = bind(dir, WITH_WORKBOOK).model(Map.of(), Locale.ENGLISH).get("accept");

        assertThat(accept).isEqualTo(
                ".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    }

    /**
     * The shape this test exists to keep out: with the module's codec absent from the set the
     * page offers every file, which is what every import page over a module format rendered
     * while the binding discovered on the thread context loader.
     */
    @Test
    void aFormatAbsentFromTheSetRendersNoAcceptList(@TempDir Path dir) throws Exception {
        assertThat(CLASSPATH_ONLY.supports("excel"))
                .as("the compiler's classpath has no workbook codec")
                .isFalse();

        Object accept = bind(dir, CLASSPATH_ONLY).model(Map.of(), Locale.ENGLISH).get("accept");

        assertThat(accept).isNull();
    }

    private static ViewBinding bind(Path dir, FileCodecs codecs) throws Exception {
        Files.writeString(dir.resolve("page.view.yml"), """
                version: tesseraql/v1
                kind: view
                recipe: import
                title: Import items
                action: /items/import
                """);
        RouteDefinition action = MAPPER.convertValue(Map.of(
                "id", "items.import",
                "kind", "route",
                "recipe", "file-import",
                "import", Map.of("format", "excel", "columns", List.of("name", "qty"))),
                RouteDefinition.class);
        return ViewBinding.of(dir, "page", null, path -> action,
                id -> dir.resolve("page.view.yml"), codecs);
    }

    /** A workbook codec by name and media type only: the page never reads or writes. */
    private static final class Workbook implements FileCodec {

        @Override
        public String format() {
            return "excel";
        }

        @Override
        public String contentType() {
            return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        }

        @Override
        public String extension() {
            return ".xlsx";
        }

        @Override
        public void read(InputStream in, FileReadSpec spec, RowHandler handler) {
            throw new UnsupportedOperationException("the page does not read");
        }

        @Override
        public void write(OutputStream out, FileWriteSpec spec, ExportModel model) {
            throw new UnsupportedOperationException("the page does not write");
        }
    }
}
