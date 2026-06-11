package io.tesseraql.compiler.binding;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import org.junit.jupiter.api.Test;

class ErrorResponseRendererTest {

    @Test
    void mapsSqlConstraintViolationsToHttpStatuses() {
        assertThat(ErrorResponseRenderer.httpStatus(new TqlErrorCode(TqlDomain.SQL, 4090)))
                .isEqualTo(409);
        assertThat(ErrorResponseRenderer.httpStatus(new TqlErrorCode(TqlDomain.SQL, 4091)))
                .isEqualTo(409);
        assertThat(ErrorResponseRenderer.httpStatus(new TqlErrorCode(TqlDomain.SQL, 4093)))
                .isEqualTo(409);
        assertThat(ErrorResponseRenderer.httpStatus(new TqlErrorCode(TqlDomain.SQL, 4001)))
                .isEqualTo(400);
        assertThat(ErrorResponseRenderer.httpStatus(new TqlErrorCode(TqlDomain.SQL, 4002)))
                .isEqualTo(400);
        // A generic SQL execution error stays 500.
        assertThat(ErrorResponseRenderer.httpStatus(new TqlErrorCode(TqlDomain.SQL, 2500)))
                .isEqualTo(500);
    }
}
