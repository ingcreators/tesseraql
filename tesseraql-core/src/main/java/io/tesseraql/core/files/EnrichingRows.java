package io.tesseraql.core.files;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * A row source with its enrichment applied a window at a time (docs/lookups.md, slice 13b).
 *
 * <p>Wrapping the <em>iterator</em> is what lets one insertion point serve every export shape.
 * A streaming codec reads through this and sees a sliding window; a buffering codec spools what
 * comes out of it; a {@code splitBy:} bundle spools it too. None of them learns that an
 * enrichment happened, and none of them holds more rows than it already did.
 *
 * <p>The window is the enrichment's {@code batchSize} — the keys it was going to fetch in one
 * statement anyway. So an export of a million rows makes one reference query per window, not one
 * per row and not one enormous one.
 */
public final class EnrichingRows implements Iterator<Map<String, Object>> {

    private final Iterator<Map<String, Object>> source;
    private final RowEnricher enricher;
    private final int window;
    private final List<Map<String, Object>> ready = new ArrayList<>();
    private int position;

    private EnrichingRows(Iterator<Map<String, Object>> source, RowEnricher enricher, int window) {
        this.source = source;
        this.enricher = enricher;
        this.window = Math.max(1, window);
    }

    /** The source unchanged when nothing enriches it, so the no-enrichment path costs nothing. */
    public static Iterator<Map<String, Object>> of(Iterator<Map<String, Object>> source,
            RowEnricher enricher, int window) {
        return enricher == null ? source : new EnrichingRows(source, enricher, window);
    }

    @Override
    public boolean hasNext() {
        if (position < ready.size()) {
            return true;
        }
        fill();
        return position < ready.size();
    }

    @Override
    public Map<String, Object> next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        return ready.get(position++);
    }

    private void fill() {
        ready.clear();
        position = 0;
        List<Map<String, Object>> batch = new ArrayList<>(window);
        while (batch.size() < window && source.hasNext()) {
            batch.add(source.next());
        }
        if (batch.isEmpty()) {
            return;
        }
        ready.addAll(enricher.enrich(batch));
    }
}
