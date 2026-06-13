package io.tesseraql.yaml.lint;

/**
 * A lint finding for an app (design ch. 18 {@code lint}, 28.17, 33).
 *
 * @param code     the TQL error/lint code
 * @param severity {@code error} or {@code warning}
 * @param source   the app-relative source file
 * @param message  human-readable description
 */
public record LintFinding(String code, String severity, String source, String message) {

    public boolean isError() {
        return "error".equals(severity);
    }
}
