package io.tesseraql.core.error;

import java.util.Objects;

/**
 * A stable error code in the {@code TQL-<DOMAIN>-<NUMBER>} taxonomy (design ch. 37).
 *
 * <p>The same code is intended to be referenced from API responses, logs, reports, Studio, and
 * the Operations Console. The numeric part is zero-padded to four digits for a uniform format
 * (for example {@code TQL-SQL-2001}).
 *
 * @param domain the functional area the error belongs to
 * @param number the numeric identifier within the domain
 */
public record TqlErrorCode(TqlDomain domain, int number) {

    public TqlErrorCode {
        Objects.requireNonNull(domain, "domain");
        if (number < 0) {
            throw new IllegalArgumentException("number must be non-negative: " + number);
        }
    }

    /**
     * Returns the canonical string form, for example {@code TQL-SQL-2001}.
     */
    @Override
    public String toString() {
        return "TQL-" + domain.name() + "-" + String.format("%04d", number);
    }

    /**
     * Parses a canonical code such as {@code TQL-SQL-2001}.
     *
     * @throws IllegalArgumentException if the value is not a valid TesseraQL error code
     */
    public static TqlErrorCode parse(String value) {
        Objects.requireNonNull(value, "value");
        String[] parts = value.split("-", 3);
        if (parts.length != 3 || !"TQL".equals(parts[0])) {
            throw new IllegalArgumentException("Not a TesseraQL error code: " + value);
        }
        TqlDomain domain;
        try {
            domain = TqlDomain.valueOf(parts[1]);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown error domain in: " + value, ex);
        }
        try {
            return new TqlErrorCode(domain, Integer.parseInt(parts[2]));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid error number in: " + value, ex);
        }
    }
}
