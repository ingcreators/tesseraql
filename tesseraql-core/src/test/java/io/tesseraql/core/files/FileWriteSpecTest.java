package io.tesseraql.core.files;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class FileWriteSpecTest {

    @Test
    void perRequestFormattingKeepsTheResourceRoot() {
        FileWriteSpec spec = new FileWriteSpec(List.of(ColumnMapping.of("name")), null,
                Path.of("/app/web/print/print.html"), null, Path.of("/app"), null, null);

        FileWriteSpec resolved = spec.withFormatting("ja", "Asia/Tokyo");

        assertThat(resolved.resources()).isEqualTo(Path.of("/app"));
        assertThat(resolved.template()).isEqualTo(Path.of("/app/web/print/print.html"));
        assertThat(resolved.locale()).isEqualTo("ja");
        assertThat(resolved.timezone()).isEqualTo("Asia/Tokyo");
    }

    @Test
    void compatibilityConstructorsLeaveTheResourceRootUnset() {
        assertThat(new FileWriteSpec(List.of(), null, null, null).resources()).isNull();
        assertThat(new FileWriteSpec(List.of(), null, null, null, "ja", "Asia/Tokyo").resources())
                .isNull();
    }
}
