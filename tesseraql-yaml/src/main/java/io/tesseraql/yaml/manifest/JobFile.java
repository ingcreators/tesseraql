package io.tesseraql.yaml.manifest;

import io.tesseraql.yaml.model.JobDefinition;
import java.nio.file.Path;

/**
 * A batch job YAML file with its source location (design ch. 4.1 {@code batch/}).
 *
 * @param source     the source file path within the app home
 * @param definition the parsed job definition
 */
public record JobFile(Path source, JobDefinition definition) {
}
