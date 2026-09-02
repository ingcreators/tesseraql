package io.tesseraql.compiler.binding;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.pipeline.Beans;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.Headers;
import io.tesseraql.pipeline.TesseraqlProperties;
import io.tesseraql.yaml.i18n.I18nSettings;
import io.tesseraql.yaml.model.ResponseSpec.OnError;
import java.util.List;
import java.util.Map;
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

        String body = exchange.getBody(String.class);
        assertThat(exchange.response().status()).isEqualTo(409);
        assertThat(body).contains("\"code\":\"TQL-SQL-4090\"")
                .contains("\"message\":\"Conflict\"")
                // Details render as the error.details namespace (transition-engine track F).
                .contains("\"details\":{\"fields\":[")
                .contains("\"field\":\"email\"")
                .contains("\"code\":\"duplicate\"")
                // The internal exception message is not leaked (design ch. 37.3).
                .doesNotContain("internal detail");
    }

    @Test
    void detailKeysNamedCodeAndMessageDoNotCollideWithTheEnvelope() throws Exception {
        // A workflow SQL guard's declared refusal (transition-engine track F): the natural
        // names live under details, beside the envelope's own registry code and phrase.
        Exchange exchange = exchangeWith(TqlException
                .builder(new TqlErrorCode(TqlDomain.WORKFLOW, 3202))
                .message("internal detail that must not leak")
                .details(Map.of("code", "not-funded", "message", "The request is not funded."))
                .build());

        new ErrorResponseRenderer().process(exchange);

        String body = exchange.getBody(String.class);
        assertThat(exchange.response().status()).isEqualTo(422);
        assertThat(body).contains("\"code\":\"TQL-WORKFLOW-3202\"")
                .contains("\"message\":\"Unprocessable Entity\"")
                .contains("\"code\":\"not-funded\"")
                .contains("\"message\":\"The request is not funded.\"")
                .doesNotContain("internal detail");
    }

    @Test
    void unenumeratedSecCodesAreServerFaultsNotUnauthorized() throws Exception {
        // The SEC domain default inverted to 500 (docs/contract-bugfixes.md track B): a
        // federation failure (4140) or crypto error (5001) is the server's fault; 401
        // would invite clients into token-refresh retries against a broken server.
        Exchange federation = exchangeWith(new TqlException(
                new TqlErrorCode(TqlDomain.SEC, 4140), "idp unreachable"));
        new ErrorResponseRenderer().process(federation);
        assertThat(federation.response().status())
                .isEqualTo(500);

        // The genuine credential failures keep their statuses.
        assertThat(ErrorResponseRenderer.httpStatus(new TqlErrorCode(TqlDomain.SEC, 4011)))
                .isEqualTo(401);
        assertThat(ErrorResponseRenderer.httpStatus(new TqlErrorCode(TqlDomain.SEC, 4012)))
                .isEqualTo(401);
        assertThat(ErrorResponseRenderer.httpStatus(new TqlErrorCode(TqlDomain.SEC, 4031)))
                .isEqualTo(403);
        assertThat(ErrorResponseRenderer.httpStatus(new TqlErrorCode(TqlDomain.SEC, 4014)))
                .isEqualTo(409);
        assertThat(ErrorResponseRenderer.httpStatus(new TqlErrorCode(TqlDomain.SEC, 5001)))
                .isEqualTo(500);
    }

    @Test
    void aMalformedBatchRunBodyIsABadRequest() {
        // TQL-BATCH-4043: a manual job-run with an unparseable JSON body is a 400, not a silent
        // parameterless 202 (silent-tolerance O8).
        assertThat(ErrorResponseRenderer.httpStatus(new TqlErrorCode(TqlDomain.BATCH, 4043)))
                .isEqualTo(400);
    }

    @Test
    void htmxFragmentRendersTheGuardRefusalMessageAsTheAlertBody() throws Exception {
        Exchange exchange = exchangeWith(TqlException
                .builder(new TqlErrorCode(TqlDomain.WORKFLOW, 3202))
                .details(Map.of("code", "not-funded", "message", "The request is not funded."))
                .build());
        exchange.request().header("HX-Request", "true");

        new ErrorResponseRenderer().process(exchange);

        assertThat(exchange.getBody(String.class))
                .contains("hc-alert__body")
                .contains("The request is not funded.");
    }

    @Test
    void rendersFieldErrorsAsHtmxFragmentForHxRequests() throws Exception {
        Exchange exchange = exchangeWith(TqlException
                .builder(new TqlErrorCode(TqlDomain.SQL, 4092))
                .details(Map.of("conflict", Map.of(
                        "step", "header", "expectedRows", 1, "actualRows", 0,
                        "hint", "The record may have been changed by another user")))
                .build());
        exchange.request().header("HX-Request", "true");

        new ErrorResponseRenderer().process(exchange);

        String body = exchange.getBody(String.class);
        assertThat(exchange.response().header(Headers.CONTENT_TYPE))
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
        steered.request().header("HX-Request", "true");
        steered.setProperty(TesseraqlProperties.FAILURE_ROUTE_ID, "members.create");
        renderer.process(steered);
        assertThat(steered.response().header("HX-Retarget")).isEqualTo("#flash");
        assertThat(steered.response().header("HX-Reswap")).isEqualTo("outerHTML");

        // A route without onError keeps htmx's defaults (no steering headers).
        Exchange plain = exchangeWith(
                TqlException.builder(new TqlErrorCode(TqlDomain.FIELD, 4220)).build());
        plain.request().header("HX-Request", "true");
        plain.setProperty(TesseraqlProperties.FAILURE_ROUTE_ID, "other.route");
        renderer.process(plain);
        assertThat(plain.response().header("HX-Retarget")).isNull();
        assertThat(plain.response().header("HX-Reswap")).isNull();
    }

    @Test
    void validationFailureMapsTo422WhileOtherFieldErrorsStay400() {
        assertThat(ErrorResponseRenderer.httpStatus(new TqlErrorCode(TqlDomain.FIELD, 4220)))
                .isEqualTo(422);
        assertThat(ErrorResponseRenderer.httpStatus(new TqlErrorCode(TqlDomain.FIELD, 2001)))
                .isEqualTo(400);
        assertThat(ErrorResponseRenderer.httpStatus(new TqlErrorCode(TqlDomain.FIELD, 2002)))
                .isEqualTo(400);
        // The locked route reached with neither lock field (docs/edit-conflict.md decision 4).
        // This assertion is the only guard on it: the FIELD arm answers 400 by default, so
        // nothing else would catch a regression, and a later reader must not "fix" that arm.
        assertThat(ErrorResponseRenderer.httpStatus(new TqlErrorCode(TqlDomain.FIELD, 2011)))
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

        String body = exchange.getBody(String.class);
        assertThat(exchange.response().status()).isEqualTo(422);
        assertThat(body).contains("\"code\":\"TQL-FIELD-4220\"")
                .contains("\"message\":\"Unprocessable Entity\"")
                .contains("\"details\":{\"fields\":[")
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
        exchange.setProperty(io.tesseraql.pipeline.TesseraqlProperties.LOCALE, "ja");

        new ErrorResponseRenderer().process(exchange);

        String body = exchange.getBody(String.class);
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
        exchange.setProperty(io.tesseraql.pipeline.TesseraqlProperties.LOCALE, "ja");
        exchange.request().header("HX-Request", "true");

        new ErrorResponseRenderer(i18n).process(exchange);

        String body = exchange.getBody(String.class);
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
        exchange.request().header("HX-Request", "true");

        new ErrorResponseRenderer().process(exchange);

        assertThat(exchange.getBody(String.class))
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
        exchange.setProperty(io.tesseraql.pipeline.TesseraqlProperties.LOCALE, "ja");

        new ErrorResponseRenderer().process(exchange);

        String body = exchange.getBody(String.class);
        assertThat(body).contains("\"details\":{\"conflict\":{")
                .contains("\"hintKey\":\"tql.conflict.stale\"")
                .contains("他のユーザーによってレコードが変更または削除された可能性があります");
    }

    @Test
    void aStaleLockCarriesTheAffordanceBesideTheConflictNeverInsideIt() throws Exception {
        // docs/edit-conflict.md decision 5: a sibling, because the renderer hands the whole
        // conflict map to the catalog as the hint interpolation parameters — a key named like
        // a placeholder inside it would silently rewrite the sentence. Asserted over the parsed
        // tree rather than the bytes: Map.of iterates in a per-JVM-run order, so a substring
        // assertion on serialized key order is a flake, not a guard.
        Exchange exchange = exchangeWith(TqlException
                .builder(new TqlErrorCode(TqlDomain.SQL, 4094))
                .details(Map.of(
                        "conflict", Map.of("step", "main", "expectedRows", 1, "actualRows", 0,
                                "hint", "tql.conflict.stale"),
                        "lock", Map.of("column", "version", "field", "_lock",
                                "overwriteField", "_overwrite")))
                .build());

        new ErrorResponseRenderer().process(exchange);

        com.fasterxml.jackson.databind.JsonNode error = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(exchange.getBody(String.class)).path("error");
        assertThat(error.path("code").asText()).isEqualTo("TQL-SQL-4094");

        com.fasterxml.jackson.databind.JsonNode conflict = error.path("details").path("conflict");
        assertThat(conflict.path("hintKey").asText()).isEqualTo("tql.conflict.stale");
        // The affordance is a sibling of the conflict, never an entry inside it.
        assertThat(conflict.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("step", "expectedRows", "actualRows", "hint", "hintKey");

        com.fasterxml.jackson.databind.JsonNode lock = error.path("details").path("lock");
        assertThat(lock.path("column").asText()).isEqualTo("version");
        assertThat(lock.path("field").asText()).isEqualTo("_lock");
        assertThat(lock.path("overwriteField").asText()).isEqualTo("_overwrite");
    }

    @Test
    void htmxFragmentCarriesTheFieldCodeAndMessageKey() throws Exception {
        Exchange exchange = exchangeWith(TqlException
                .builder(new TqlErrorCode(TqlDomain.FIELD, 4220))
                .details(Map.of("fields", List.of(
                        Map.of("rule", "uniqueEmail", "field", "email", "code", "duplicate",
                                "message", "members.email.duplicate"))))
                .build());
        exchange.request().header("HX-Request", "true");

        new ErrorResponseRenderer().process(exchange);

        String body = exchange.getBody(String.class);
        assertThat(exchange.response().status()).isEqualTo(422);
        assertThat(body).contains("hc-alert__error")
                .contains("data-field=\"email\"")
                .contains("data-code=\"duplicate\"")
                .contains("data-message-key=\"members.email.duplicate\"");
    }

    private static Exchange exchangeWith(Throwable cause) {
        Exchange exchange = new Exchange(
                Beans.NONE);
        exchange.setProperty(TesseraqlProperties.EXCEPTION_CAUGHT, cause);
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
        // The declared lock's stale write (docs/edit-conflict.md decision 5).
        assertThat(ErrorResponseRenderer.httpStatus(new TqlErrorCode(TqlDomain.SQL, 4094)))
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
