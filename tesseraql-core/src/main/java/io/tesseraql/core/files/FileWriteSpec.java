package io.tesseraql.core.files;

import java.nio.file.Path;
import java.util.List;

/**
 * How exported rows render into a file (design ch. 28): the columns (selection, order, header
 * labels, positions, types and formats), for workbook formats the sheet name, an optional report
 * template, for placement mode the cell where data rows start, the app resource root, and the
 * locale/time zone used to render dates and numbers (null = platform defaults). Locale and zone
 * resolve per request - e.g. from the starting user's principal - so the same route serves
 * localized output.
 *
 * <p>Workbook modes: no template = a plain grid; template plus {@code startCell} = placement
 * mode (the YAML declares where each column lands, the template only carries layout and styles);
 * template without {@code startCell} = a jxls-annotated report driving its own iteration.
 *
 * <p>{@code splitBy} names a column that turns the export into one document per value, bundled as
 * a ZIP (docs/export-pipeline.md, decision 12). {@code groupBy} names a column the rows are grouped
 * by; a template reads the groups as
 * {@code groups}, each with its {@code key} and its own {@code rows}
 * (docs/export-pipeline.md, decision 3).
 *
 * <p>{@code resources} is the app home: the confinement boundary for everything a template
 * references beyond itself - stylesheets, images, and the {@code fonts/} directory the PDF codec
 * embeds (roadmap Phase 21). Codecs must never read files outside it. {@code template} arrives
 * fenced by it when a compiler built the spec (the declaration is refused at lint and boot
 * otherwise, docs/audit-low-leads.md slice 14); a codec that opens the template still refuses
 * one outside {@code resources} itself, as the PDF and Excel codecs do, because a spec may be
 * built by hand.
 *
 * <p>{@code bom} asks a codec that writes a text stream to open it with the encoding's
 * byte-order mark ({@code EF BB BF} for the CSV codec's UTF-8), so a spreadsheet that sniffs
 * the mark decodes the file as UTF-8 rather than in its system code page. It is a property
 * of the stream, not of the rows: an export with no rows still carries it, and a split export
 * carries one per entry. It is a declaration resolved before it reaches here - never derived
 * from the locale (docs/csv-import.md, decision 10, seen from the writing side) - and a codec
 * whose output is not a text stream ignores it.
 */
public record FileWriteSpec(List<ColumnMapping> columns, String sheet, Path template,
        CellRef startCell, Path resources, String locale, String timezone, String groupBy,
        String splitBy, boolean bom, DocumentMessages messages) {

    public FileWriteSpec(List<ColumnMapping> columns, String sheet, Path template,
            CellRef startCell) {
        this(columns, sheet, template, startCell, null, null, null, null, null, false, null);
    }

    public FileWriteSpec(List<ColumnMapping> columns, String sheet, Path template,
            CellRef startCell, String locale, String timezone) {
        this(columns, sheet, template, startCell, null, locale, timezone, null, null, false,
                null);
    }

    public FileWriteSpec(List<ColumnMapping> columns, String sheet, Path template,
            CellRef startCell, Path resources, String locale, String timezone) {
        this(columns, sheet, template, startCell, resources, locale, timezone, null, null,
                false, null);
    }

    /** The spec without document messages — the positional shape every caller before them wrote. */
    public FileWriteSpec(List<ColumnMapping> columns, String sheet, Path template,
            CellRef startCell, Path resources, String locale, String timezone, String groupBy,
            String splitBy, boolean bom) {
        this(columns, sheet, template, startCell, resources, locale, timezone, groupBy, splitBy,
                bom, null);
    }

    public FileWriteSpec {
        columns = columns == null ? List.of() : List.copyOf(columns);
    }

    /** Whether this export writes one document per group, bundled (docs/export-pipeline.md, decision 12). */
    public boolean splits() {
        return splitBy != null && !splitBy.isBlank();
    }

    /**
     * The template as a refusal names it: relative to {@code resources} when it lies inside
     * (the spelling the declaration used, so two routes declaring the same file name in
     * different directories stay apart), its file name when it does not or no root is known —
     * never the absolute path, which puts the host's directory layout on the execution row an
     * operator reads (docs/audit-low-leads.md slice 15, XH-12). Null without a template.
     */
    public String templateName() {
        if (template == null) {
            return null;
        }
        if (resources != null) {
            ConfinedPath root = ConfinedPath.under(resources);
            java.util.Optional<Path> inside = root.confine(template)
                    .filter(confined -> !confined.equals(root.root()));
            if (inside.isPresent()) {
                return root.root().relativize(inside.get()).toString().replace('\\', '/');
            }
        }
        Path name = template.getFileName();
        return name == null ? template.toString() : name.toString();
    }

    /** This spec with the per-request locale and time zone resolved. */
    public FileWriteSpec withFormatting(String resolvedLocale, String resolvedTimezone) {
        return new FileWriteSpec(columns, sheet, template, startCell, resources,
                resolvedLocale, resolvedTimezone, groupBy, splitBy, bom, messages);
    }

    /**
     * This spec with the message texts a print template reads through {@code #{key}}: the
     * application's catalogs over the framework's, resolved per document in the export's
     * locale (docs/printable-documents.md). Null leaves every message expression unresolved.
     */
    public FileWriteSpec withMessages(DocumentMessages resolved) {
        return new FileWriteSpec(columns, sheet, template, startCell, resources, locale,
                timezone, groupBy, splitBy, bom, resolved);
    }
}
