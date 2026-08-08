package io.tesseraql.yaml.scaffold;

/**
 * One generated artifact: an app-home-relative path (always {@code /}-separated) and its content
 * before checksum stamping (roadmap Phase 23).
 */
public record ScaffoldedFile(String path, String content) {

    public ScaffoldedFile {
        // NFC-normalized so a Japanese path scaffolded on macOS (NFD filesystems) and on
        // Linux names the same file — otherwise regeneration sees spurious drift
        // (docs/unicode-identifiers.md).
        path = java.text.Normalizer.normalize(path, java.text.Normalizer.Form.NFC);
    }

    /** The content with the edit-detection checksum line applied. */
    public String stampedContent() {
        return ScaffoldChecksum.stamp(path, content);
    }
}
