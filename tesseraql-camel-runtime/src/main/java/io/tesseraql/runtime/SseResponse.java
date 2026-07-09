package io.tesseraql.runtime;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import org.apache.camel.Exchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Answers a platform-http exchange as a Server-Sent Events stream (docs/copilot.md, "The
 * SSE transport"). The response body is the read end of a pipe: camel-platform-http-vertx
 * pumps {@code InputStream} bodies to the client chunked, so each frame flushes as it is
 * written. The producer runs on a virtual thread after the route processor returns; a
 * client disconnect surfaces as an {@link IOException} on a later write and simply ends
 * the producer.
 *
 * <p>The pipe buffer is deliberately large: close propagation from a dropped connection is
 * best-effort in the pump, and a producer must never block forever on a full pipe while
 * holding its own locks — a whole turn's frames fit in the buffer, so the producer always
 * runs to completion even against a dead client.
 */
final class SseResponse {

    private static final Logger LOG = LoggerFactory.getLogger(SseResponse.class);
    private static final int PIPE_BUFFER = 1024 * 1024;

    private SseResponse() {
    }

    /** One connected SSE client; {@code data} must be single-line by construction. */
    interface Writer {
        void event(String name, String data) throws IOException;
    }

    /** The producing side of one stream, run on its own virtual thread. */
    interface Producer {
        void produce(Writer writer) throws IOException;
    }

    /**
     * Sets the SSE response headers, wires the piped body, and starts the producer. The
     * caller's processor returns immediately; the stream ends when the producer does.
     */
    static void respond(Exchange exchange, Producer producer) throws IOException {
        PipedOutputStream sink = new PipedOutputStream();
        PipedInputStream body = new PipedInputStream(sink, PIPE_BUFFER);
        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE,
                "text/event-stream; charset=utf-8");
        exchange.getMessage().setHeader("Cache-Control", "no-store");
        // Tell buffering reverse proxies (nginx) to pass frames through as they arrive.
        exchange.getMessage().setHeader("X-Accel-Buffering", "no");
        exchange.getMessage().setBody(body);
        Thread.ofVirtual().name("tql-sse-" + exchange.getExchangeId()).start(() -> {
            try (sink) {
                producer.produce((name, data) -> {
                    // An SSE data payload is one line per frame; producers encode newlines
                    // as markup before framing, this guard only keeps the wire valid.
                    String line = data == null ? "" : data.replace("\r", "").replace("\n", "");
                    sink.write(("event: " + name + "\ndata: " + line + "\n\n")
                            .getBytes(StandardCharsets.UTF_8));
                    sink.flush();
                });
            } catch (IOException ex) {
                // The client went away (or the pump closed the pipe) — normal end of stream.
                LOG.debug("SSE stream {} ended early: {}", exchange.getExchangeId(),
                        ex.getMessage());
            }
        });
    }
}
