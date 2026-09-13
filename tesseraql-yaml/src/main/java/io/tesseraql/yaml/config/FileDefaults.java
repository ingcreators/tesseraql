package io.tesseraql.yaml.config;

import java.util.Objects;

/**
 * The app-wide formatting literals a file recipe falls back to: {@code tesseraql.files.locale}
 * and {@code tesseraql.files.timezone}. One reader for the route compiler, the job executor and
 * the poll sources, because a key that resolves differently depending on which arm asked is a
 * default only by coincidence — and for the job arms it was worse than that: neither read it.
 *
 * <p>Read on use, not at construction, like every {@link SqlDefaults} read: a key nothing in
 * the app consults is never resolved, so an unresolvable placeholder in it fails where it fails
 * today — at the file route's compile, or at the export step — and never at the boot of an app
 * that has no file recipe. Blank is unset. The values are literals by contract
 * (docs/export-declarations.md); a source expression here is refused at lint and boot, never
 * resolved.
 */
public final class FileDefaults {

    private static final FileDefaults NONE = new FileDefaults(null);

    private final AppConfig config;

    private FileDefaults(AppConfig config) {
        this.config = config;
    }

    /** The defaults an app configuration declares. */
    public static FileDefaults of(AppConfig config) {
        return new FileDefaults(Objects.requireNonNull(config, "config"));
    }

    /** No configuration at all: every read answers null, the platform default. */
    public static FileDefaults none() {
        return NONE;
    }

    /** {@code tesseraql.files.locale}, or null when unset or blank. */
    public String locale() {
        return config == null
                ? null
                : config.getString("tesseraql.files.locale")
                        .filter(value -> !value.isBlank()).orElse(null);
    }

    /** {@code tesseraql.files.timezone}, or null when unset or blank. */
    public String timezone() {
        return config == null
                ? null
                : config.getString("tesseraql.files.timezone")
                        .filter(value -> !value.isBlank()).orElse(null);
    }

    /** The step's or route's own literal when it declares one, else the configured locale. */
    public String localeOr(String declared) {
        return declared != null && !declared.isBlank() ? declared : locale();
    }

    /** The step's or route's own literal when it declares one, else the configured zone. */
    public String timezoneOr(String declared) {
        return declared != null && !declared.isBlank() ? declared : timezone();
    }
}
