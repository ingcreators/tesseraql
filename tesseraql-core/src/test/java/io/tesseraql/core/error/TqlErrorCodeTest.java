package io.tesseraql.core.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TqlErrorCodeTest {

    @Test
    void formatsCanonicalString() {
        assertThat(new TqlErrorCode(TqlDomain.SQL, 2001)).hasToString("TQL-SQL-2001");
    }

    @Test
    void zeroPadsNumberToFourDigits() {
        assertThat(new TqlErrorCode(TqlDomain.SEC, 1)).hasToString("TQL-SEC-0001");
    }

    /**
     * A code is an identity, not a rendering: it goes on the wire in the error envelope, into the
     * generated reference, and into every comparison against a literal like {@code "TQL-SQL-2001"}.
     * A JVM whose default locale carries its own numbering system — {@code ar-EG}, {@code bn-BD},
     * Devanagari — would otherwise render {@code TQL-SQL-٢٠٠١}, which matches no literal anywhere
     * and which {@link TqlErrorCode#parse} cannot read back.
     *
     * <p>There is no parallel test execution in this repository, so restoring the default in a
     * finally block is enough.
     */
    @Test
    void theCodeIsTheSameStringUnderALocaleWithItsOwnDigits() {
        java.util.Locale original = java.util.Locale.getDefault();
        try {
            for (java.util.Locale locale : new java.util.Locale[]{
                    java.util.Locale.of("ar", "EG"), java.util.Locale.of("bn", "BD"),
                    java.util.Locale.forLanguageTag("hi-IN-u-nu-deva")}) {
                java.util.Locale.setDefault(locale);
                TqlErrorCode code = new TqlErrorCode(TqlDomain.SQL, 2001);
                assertThat(code).as("under %s", locale).hasToString("TQL-SQL-2001");
                assertThat(TqlErrorCode.parse(code.toString())).isEqualTo(code);
            }
        } finally {
            java.util.Locale.setDefault(original);
        }
    }

    @Test
    void parsesCanonicalString() {
        TqlErrorCode code = TqlErrorCode.parse("TQL-YAML-1001");
        assertThat(code.domain()).isEqualTo(TqlDomain.YAML);
        assertThat(code.number()).isEqualTo(1001);
    }

    @Test
    void parseRoundTrips() {
        TqlErrorCode code = new TqlErrorCode(TqlDomain.TENANT, 3001);
        assertThat(TqlErrorCode.parse(code.toString())).isEqualTo(code);
    }

    @Test
    void rejectsUnknownDomain() {
        assertThatThrownBy(() -> TqlErrorCode.parse("TQL-NOPE-0001"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonTqlPrefix() {
        assertThatThrownBy(() -> TqlErrorCode.parse("ERR-SQL-0001"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exceptionMessageIncludesCodeAndSource() {
        TqlException ex = TqlException.builder(new TqlErrorCode(TqlDomain.SQL, 2001))
                .message("Missing bind parameter")
                .source("web/api/users/search.sql")
                .line(12)
                .build();
        assertThat(ex.getMessage())
                .isEqualTo("TQL-SQL-2001: Missing bind parameter [web/api/users/search.sql:12]");
        assertThat(ex.line()).contains(12);
    }
}
