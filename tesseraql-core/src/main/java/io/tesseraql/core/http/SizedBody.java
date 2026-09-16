package io.tesseraql.core.http;

import java.io.FilterInputStream;
import java.io.InputStream;

/**
 * A streamed body that knows its length (docs/audit-low-leads.md slice 9, XD-09b).
 *
 * <p>A download is written to the wire as a stream, and the edge framed every stream as
 * chunked — which HTTP/1.0 does not have. An HTTP/1.0 client, and nginx is one to its upstream
 * by default, then read the body to EOF, so a mid-body failure that closed the connection
 * read as a complete file; and the stack gateway, which cannot forward chunks to such a
 * client, buffered the whole body in heap first. A spool, a staged database spool, a blob and
 * an attachment all know their size before the first byte is read, so the reader hands the
 * edge the length with the stream and the edge declares {@code Content-Length} instead: a
 * short body is then a truncated one on every HTTP version, a HEAD carries the length, and
 * the gateway streams what it can measure. The length travels on the body, not as a header,
 * because {@link ReservedHeaders} drops a {@code Content-Length} written by code — the
 * framing is the edge's to compute from what it writes.
 */
public final class SizedBody extends FilterInputStream {

    private final long length;

    private SizedBody(InputStream in, long length) {
        super(in);
        this.length = length;
    }

    /** {@code in} with its length, or {@code in} itself when the length is unknown ({@code < 0}). */
    public static InputStream of(InputStream in, long length) {
        return length < 0 || in instanceof SizedBody ? in : new SizedBody(in, length);
    }

    /** The bytes {@code in} will deliver, or {@code -1} when nothing measured it. */
    public static long lengthOf(InputStream in) {
        return in instanceof SizedBody sized ? sized.length : -1;
    }

    /** The bytes this body delivers. */
    public long length() {
        return length;
    }
}
