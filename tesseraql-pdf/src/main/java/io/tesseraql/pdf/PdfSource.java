package io.tesseraql.pdf;

import java.nio.file.Path;
import java.util.List;

/**
 * What a {@link PdfEngine} renders: the XHTML document (well-formed XML - templates are
 * app-authored, never payload), the resource root every relative reference must stay inside
 * (the app home; null = no file resources resolvable), and the fonts to register, in
 * deterministic order.
 */
public record PdfSource(String xhtml, Path resources, List<PdfFont> fonts) {

    public PdfSource {
        fonts = fonts == null ? List.of() : List.copyOf(fonts);
    }

    /** A font file to embed, registered under its family name. */
    public record PdfFont(Path file, String family) {
    }
}
