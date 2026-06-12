package io.tesseraql.pdf;

import java.io.OutputStream;

/**
 * One HTML-to-PDF engine stack behind the pdf codec (roadmap Phase 21). The engine choice is a
 * license-policy decision (design ch. 50) still pending, so both candidates ship as prototypes
 * discovered through {@link java.util.ServiceLoader} and selected by the
 * {@code tesseraql.pdf.engine} system property: {@code openhtml} (openhtmltopdf, LGPL, full
 * page-oriented CSS) and {@code pdfbox} (plain Apache PDFBox, Apache-2.0, a documented HTML
 * subset).
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
