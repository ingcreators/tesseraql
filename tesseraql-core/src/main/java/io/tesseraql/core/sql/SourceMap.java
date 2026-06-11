package io.tesseraql.core.sql;

import java.util.ArrayList;
import java.util.List;

/**
 * Maps offsets in rendered SQL back to 1-based source line numbers (design ch. 8.2, 9.1).
 *
 * <p>This supports translating database errors and query-plan findings on the executed SQL back
 * to the original {@code .sql} file location. The map is built incrementally by the renderer as
 * text segments are appended.
 */
public final class SourceMap {

    /**
     * A contiguous rendered range that originates from a single source line.
     *
     * @param renderedStart inclusive start offset in the rendered SQL
     * @param renderedEnd   exclusive end offset in the rendered SQL
     * @param sourceLine    1-based source line number
     */
    public record Segment(int renderedStart, int renderedEnd, int sourceLine) {
    }

    private final List<Segment> segments = new ArrayList<>();

    void addSegment(int renderedStart, int renderedEnd, int sourceLine) {
        if (renderedEnd > renderedStart) {
            segments.add(new Segment(renderedStart, renderedEnd, sourceLine));
        }
    }

    public List<Segment> segments() {
        return List.copyOf(segments);
    }

    /**
     * Returns the 1-based source line for a rendered offset, or {@code -1} if unknown.
     */
    public int sourceLineAt(int renderedOffset) {
        for (Segment segment : segments) {
            if (renderedOffset >= segment.renderedStart()
                    && renderedOffset < segment.renderedEnd()) {
                return segment.sourceLine();
            }
        }
        return -1;
    }
}
