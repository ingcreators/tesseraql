package io.tesseraql.yaml.manifest;

import io.tesseraql.yaml.view.ViewSpec;
import java.nio.file.Path;

/**
 * One parsed {@code *.view.yml} document from the app-wide view registry (docs/view-composition.md
 * wave 1): the source file and its spec. Views are name-referenced — {@code response.html.view}
 * carries the document's {@code id}, unique app-wide — while the files themselves stay colocated
 * with their routes (or under {@code templates/} when shared).
 */
public record ViewFile(Path source, ViewSpec spec) {
}
