package io.tesseraql.pdf;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;

/**
 * Strips every volatile bit out of a rendered PDF (roadmap Phase 21) so exports stay
 * reproducibility-friendly (design ch. 48): the engine-stamped creation/modification dates and
 * producer string, the XMP metadata packet, and the trailer {@code /ID} (normally derived from
 * the current time) all give way to fixed values. The same rows through the same template yield
 * byte-identical output, whatever the engine.
 */
final class DeterministicPdf {

    private DeterministicPdf() {
    }

    static byte[] normalize(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            PDDocumentInformation fixed = new PDDocumentInformation();
            fixed.setProducer("TesseraQL");
            fixed.setTitle(document.getDocumentInformation().getTitle());
            document.setDocumentInformation(fixed);
            document.getDocumentCatalog().setMetadata(null);
            // A fixed id seed plus a cleared trailer /ID makes the writer's digest input - and
            // with it the rewritten document - a pure function of the content.
            document.getDocument().getTrailer().removeItem(COSName.ID);
            document.setDocumentId(0L);
            ByteArrayOutputStream out = new ByteArrayOutputStream(pdf.length);
            document.save(out);
            return out.toByteArray();
        }
    }
}
