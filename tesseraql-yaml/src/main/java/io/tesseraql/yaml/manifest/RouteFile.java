package io.tesseraql.yaml.manifest;

import io.tesseraql.yaml.model.RouteDefinition;
import java.nio.file.Path;

/**
 * A route YAML file resolved to its HTTP binding (design ch. 4.2).
 *
 * @param httpMethod  the HTTP method derived from the file name (e.g. {@code get.yml} -&gt; GET)
 * @param urlPath     the URL path derived from the directory structure
 * @param source      the source file path, within the app home
 * @param definition  the parsed route definition
 */
public record RouteFile(String httpMethod, String urlPath, Path source, RouteDefinition definition) {
}
