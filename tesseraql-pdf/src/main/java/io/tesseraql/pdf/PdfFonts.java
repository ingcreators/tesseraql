package io.tesseraql.pdf;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.apache.fontbox.ttf.OTFParser;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;

/**
 * The app's embeddable fonts (roadmap Phase 21): every {@code *.ttf}/{@code *.otf} under the app
 * home's {@code fonts/} directory, registered under the family name carried in the font's own
 * naming table - so a template's {@code font-family} simply names the font, CJK included. The
 * scan order is the file name order, deterministic across machines.
 */
public final class PdfFonts {

    static final TqlErrorCode BAD_FONT = new TqlErrorCode(TqlDomain.LD, 2834);

    private PdfFonts() {
    }

    /** The fonts under {@code <resources>/fonts}, empty when there is no such directory. */
    public static List<PdfSource.PdfFont> scan(Path resources) {
        if (resources == null) {
            return List.of();
        }
        Path dir = resources.resolve("fonts");
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<PdfSource.PdfFont> fonts = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(PdfFonts::isFontFile)
                    .sorted(Comparator.comparing(file -> file.getFileName().toString()))
                    .forEach(file -> fonts.add(new PdfSource.PdfFont(file, family(file))));
        } catch (IOException ex) {
            throw new TqlException(BAD_FONT,
                    "Cannot list the font directory '" + dir + "': " + ex.getMessage());
        }
        return List.copyOf(fonts);
    }

    private static boolean isFontFile(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return Files.isRegularFile(file) && (name.endsWith(".ttf") || name.endsWith(".otf"));
    }

    /** The font's family name from its naming table (its file stem when the table lacks one). */
    static String family(Path file) {
        boolean otf = file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".otf");
        try (TrueTypeFont font = (otf ? new OTFParser() : new TTFParser())
                .parse(new RandomAccessReadBufferedFile(file.toFile()))) {
            String family = font.getNaming() == null ? null : font.getNaming().getFontFamily();
            if (family != null && !family.isBlank()) {
                return family;
            }
            String stem = file.getFileName().toString();
            return stem.substring(0, stem.lastIndexOf('.'));
        } catch (IOException ex) {
            throw new TqlException(BAD_FONT, "Cannot read font '" + file.getFileName()
                    + "': " + ex.getMessage());
        }
    }
}
