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
