package io.tesseraql.pdf;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * The plain Apache PDFBox engine prototype (roadmap Phase 21; Apache-2.0, license decision
 * pending, design ch. 50). It lays out a documented XHTML subset itself instead of interpreting
 * CSS: {@code h1}-{@code h3}, {@code p}/{@code div} paragraphs, and {@code table} (the header
 * row repeating after every page break); a {@code header} element repeats at the top of every
 * page, a {@code footer} element at the bottom left, and "Page n / total" always prints at the
 * bottom center. Pages are fixed A4 portrait. The first app font embeds (subset) and renders all
 * text - CJK included; without app fonts, Helvetica carries ASCII and unmappable characters
 * print as {@code ?}.
 */
public final class PdfBoxPdfEngine implements PdfEngine {

    private static final float PAGE_MARGIN_X = mm(15);
    private static final float PAGE_MARGIN_Y = mm(20);
    private static final float BODY_SIZE = 9.5f;
    private static final float BAND_SIZE = 8f;
    private static final float CELL_PADDING = 3f;
    private static final float LINE_FACTOR = 1.4f;

    @Override
    public String id() {
        return "pdfbox";
    }

    @Override
    public void render(PdfSource source, OutputStream out) throws Exception {
        Document dom = parse(source.xhtml());
        try (PDDocument document = new PDDocument()) {
            PDFont font = font(document, source.fonts());
            new Renderer(document, font, dom).run();
            document.save(out);
        }
    }

