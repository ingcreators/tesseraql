package io.tesseraql.yaml.attachment;

import io.tesseraql.yaml.config.AppConfig;

/**
 * Attachment metadata-store settings (roadmap Phase 30). Consistent with the IAM managed/app realm
 * duality, {@code tesseraql.attachments.mode} selects whether the framework provisions the managed
 * {@code tql_attachment} table ({@code managed}, the default) or the app owns its own metadata
 * ({@code app}). Slice 1 ships the managed store.
 */
public record AttachmentSettings(String mode, String onInfected) {

    public AttachmentSettings {
        mode = mode == null || mode.isBlank() ? "managed" : mode;
        onInfected = onInfected == null || onInfected.isBlank() ? "quarantine" : onInfected;
    }

    /** Whether the managed {@code tql_attachment} table is provisioned and maintained. */
    public boolean managed() {
        return "managed".equalsIgnoreCase(mode);
    }

    /** What to do with an infected upload: {@code quarantine} (keep, default) or {@code delete}. */
    public String onInfected() {
        return onInfected;
    }

    /** Reads the settings from config, defaulting to {@code managed} mode + {@code quarantine}. */
    public static AttachmentSettings from(AppConfig config) {
        return new AttachmentSettings(
                config.getString("tesseraql.attachments.mode").orElse(null),
                config.getString("tesseraql.attachments.scan.onInfected").orElse(null));
    }
}
