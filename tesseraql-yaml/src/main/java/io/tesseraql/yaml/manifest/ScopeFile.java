package io.tesseraql.yaml.manifest;

import io.tesseraql.yaml.model.ScopeDefinition;
import java.nio.file.Path;

/**
 * A {@code scope/} document resolved to its definition (roadmap Phase 29). The {@code source}
 * directory anchors the fragment files the scope's match arms reference.
 *
 * @param source     the source file path, within the app home
 * @param definition the parsed scope definition
 */
public record ScopeFile(Path source, ScopeDefinition definition) {
}
