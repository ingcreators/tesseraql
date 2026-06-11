package io.tesseraql.core.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class TqlExceptionTest {

    private static final TqlErrorCode CODE = new TqlErrorCode(TqlDomain.SQL, 4092);

    @Test
    void carriesClientSafeDetails() {
        TqlException ex = TqlException.builder(CODE)
                .message("Row-count expectation failed")
                .details(Map.of("expectedRows", 1, "actualRows", 0))
                .build();

        assertThat(ex.details())
                .containsEntry("expectedRows", 1)
                .containsEntry("actualRows", 0);
    }

    @Test
    void detailsDefaultToEmptyAndAreImmutable() {
        TqlException plain = new TqlException(CODE, "boom");
        assertThat(plain.details()).isEmpty();

        TqlException built = TqlException.builder(CODE).details(Map.of("a", 1)).build();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> built.details().put("b", 2))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
