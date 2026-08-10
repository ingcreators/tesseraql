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

    /** The named query and HTTP source results, keyed as the export declared them. */
    public Map<String, Object> values() {
        return values;
    }

    /** The model with one more named value; used while the declared sources are collected. */
    public ExportModel with(String name, Object value) {
        Map<String, Object> merged = new LinkedHashMap<>(values);
        merged.put(name, value);
        return new ExportModel(single, repeatable, merged);
    }
}
