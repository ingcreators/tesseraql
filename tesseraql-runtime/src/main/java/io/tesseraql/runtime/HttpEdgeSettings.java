package io.tesseraql.runtime;

import java.nio.file.Path;

/**
 * What the HTTP edge is configured with, as one value rather than a growing parameter list on
 * {@link TesseraqlHttpServer}'s constructor.
 *
 * <p>Both fields are the framework's own numbers rather than the transport's defaults, which is
 * the point of the record: every one of Vert.x's edge defaults that this runtime has adopted
 * silently has cost something (docs/camel-removal.md's defect class), so the ones it declares are
 * declared in one place.
 *
 * @param maxBodyBytes      the request-body ceiling, covering buffered bodies and streamed
 *                          uploads alike; {@code -1} is the visible opt-out
 * @param uploadsDirectory  where a multipart part or a form body spools while the request is in
 *                          flight; created at boot, and under the application's own work
 *                          directory rather than wherever the process happened to start
 */
record HttpEdgeSettings(long maxBodyBytes, Path uploadsDirectory) {
}
