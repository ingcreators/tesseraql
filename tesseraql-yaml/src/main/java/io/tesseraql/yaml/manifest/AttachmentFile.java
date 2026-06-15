package io.tesseraql.yaml.manifest;

import io.tesseraql.yaml.model.AttachmentDefinition;
import java.nio.file.Path;

/** An {@code attachments/} document and its source path (roadmap Phase 30). */
public record AttachmentFile(Path source, AttachmentDefinition definition) {
}
