package io.tesseraql.pdf;

import java.io.OutputStream;

/**
 * The HTML-to-PDF engine behind the pdf codec (roadmap Phase 21). The license-policy decision
 * (design ch. 50, decision point 1) adopted openhtmltopdf; this SPI remains the seam that made
 * the choice reversible - engines register through {@link java.util.ServiceLoader} and the
 * {@code tesseraql.pdf.engine} system property selects one per deployment, so a replacement
 * stack can ship as a drop-in jar without touching the codec.
 *
 * <p>Engines render the XHTML only; the codec owns template rendering, font discovery, and the
 * deterministic post-processing that keeps exports reproducibility-friendly.
 */
public interface PdfEngine {

    /** The engine key selected by the {@code tesseraql.pdf.engine} system property. */
    String id();

    /** Renders the source document to PDF bytes on {@code out}. */
    void render(PdfSource source, OutputStream out) throws Exception;
}