    /** Well-formed XML only, secured: no doctype, no external entities, no network. */
    private static Document parse(String xhtml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(xhtml.getBytes(StandardCharsets.UTF_8)));
    }

    private static PDFont font(PDDocument document, List<PdfSource.PdfFont> fonts)
            throws IOException {
        if (fonts.isEmpty()) {
            return new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        }
        Path file = fonts.get(0).file();
        return PDType0Font.load(document, file.toFile());
    }

    private static float mm(double value) {
        return (float) (value * 72 / 25.4);
    }

    /** Single-column top-down layout with repeated page bands and a final page-number pass. */
    private static final class Renderer {

        private final PDDocument document;
        private final PDFont font;
        private final Document dom;
        private final List<String> headerLines = new ArrayList<>();
        private final List<String> footerLines = new ArrayList<>();
        private final float contentWidth = PDRectangle.A4.getWidth() - 2 * PAGE_MARGIN_X;

        private PDPageContentStream stream;
        private float y;

        Renderer(PDDocument document, PDFont font, Document dom) {
            this.document = document;
            this.font = font;
            this.dom = dom;
        }

        void run() throws IOException {
            Element body = single(dom.getDocumentElement(), "body");
            collectBands(body);
            newPage();
            renderChildren(body == null ? dom.getDocumentElement() : body);
            stream.close();
            numberPages();
        }

        private void collectBands(Element body) {
            Element header = single(body, "header");
            if (header != null) {
                headerLines.addAll(wrap(text(header), BAND_SIZE, contentWidth));
            }
            Element footer = single(body, "footer");
            if (footer != null) {
                footerLines.addAll(wrap(text(footer), BAND_SIZE, contentWidth));
            }
        }

        private void renderChildren(Element parent) throws IOException {
            NodeList children = parent.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                if (!(children.item(i) instanceof Element element)) {
                    continue;
                }
                switch (element.getTagName()) {
                    case "h1" -> paragraph(text(element), 14f);
                    case "h2" -> paragraph(text(element), 12f);
                    case "h3" -> paragraph(text(element), 10.5f);
                    case "p", "div" -> paragraph(text(element), BODY_SIZE);
                    case "table" -> table(element);
                    case "header", "footer", "style", "script" -> {
                    }
                    default -> renderChildren(element);
                }
            }
        }

        private void paragraph(String content, float size) throws IOException {
            for (String line : wrap(content, size, contentWidth)) {
                float lineHeight = size * LINE_FACTOR;
                ensureRoom(lineHeight);
                y -= lineHeight;
                showText(line, size, PAGE_MARGIN_X, y);
            }
            y -= size * 0.6f;
        }

        private void table(Element table) throws IOException {
            List<Element> rows = descendants(table, "tr");
            if (rows.isEmpty()) {
                return;
            }
            List<String> headerCells = cells(rows.get(0), true);
            boolean hasHeader = !headerCells.isEmpty();
            int columns = Math.max(1, hasHeader
                    ? headerCells.size()
                    : cells(rows.get(0), false).size());
            float cellWidth = contentWidth / columns;
            boolean first = true;
            for (Element row : rows) {
                List<String> values = cells(row, false);
                boolean isHeaderRow = hasHeader && first;
                if (values.isEmpty() && !isHeaderRow) {
                    continue;
                }
                List<String> content = isHeaderRow ? headerCells : values;
                if (tableRow(content, columns, cellWidth, isHeaderRow) && hasHeader
                        && !isHeaderRow) {
                    // The row moved to a fresh page: repeat the table header above it.
                    tableRow(headerCells, columns, cellWidth, true);
                    tableRow(content, columns, cellWidth, false);
                }
                first = false;
            }
            y -= BODY_SIZE * 0.6f;
        }

        /**
         * Draws one row; returns true when it did not fit and only opened a fresh page - the
         * caller then re-draws the repeated header and the row itself.
         */
        private boolean tableRow(List<String> values, int columns, float cellWidth,
                boolean header) throws IOException {
            float lineHeight = BODY_SIZE * LINE_FACTOR;
            List<List<String>> wrapped = new ArrayList<>();
            int lines = 1;
            for (int i = 0; i < columns; i++) {
                List<String> cell = wrap(i < values.size() ? values.get(i) : "", BODY_SIZE,
                        cellWidth - 2 * CELL_PADDING);
                wrapped.add(cell);
                lines = Math.max(lines, cell.size());
            }
            float rowHeight = lines * lineHeight + 2 * CELL_PADDING;
            if (!fits(rowHeight)) {
                newPage();
                return true;
            }
            float top = y;
            y -= rowHeight;
            for (int i = 0; i < columns; i++) {
                float x = PAGE_MARGIN_X + i * cellWidth;
                if (header) {
                    stream.setNonStrokingColor(0.93f);
                    stream.addRect(x, y, cellWidth, rowHeight);
                    stream.fill();
                    stream.setNonStrokingColor(0f);
                }
                stream.setStrokingColor(0.27f);
                stream.setLineWidth(0.5f);
                stream.addRect(x, y, cellWidth, rowHeight);
                stream.stroke();
                float textY = top - CELL_PADDING - BODY_SIZE;
                for (String line : wrapped.get(i)) {
                    showText(line, BODY_SIZE, x + CELL_PADDING, textY);
                    textY -= lineHeight;
                }
            }
            return false;
        }

        private void ensureRoom(float height) throws IOException {
            if (!fits(height)) {
                newPage();
            }
        }

        private boolean fits(float height) {
            return y - height >= PAGE_MARGIN_Y + bandHeight(footerLines.size() + 1);
        }

        private void newPage() throws IOException {
            if (stream != null) {
                stream.close();
            }
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            stream = new PDPageContentStream(document, page);
            y = PDRectangle.A4.getHeight() - PAGE_MARGIN_Y;
            for (String line : headerLines) {
                y -= BAND_SIZE * LINE_FACTOR;
                showText(line, BAND_SIZE, PAGE_MARGIN_X, y);
            }
            if (!headerLines.isEmpty()) {
                y -= BAND_SIZE;
            }
        }

        /** The second pass: every page gets its footer text and "Page n / total". */
        private void numberPages() throws IOException {
            int total = document.getNumberOfPages();
            for (int index = 0; index < total; index++) {
                PDPage page = document.getPage(index);
                try (PDPageContentStream band = new PDPageContentStream(document, page,
                        PDPageContentStream.AppendMode.APPEND, true, true)) {
                    float textY = PAGE_MARGIN_Y - BAND_SIZE * LINE_FACTOR;
                    for (String line : footerLines) {
                        show(band, line, BAND_SIZE, PAGE_MARGIN_X, textY);
                        textY -= BAND_SIZE * LINE_FACTOR;
                    }
                    String number = "Page " + (index + 1) + " / " + total;
                    float width = width(number, BAND_SIZE);
                    show(band, number, BAND_SIZE,
                            (PDRectangle.A4.getWidth() - width) / 2,
                            PAGE_MARGIN_Y - BAND_SIZE * LINE_FACTOR);
                }
            }
        }

        private float bandHeight(int lines) {
            return lines * BAND_SIZE * LINE_FACTOR + BAND_SIZE;
        }

        private void showText(String line, float size, float x, float textY)
                throws IOException {
            show(stream, line, size, x, textY);
        }

        private void show(PDPageContentStream target, String line, float size, float x,
                float textY) throws IOException {
            target.beginText();
            target.setFont(font, size);
            target.newLineAtOffset(x, textY);
            target.showText(encodable(line));
            target.endText();
        }

        /** Greedy wrapping that may break anywhere - CJK text carries no spaces. */
        private List<String> wrap(String content, float size, float width) {
            List<String> lines = new ArrayList<>();
            String text = encodable(content);
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < text.length();) {
                int codePoint = text.codePointAt(i);
                String next = line + Character.toString(codePoint);
                if (!line.isEmpty() && width(next, size) > width) {
                    lines.add(line.toString());
                    line = new StringBuilder(Character.toString(codePoint));
                } else {
                    line = new StringBuilder(next);
                }
                i += Character.charCount(codePoint);
            }
            if (!line.isEmpty() || lines.isEmpty()) {
                lines.add(line.toString());
            }
            return lines;
        }

        private float width(String text, float size) {
            try {
                return font.getStringWidth(text) / 1000 * size;
            } catch (IOException | IllegalArgumentException ex) {
                return text.length() * size;
            }
        }

        /** Characters the font cannot map print as '?' instead of failing the export. */
        private String encodable(String text) {
            StringBuilder safe = new StringBuilder(text.length());
            for (int i = 0; i < text.length();) {
                int codePoint = text.codePointAt(i);
                String character = Character.toString(codePoint);
                try {
                    font.encode(character);
                    safe.append(character);
                } catch (IOException | IllegalArgumentException ex) {
                    safe.append('?');
                }
                i += Character.charCount(codePoint);
            }
            return safe.toString();
        }

        private static Element single(Element parent, String tag) {
            if (parent == null) {
                return null;
            }
            NodeList found = parent.getElementsByTagName(tag);
            return found.getLength() == 0 ? null : (Element) found.item(0);
        }

        private static List<Element> descendants(Element parent, String tag) {
            NodeList found = parent.getElementsByTagName(tag);
            List<Element> elements = new ArrayList<>();
            for (int i = 0; i < found.getLength(); i++) {
                elements.add((Element) found.item(i));
            }
            return elements;
        }

        /** The row's cells; {@code headerOnly} selects {@code th} cells, otherwise {@code td}. */
        private static List<String> cells(Element row, boolean headerOnly) {
            List<String> values = new ArrayList<>();
            NodeList children = row.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                if (children.item(i) instanceof Element cell
                        && cell.getTagName().equals(headerOnly ? "th" : "td")) {
                    values.add(text(cell));
                }
            }
            return values;
        }

        private static String text(Node node) {
            String content = node.getTextContent();
            return content == null ? "" : content.strip().replaceAll("\\s+", " ");
        }
    }
}
