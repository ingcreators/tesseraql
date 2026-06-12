package io.tesseraql.yaml.scaffold;

/**
 * One generated artifact: an app-home-relative path (always {@code /}-separated) and its content
 * before checksum stamping (roadmap Phase 23).
 */
public record ScaffoldedFile(String path, String content) {

    /** The content with the edit-detection checksum line applied. */
    public String stampedContent() {
        return ScaffoldChecksum.stamp(path, content);
    }
}
