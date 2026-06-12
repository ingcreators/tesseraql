package io.tesseraql.pdf;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfFontsTest {

    private static final String FONT = "TesseraQLSampleGothic-Regular.ttf";

    @Test
    void scanReadsTheFamilyNameFromTheFontItself(@TempDir Path appHome) throws IOException {
        install(appHome, FONT);
        Files.writeString(appHome.resolve("fonts/notes.txt"), "not a font");

        List<PdfSource.PdfFont> fonts = PdfFonts.scan(appHome);

        assertThat(fonts).hasSize(1);
        assertThat(fonts.get(0).family()).isEqualTo("TesseraQL Sample Gothic");
        assertThat(fonts.get(0).file()).isEqualTo(appHome.resolve("fonts").resolve(FONT));
    }

    @Test
    void scanOrdersByFileNameForDeterministicRegistration(@TempDir Path appHome)
            throws IOException {
        install(appHome, "b-second.ttf");
        install(appHome, "a-first.ttf");

        assertThat(PdfFonts.scan(appHome))
                .extracting(font -> font.file().getFileName().toString())
                .containsExactly("a-first.ttf", "b-second.ttf");
    }

    @Test
    void noFontsDirectoryMeansNoFonts(@TempDir Path appHome) {
        assertThat(PdfFonts.scan(appHome)).isEmpty();
        assertThat(PdfFonts.scan(null)).isEmpty();
    }

    private static void install(Path appHome, String name) throws IOException {
        Files.createDirectories(appHome.resolve("fonts"));
        try (InputStream font = PdfFontsTest.class.getResourceAsStream("/fonts/" + FONT)) {
            Files.copy(font, appHome.resolve("fonts").resolve(name));
        }
    }
}
