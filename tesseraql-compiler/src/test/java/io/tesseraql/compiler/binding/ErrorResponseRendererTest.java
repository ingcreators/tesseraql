package io.tesseraql.compiler.binding;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.i18n.I18nSettings;
import io.tesseraql.yaml.model.ResponseSpec.OnError;
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
    void steersTheHtmxErrorResponseWhenTheFailingRouteDeclaresOnError() throws Exception {
        ErrorResponseRenderer renderer = new ErrorResponseRenderer(I18nSettings.defaults(),
                Map.of("members.create", new OnError("#flash", "outerHTML")));

        // The failing route declares onError: the error fragment is retargeted/reswapped.
        Exchange steered = exchangeWith(
                TqlException.builder(new TqlErrorCode(TqlDomain.FIELD, 4220)).build());
        steered.getMessage().setHeader("HX-Request", "true");
        steered.setProperty(Exchange.FAILURE_ROUTE_ID, "members.create");
        renderer.process(steered);
        assertThat(steered.getMessage().getHeader("HX-Retarget")).isEqualTo("#flash");
        assertThat(steered.getMessage().getHeader("HX-Reswap")).isEqualTo("outerHTML");

        // A route without onError keeps htmx's defaults (no steering headers).
        Exchange plain = exchangeWith(
                TqlException.builder(new TqlErrorCode(TqlDomain.FIELD, 4220)).build());
        plain.getMessage().setHeader("HX-Request", "true");
        plain.setProperty(Exchange.FAILURE_ROUTE_ID, "other.route");
        renderer.process(plain);
        assertThat(plain.getMessage().getHeader("HX-Retarget")).isNull();
        assertThat(plain.getMessage().getHeader("HX-Reswap")).isNull();
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
                // The declared key rides as messageKey (roadmap Phase 22); the human text under
                // message falls back to the built-in tql.constraint.<code> translation.
                .contains("\"messageKey\":\"members.email.duplicate\"")
                .contains("\"message\":\"Already exists.\"")
                .doesNotContain("internal detail");
    }

    @Test
    void localizesFieldErrorsAndStatusPhrasePerRequestLocale() throws Exception {
        Exchange exchange = exchangeWith(TqlException
                .builder(new TqlErrorCode(TqlDomain.FIELD, 4220))
                .details(Map.of("fields", List.of(
                        Map.of("field", "email", "code", "duplicate",
                                "message", "members.email.duplicate"))))
                .build());
        exchange.setProperty(io.tesseraql.camel.TesseraqlProperties.LOCALE, "ja");

        new ErrorResponseRenderer().process(exchange);

        String body = exchange.getMessage().getBody(String.class);
        assertThat(body).contains("\"message\":\"入力内容を確認してください\"")
                .contains("\"message\":\"すでに登録されています。\"")
                .contains("\"messageKey\":\"members.email.duplicate\"");
    }

    @Test
    void appCatalogResolvesDeclaredKeysWithPlaceholders() throws Exception {
        io.tesseraql.yaml.i18n.MessageCatalog catalog = io.tesseraql.yaml.i18n.MessageCatalog
                .parse("ja", new java.io.ByteArrayInputStream(
                        "orders.qty.exceeds: 在庫 {stock} を超えています。\n"
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        "ja.yml");
        I18nSettings i18n = new I18nSettings("en", List.of("en", "ja"),
                List.of("principal.claim.locale"),
                catalog.withFallback(I18nSettings.builtinCatalog()));
        Exchange exchange = exchangeWith(TqlException
                .builder(new TqlErrorCode(TqlDomain.FIELD, 4220))
                .details(Map.of("fields", List.of(
                        Map.of("field", "qty", "code", "stock", "stock", 5,
                                "message", "orders.qty.exceeds"))))
                .build());
        exchange.setProperty(io.tesseraql.camel.TesseraqlProperties.LOCALE, "ja");
        exchange.getMessage().setHeader("HX-Request", "true");

        new ErrorResponseRenderer(i18n).process(exchange);

        String body = exchange.getMessage().getBody(String.class);
        assertThat(body).contains("data-message-key=\"orders.qty.exceeds\"")
                .contains("在庫 5 を超えています。")
                // The entry's params ride along for the kit's client-side interpolation
                // (hc 0.1.1 data-message-params; JSON quotes escape as attribute text).
                .contains("data-message-params=\"{&quot;stock&quot;:5}\"");
    }

    @Test
    void parameterlessEntriesOmitDataMessageParams() throws Exception {
        Exchange exchange = exchangeWith(TqlException
                .builder(new TqlErrorCode(TqlDomain.FIELD, 4220))
                .details(Map.of("fields", List.of(
                        Map.of("field", "email", "code", "duplicate",
                                "message", "members.email.duplicate"))))
                .build());
        exchange.getMessage().setHeader("HX-Request", "true");

        new ErrorResponseRenderer().process(exchange);

        assertThat(exchange.getMessage().getBody(String.class))
                .contains("data-message-key=\"members.email.duplicate\"")
                .doesNotContain("data-message-params");
    }

    @Test
    void localizesConflictHintKeys() throws Exception {
        Exchange exchange = exchangeWith(TqlException
                .builder(new TqlErrorCode(TqlDomain.SQL, 4092))
                .details(Map.of("conflict", Map.of(
                        "step", "header", "expectedRows", 1, "actualRows", 0,
                        "hint", "tql.conflict.stale")))
                .build());
        exchange.setProperty(io.tesseraql.camel.TesseraqlProperties.LOCALE, "ja");

        new ErrorResponseRenderer().process(exchange);

        String body = exchange.getMessage().getBody(String.class);
        assertThat(body).contains("\"hintKey\":\"tql.conflict.stale\"")
                .contains("他のユーザーによってレコードが変更または削除された可能性があります");
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
