package io.tesseraql.compiler.binding;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import java.util.List;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.Test;

class ErrorResponseRendererTest {

    @Test
    void rowCountExpectationConflictMapsTo409() {
        assertThat(ErrorResponseRenderer.httpStatus(new TqlErrorCode(TqlDomain.SQL, 4092)))
                .isEqualTo(409);
    }

    @Test
    void rendersClientSafeDetailsInTheErrorBody() throws Exception {
        Exchange exchange = exchangeWith(TqlException
                .builder(new TqlErrorCode(TqlDomain.SQL, 4090))
                .message("internal detail that must not leak")
                .details(Map.of("fields", List.of(
                        Map.of("field", "email", "code", "duplicate",
                                "constraint", "uq_users_email"))))
                .build());

        new ErrorResponseRenderer().process(exchange);

        String body = exchange.getMessage().getBody(String.class);
        assertThat(exchange.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE)).isEqualTo(409);
        assertThat(body).contains("\"code\":\"TQL-SQL-4090\"")
                .contains("\"message\":\"Conflict\"")
                .contains("\"field\":\"email\"")
                .contains("\"code\":\"duplicate\"")
                // The internal exception message is not leaked (design ch. 37.3).
                .doesNotContain("internal detail");
    }

    @Test
    void rendersFieldErrorsAsHtmxFragmentForHxRequests() throws Exception {
        Exchange exchange = exchangeWith(TqlException
                .builder(new TqlErrorCode(TqlDomain.SQL, 4092))
                .details(Map.of("conflict", Map.of(
                        "step", "header", "expectedRows", 1, "actualRows", 0,
                        "hint", "The record may have been changed by another user")))
                .build());
        exchange.getMessage().setHeader("HX-Request", "true");

        new ErrorResponseRenderer().process(exchange);

        String body = exchange.getMessage().getBody(String.class);
        assertThat(exchange.getMessage().getHeader(Exchange.CONTENT_TYPE, String.class))
                .startsWith("text/html");
        assertThat(body).contains("class=\"hc-alert\" data-variant=\"error\"")
                .contains("data-error-code=\"TQL-SQL-4092\"")
                .contains("hc-alert__body")
                .contains("changed by another user");
    }

    @Test
    void validationFailureMapsTo422WhileOtherFieldErrorsStay400() {
        assertThat(ErrorResponseRenderer.httpStatus(new TqlErrorCode(TqlDomain.FIELD, 4220)))
                .isEqualTo(422);
        assertThat(ErrorResponseRenderer.httpStatus(new TqlErrorCode(TqlDomain.FIELD, 2001)))
                .isEqualTo(400);
        assertThat(ErrorResponseRenderer.httpStatus(new TqlErrorCode(TqlDomain.FIELD, 2002)))
                .isEqualTo(400);
    }

    @Test
    void rendersValidationViolationsWithRuleAndMessageKey() throws Exception {
        Exchange exchange = exchangeWith(TqlException
                .builder(new TqlErrorCode(TqlDomain.FIELD, 4220))
                .message("internal detail that must not leak")
                .details(Map.of("fields", List.of(
                        Map.of("rule", "uniqueEmail", "field", "email", "code", "duplicate",
                                "message", "members.email.duplicate"))))
                .build());

        new ErrorResponseRenderer().process(exchange);

        String body = exchange.getMessage().getBody(String.class);
        assertThat(exchange.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE)).isEqualTo(422);
        assertThat(body).contains("\"code\":\"TQL-FIELD-4220\"")
                .contains("\"message\":\"Unprocessable Entity\"")
                .contains("\"rule\":\"uniqueEmail\"")
                .contains("\"field\":\"email\"")
                .contains("\"message\":\"members.email.duplicate\"")
                .doesNotContain("internal detail");
    }

    @Test
    void htmxFragmentCarriesTheFieldCodeAndMessageKey() throws Exception {
        Exchange exchange = exchangeWith(TqlException
                .builder(new TqlErrorCode(TqlDomain.FIELD, 4220))
                .details(Map.of("fields", List.of(
                        Map.of("rule", "uniqueEmail", "field", "email", "code", "duplicate",
                                "message", "members.email.duplicate"))))
                .build());
        exchange.getMessage().setHeader("HX-Request", "true");

        new ErrorResponseRenderer().process(exchange);

        String body = exchange.getMessage().getBody(String.class);
        assertThat(exchange.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE)).isEqualTo(422);
        assertThat(body).contains("hc-alert__error")
                .contains("data-field=\"email\"")
                .contains("data-code=\"duplicate\"")
                .contains("data-message-key=\"members.email.duplicate\"");
    }

    private static Exchange exchangeWith(Throwable cause) {
        Exchange exchange = new DefaultExchange(new DefaultCamelContext());
        exchange.setProperty(Exchange.EXCEPTION_CAUGHT, cause);
        return exchange;
    }

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
