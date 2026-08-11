package io.tesseraql.core.files;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a codec is given to write (docs/export-pipeline.md, decision 1): the extraction's rows, plus
 * whatever else the export declared for a template to compose around them.
 *
 * <p>Before this, a codec received a bare row iterator and nothing else, and three unrelated-looking
 * problems followed from that one signature — a header-and-lines document had to denormalize its
 * header onto every line, a report template could not group without materializing, and a large
 * export was a memory question. A read route has never been so limited: it carries {@code sql:},
 * named {@code queries:} and {@code http:} sources, each published under its own key.
 *
 * <p>The row source appears twice on purpose. A codec that declared
 * {@link FileCodec#streams(FileWriteSpec)} gets {@link #rows()}, a single-pass iterator, and one
 * that did not gets {@link #repeatableRows()}, which can be walked as often as a template needs.
 * Asking for the other one fails rather than being described in prose: a streaming codec cannot be
 * handed a second pass it would have to buffer to provide, and a buffering codec asking for a
 * one-shot iterator has lost the ability to iterate twice that it was given the spool for.
 */
public final class ExportModel {

    /** TQL-LD-2856: a codec asked for the row source its streaming declaration does not match. */
    static final TqlErrorCode WRONG_ROW_SOURCE = new TqlErrorCode(TqlDomain.LD, 2856);

    private final Iterator<Map<String, Object>> single;
    private final Iterable<Map<String, Object>> repeatable;
    private final Map<String, Object> values;

    private ExportModel(Iterator<Map<String, Object>> single,
            Iterable<Map<String, Object>> repeatable, Map<String, Object> values) {
        this.single = single;
        this.repeatable = repeatable;
        this.values = Map.copyOf(values);
    }

    /** For a codec that streams: the rows arrive once, in order, and are not held. */
    public static ExportModel streaming(Iterator<Map<String, Object>> rows,
            Map<String, Object> values) {
        return new ExportModel(rows, null, values);
    }

    /** For a codec that buffers: the rows can be walked as many times as the template needs. */
    public static ExportModel repeatable(Iterable<Map<String, Object>> rows,
            Map<String, Object> values) {
        return new ExportModel(null, rows, values);
    }

    /** The single-pass row source. Fails when this export was built for a buffering codec. */
    public Iterator<Map<String, Object>> rows() {
        if (single == null) {
            throw new TqlException(WRONG_ROW_SOURCE, "This export carries a re-readable row set"
                    + " because its codec declared that it holds rows - iterate repeatableRows()");
        }
        return single;
    }

    /** The re-readable row source. Fails when this export was built for a streaming codec. */
    public Iterable<Map<String, Object>> repeatableRows() {
        if (repeatable == null) {
            throw new TqlException(WRONG_ROW_SOURCE, "This export streams its rows because its"
                    + " codec declared that it writes them through - iterate rows() once");
        }
        return repeatable;
    }

    /**
     * The extraction as a template sees it (docs/export-pipeline.md, decision 14): {@code rows}
     * and {@code rowCount}, under the key {@code sql}, exactly as a route publishes its default
     * result and exactly as a named query publishes its own. The only difference left between the
     * extraction and a named query is the one that is real — which result the export is about.
     *
     * <p>The count is answerable because a template mode is a buffering mode: its rows are
     * spooled, and a spool knows its size. A streaming codec has no template and reads neither.
     */
    public Map<String, Object> subject() {
        Iterable<Map<String, Object>> rows = repeatableRows();
        return result(rows, rows instanceof SpooledRows spooled
                ? spooled.size()
                : count(rows));
    }

    /**
     * A result as every consumer of one is shaped: the rows, how many there are, and the first of
     * them.
     *
     * <p>{@code first} is not a convenience. A spooled result is read in sequence and cannot be
     * indexed, so the {@code rows[0]} a single-row header query used to be read with no longer
     * resolves — {@code ${header.first.customer}} replaces {@code ${header.rows[0].customer}}, and
     * says what it means besides. Indexing further into a result is not available; a template that
     * wants the third row wants a query that returns it.
     */
    public static Map<String, Object> result(Iterable<Map<String, Object>> rows, long rowCount) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", rows);
        result.put("rowCount", rowCount);
        Iterator<Map<String, Object>> first = rows.iterator();
        result.put("first", first.hasNext() ? first.next() : null);
        return result;
    }

    private static long count(Iterable<Map<String, Object>> rows) {
        long count = 0;
        for (Map<String, Object> ignored : rows) {
            count++;
        }
        return count;
    }

    /** The named query and HTTP source results, keyed as the export declared them. */
    public Map<String, Object> values() {
        return values;
    }

    /**
     * The rows grouped by {@code column} (docs/export-pipeline.md, decision 3). Only a re-readable
     * export can answer: grouping walks the rows to find the boundaries, and each group walks them
     * again for its own.
     */
    public ExportGroups groupedBy(String column) {
        if (!(repeatable instanceof SpooledRows spooled)) {
            throw new TqlException(WRONG_ROW_SOURCE, "groups are only available to a codec that"
                    + " holds its rows - this export streams them");
        }
        return ExportGroups.of(spooled, column);
    }

    /** The model with one more named value; used while the declared sources are collected. */
    public ExportModel with(String name, Object value) {
        Map<String, Object> merged = new LinkedHashMap<>(values);
        merged.put(name, value);
        return new ExportModel(single, repeatable, merged);
    }
}
