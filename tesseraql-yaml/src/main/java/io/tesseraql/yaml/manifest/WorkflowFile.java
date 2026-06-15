package io.tesseraql.yaml.manifest;

import io.tesseraql.yaml.model.WorkflowDefinition;
import java.nio.file.Path;

/**
 * A {@code workflow/} document resolved to its definition (roadmap Phase 28). The {@code source}
 * directory anchors the command, assignee, and history SQL files the workflow's transitions
 * reference.
 *
 * @param source     the source file path, within the app home
 * @param definition the parsed workflow definition
 */
public record WorkflowFile(Path source, WorkflowDefinition definition) {
}
