package io.tesseraql.core;

import java.util.Objects;

/**
 * Marker and version helper for the TesseraQL core module.
 */
public final class TesseraQL {
    private TesseraQL() {
    }

    public static String normalizeId(String id) {
        return Objects.requireNonNull(id, "id").trim();
    }
}
