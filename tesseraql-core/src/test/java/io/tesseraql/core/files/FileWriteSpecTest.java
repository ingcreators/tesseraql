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

    /**
     * {@code withFormatting} rebuilds the record field by field on every route export, so a
     * component it forgets is dropped in silence - what happened to {@code resources} once.
     */
    @Test
    void perRequestFormattingKeepsTheByteOrderMark() {
        FileWriteSpec spec = new FileWriteSpec(List.of(ColumnMapping.of("name")), null,
                Path.of("/app/web/print/print.html"), null, Path.of("/app"), null, null, null,
                null, true);

        FileWriteSpec resolved = spec.withFormatting("ja", "Asia/Tokyo");

        assertThat(resolved.bom()).as("withFormatting keeps the declared mark").isTrue();
        assertThat(resolved.locale()).isEqualTo("ja");
    }

    @Test
    void aBlankSplitByIsNotASplit() {
        // The one split predicate: the lint treats a blank splitBy: as absent, and so does the
        // writer, so the transfer must too - a blank must never record a bundle.
        assertThat(spec(null).splits()).isFalse();
        assertThat(spec("").splits()).isFalse();
        assertThat(spec(" ").splits()).isFalse();
        assertThat(spec("grp").splits()).isTrue();
    }

    private static FileWriteSpec spec(String splitBy) {
        return new FileWriteSpec(List.of(), null, null, null, null, null, null, null, splitBy,
                false);
    }

    @Test
    void compatibilityConstructorsLeaveTheMarkOff() {
        assertThat(new FileWriteSpec(List.of(), null, null, null).bom()).isFalse();
        assertThat(new FileWriteSpec(List.of(), null, null, null, "ja", "Asia/Tokyo").bom())
                .isFalse();
        assertThat(new FileWriteSpec(List.of(), null, null, null, Path.of("/app"), "ja",
                "Asia/Tokyo").bom()).isFalse();
    }
}
