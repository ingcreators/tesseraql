package io.tesseraql.core.files;

import java.net.URI;
import java.util.UUID;

/**
 * The semantic formats a declared {@code format:} names — pragmatic and JDK-only.
 *
 * <p>It lives in core for the reason the pattern cache does: two surfaces hold values to the same
 * declaration and must not disagree about it. The request binder checks one submitted value; a
 * reviewed import checks one column of every row in a file. An author who declares
 * {@code format: email} on an import route's row contract has every reason to expect the word to
 * mean there what it means on the form beside it.
 *
 * <p>An unknown format matches everything rather than nothing: the name is checked where it is
 * written, and a runtime that refused what it did not recognise would turn a lint's job into an
 * outage.
 */
public final class FieldFormats {

    private FieldFormats() {
    }

    public static boolean matches(String format, String value) {
        return switch (format) {
            case "email" -> value.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
            case "uuid" -> {
                try {
                    UUID.fromString(value);
                    yield true;
                } catch (IllegalArgumentException ex) {
                    yield false;
                }
            }
            case "url" -> {
                try {
                    URI uri = URI.create(value);
                    yield ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                            && uri.getHost() != null;
                } catch (IllegalArgumentException ex) {
                    yield false;
                }
            }
            default -> true;
        };
    }
}
