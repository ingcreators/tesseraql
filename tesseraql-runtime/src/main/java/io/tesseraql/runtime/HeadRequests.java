package io.tesseraql.runtime;

import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;

/**
 * A HEAD is a GET without the body (RFC 9110 §9.3.2; docs/edge-hygiene.md E4): the response
 * carries the status and the headers the GET would carry — {@code Content-Length} saying how
 * long the content would have been — and no content.
 *
 * <p>The withholding is the edge's, not the transport's. Measured on Vert.x 5.1.7: over
 * HTTP/1.1 the response drops the body of a HEAD itself but sends no {@code Content-Length}
 * for it (its {@code prepareHeaders} keeps one the handler set and sets none of its own on a
 * HEAD); over HTTP/2 — which every h2c client, the JDK's included, upgrades to — the body of
 * a HEAD goes out in full. So every writer at the edge ends a HEAD through here, having set
 * its headers as it would for the GET and computed the length it would have sent.
 */
final class HeadRequests {

    private HeadRequests() {
    }

    /** Whether the request is a HEAD, whose response must carry no content. */
    static boolean isHead(HttpServerRequest request) {
        return request.method() == HttpMethod.HEAD;
    }

    /**
     * Ends a HEAD response: the length the GET's content would have had ({@code -1} when the
     * writer does not know it — a streamed body — in which case no length is claimed), and no
     * content. The headers are the ones already set on the response.
     */
    static void endWithoutBody(HttpServerResponse response, long length) {
        if (length >= 0) {
            response.putHeader(HttpHeaders.CONTENT_LENGTH, Long.toString(length));
        }
        response.end();
    }
}
