package io.tesseraql.core.files;

import java.nio.file.Path;

/**
 * A named query an export composes around its rows (docs/export-pipeline.md, decision 2): the
 * order header a line-item document labels itself with, the totals its footer prints, the master
 * data a template resolves codes against.
 *
 * <p>It runs on the extraction's connection, inside the extraction's transaction and before it, so
 * a document reads exactly the state its rows came from. The result lands in
 * {@link ExportModel#values()} under {@code name}, shaped like a read route's named query —
 * {@code rows} and {@code rowCount} — so a template written against one reads the same as the
 * other.
 *
 * @param name    the key the template reads it under
 * @param sqlFile the 2-way SQL file, already resolved against the route or job directory
 */
public record ExportQuery(String name, Path sqlFile) {
}
