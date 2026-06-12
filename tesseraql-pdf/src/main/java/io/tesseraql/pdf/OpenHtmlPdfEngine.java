package io.tesseraql.pdf;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.openhtmltopdf.slf4j.Slf4jLogger;
import com.openhtmltopdf.util.XRLog;
import java.io.OutputStream;

/**
 * The openhtmltopdf engine prototype (roadmap Phase 21; LGPL, license decision pending, design
 * ch. 50): full page-oriented CSS - {@code @page} size and margins, margin boxes with
 * {@code counter(page)}/{@code counter(pages)}, running headers and footers - rendering through
 * PDFBox. Fonts register under their family names so a template's {@code font-family} works for
 * CJK text, and resource resolution stays confined to the app resource root.
 */
public final class OpenHtmlPdfEngine implements PdfEngine {

    static {
        XRLog.setLoggerImpl(new Slf4jLogger());
        // Rendering is server-side; default to headless so AWT never needs a display.
        System.setProperty("java.awt.headless",
                System.getProperty("java.awt.headless", "true"));
    }

    @Override
    public String id() {
        return "openhtml";
    }

    @Override
    public void render(PdfSource source, OutputStream out) throws Exception {
        PdfRendererBuilder builder = new PdfRendererBuilder();
        for (PdfSource.PdfFont font : source.fonts()) {
            builder.useFont(font.file().toFile(), font.family());
        }
        builder.useUriResolver(new ConfinedUriResolver(source.resources()));
        builder.withHtmlContent(source.xhtml(), source.resources() == null
                ? null
                : source.resources().toUri().toString());
        builder.toStream(out);
        builder.run();
    }
}
