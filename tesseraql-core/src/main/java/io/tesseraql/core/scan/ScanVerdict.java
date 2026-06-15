package io.tesseraql.core.scan;

/**
 * The result of scanning an attachment (roadmap Phase 30 slice 3): {@code CLEAN} when no threat was
 * found, {@code INFECTED} when one was, {@code ERROR} when the scanner could not reach a verdict
 * (treated fail-closed — the upload is rejected). {@code detail} is an optional human-readable note.
 */
public record ScanVerdict(Status status, String detail) {

    /** The outcome of a scan. */
    public enum Status {
        CLEAN, INFECTED, ERROR
    }

    public static ScanVerdict clean() {
        return new ScanVerdict(Status.CLEAN, null);
    }

    public static ScanVerdict infected(String detail) {
        return new ScanVerdict(Status.INFECTED, detail);
    }

    public static ScanVerdict error(String detail) {
        return new ScanVerdict(Status.ERROR, detail);
    }

    public boolean isClean() {
        return status == Status.CLEAN;
    }
}
