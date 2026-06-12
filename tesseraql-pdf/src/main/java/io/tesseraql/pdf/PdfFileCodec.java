package io.tesseraql.pdf;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.files.ColumnMapping;
import io.tesseraql.core.files.ColumnValues;
import io.tesseraql.core.files.FileCodec;
import io.tesseraql.core.files.FileReadSpec;
import io.tesseraql.core.files.FileWriteSpec;
import io.tesseraql.core.files.RowHandler;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The optional printable-documents codec (roadmap Phase 21): {@code format: pdf} on
 * {@code query-export}/{@code file-export}. An export with a template renders it through the
 * standard template engine - an app-authored XHTML file with page-oriented CSS, colocated with
 * the route - against {@code rows} (values formatted per the column mappings), {@code columns},
 * and {@code fontFamilies}; without a template the built-in grid lays the rows out as a plain
 * table. Fonts under the app home's {@code fonts/} directory embed automatically, CJK included,
 * and the output is normalized to be byte-identical for identical data (design ch. 48).
 *
 * <p>The HTML-to-PDF engine is a deployment choice ({@code tesseraql.pdf.engine}); see
 * {@link PdfEngine}. PDF is an output-only format: {@code file-import} rejects it.
 */
public final class PdfFileCodec implements FileCodec {

    static final TqlErrorCode IMPORT_UNSUPPORTED = new TqlErrorCode(TqlDomain.LD, 2830);
    static final TqlErrorCode RENDER_FAILED = new TqlErrorCode(TqlDomain.LD, 2831);
    static final TqlErrorCode OUTSIDE_ROOT = new TqlErrorCode(TqlDomain.LD, 2832);

    @Override
    public String format() {
        return "pdf";
    }

    @Override
    public String contentType() {
        return "application/pdf";
    }

    @Override
    public String extension() {
        return ".pdf";
    }

    @Override
    public void read(InputStream in, FileReadSpec spec, RowHandler handler) {
        throw new TqlException(IMPORT_UNSUPPORTED,
                "PDF is an output-only format - file-import cannot read it");
    }

    @Override
    public void write(OutputStream out, FileWriteSpec spec, Iterator<Map<String, Object>> rows)
            throws IOException {
        Locale locale = ColumnValues.locale(spec.locale());
        ZoneId zone = ColumnValues.zone(spec.timezone());
        List<ColumnMapping> columns = new ArrayList<>(spec.columns());
        List<Map<String, Object>> data = new ArrayList<>();
        while (rows.hasNext()) {
            Map<String, Object> row = rows.next();
            if (columns.isEmpty()) {
                row.keySet().forEach(key -> columns.add(ColumnMapping.of(key)));
            }
            Map<String, Object> formatted = new LinkedHashMap<>();
            for (ColumnMapping column : columns) {
                formatted.put(column.name(),
                        ColumnValues.format(column, row.get(column.name()), locale, zone));
            }
            data.add(formatted);
        }
        List<PdfSource.PdfFont> fonts = PdfFonts.scan(spec.resources());
        String xhtml = render(spec, model(columns, data, fonts));
        ByteArrayOutputStream rendered = new ByteArrayOutputStream();
        try {
            PdfEngines.selected().render(new PdfSource(xhtml, spec.resources(), fonts), rendered);
        } catch (TqlException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new TqlException(RENDER_FAILED, "PDF rendering failed"
                    + (spec.template() == null
                            ? ""
                            : " for template '" + spec.template().getFileName() + "'")
                    + ": " + ex.getMessage());
        }
        out.write(DeterministicPdf.normalize(rendered.toByteArray()));
    }

    private static Map<String, Object> model(List<ColumnMapping> columns,
            List<Map<String, Object>> data, List<PdfSource.PdfFont> fonts) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("rows", data);
        model.put("columns", columns.stream()
                .map(column -> Map.of("name", column.name(), "header", column.effectiveHeader()))
                .toList());
        model.put("fontFamilies", fonts.isEmpty()
                ? null
                : fonts.stream()
                        .map(PdfSource.PdfFont::family)
                        .distinct()
                        .map(family -> "'" + family + "'")
                        .collect(Collectors.joining(", ")));
        return model;
    }

    /** The template rendered against the model, or the built-in grid without one. */
    private static String render(FileWriteSpec spec, Map<String, Object> model) {
        if (spec.template() == null) {
            return PdfTemplates.renderGrid(model);
        }
        Path template = spec.template().toAbsolutePath().normalize();
        if (!Files.isRegularFile(template)) {
            throw new TqlException(RENDER_FAILED,
                    "PDF template '" + spec.template() + "' is not a file");
        }
        Path root = spec.resources() == null
                ? template.getParent()
                : spec.resources().toAbsolutePath().normalize();
        if (!template.startsWith(root)) {
            throw new TqlException(OUTSIDE_ROOT, "PDF template '" + template
                    + "' is outside the app resource root '" + root + "'");
        }
        return PdfTemplates.render(root,
                root.relativize(template).toString().replace('\\', '/'), model);
    }
}
