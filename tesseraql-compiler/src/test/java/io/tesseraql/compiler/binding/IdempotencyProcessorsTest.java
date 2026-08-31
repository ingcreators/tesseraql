package io.tesseraql.compiler.binding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.idempotency.IdempotencyStore;
import io.tesseraql.pipeline.Beans;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.TesseraqlProperties;
import io.tesseraql.security.Principal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The begin step's request hash and claim bookkeeping (docs/idempotency-key.md decisions 1-3).
 * A browser form's fields never reach the exchange body — the edge parses them into
 * {@code formFields()} — so the hash must canonicalize them; and the claim must be recorded on
 * the exchange so the runner can release it when the request fails before a commit.
 */
class IdempotencyProcessorsTest {

    /** Records what begin() was asked, answers what the test says. */
    private static final class FakeStore implements IdempotencyStore {
        String lastHash;
        Map<String, String> completedHeaders;
        BeginResult answer = new Proceed();

        @Override
        public BeginResult begin(String scope, String key, String requestHash, long ttlMillis) {
            this.lastHash = requestHash;
            return answer;
        }

        @Override
        public void complete(String scope, String key, int status, String body,
                String contentType, Map<String, String> headers) {
            this.completedHeaders = headers;
        }

        @Override
        public void release(String scope, String key) {
        }
    }

    private static Exchange exchange(FakeStore store) {
        Beans beans = new Beans() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> T lookup(String name, Class<T> type) {
                return TesseraqlProperties.IDEMPOTENCY_STORE_BEAN.equals(name) ? (T) store : null;
            }
        };
        Exchange exchange = new Exchange(beans);
        exchange.request().header("Idempotency-Key", "k-1");
        exchange.request().method("POST");
        exchange.request().uri("/api/orders");
        return exchange;
    }

    private static void begin(Exchange exchange) throws Exception {
        IdempotencyProcessors.begin("orders", 60_000, false).process(exchange);
    }

    @Test
    void formFieldsHashCanonicallyRegardlessOfArrivalOrder() throws Exception {
        FakeStore first = new FakeStore();
        Exchange a = exchange(first);
        a.request().formFields().put("customerId", List.of("1"));
        a.request().formFields().put("quantity", List.of("2"));
        begin(a);

        FakeStore second = new FakeStore();
        Exchange b = exchange(second);
        b.request().formFields().put("quantity", List.of("2"));
        b.request().formFields().put("customerId", List.of("1"));
        begin(b);

        assertThat(first.lastHash).isEqualTo(second.lastHash);
    }

    @Test
    void differentFormValuesHashDifferently() throws Exception {
        FakeStore first = new FakeStore();
        Exchange a = exchange(first);
        a.request().formFields().put("quantity", List.of("2"));
        begin(a);

        FakeStore second = new FakeStore();
        Exchange b = exchange(second);
        b.request().formFields().put("quantity", List.of("3"));
        begin(b);

        assertThat(first.lastHash).isNotEqualTo(second.lastHash);
    }

    @Test
    void reservedFieldsStayOutOfTheHash() throws Exception {
        // _csrf varies by session and _idempotency is the key itself: neither may make two
        // submissions of the same form look like different requests.
        FakeStore first = new FakeStore();
        Exchange a = exchange(first);
        a.request().formFields().put("quantity", List.of("2"));
        a.request().formFields().put("_csrf", List.of("token-a"));
        a.request().formFields().put("_idempotency", List.of("k-1"));
        begin(a);

        FakeStore second = new FakeStore();
        Exchange b = exchange(second);
        b.request().formFields().put("quantity", List.of("2"));
        b.request().formFields().put("_csrf", List.of("token-b"));
        begin(b);

        assertThat(first.lastHash).isEqualTo(second.lastHash);
    }

    @Test
    void thePrincipalIsFoldedIntoTheHash() throws Exception {
        // Per-user scope without a schema change: another user replaying a stolen key
        // mismatches and is refused (docs/idempotency-key.md decision 2).
        FakeStore first = new FakeStore();
        Exchange a = exchange(first);
        a.setProperty(TesseraqlProperties.PRINCIPAL, principal("alice"));
        begin(a);

        FakeStore second = new FakeStore();
        Exchange b = exchange(second);
        b.setProperty(TesseraqlProperties.PRINCIPAL, principal("bob"));
        begin(b);

        assertThat(first.lastHash).isNotEqualTo(second.lastHash);
    }

    @Test
    void aFreshClaimIsRecordedOnTheExchangeForTheRunnerToRelease() throws Exception {
        FakeStore store = new FakeStore();
        Exchange exchange = exchange(store);
        begin(exchange);

        assertThat(exchange.getProperty(TesseraqlProperties.IDEMPOTENCY_CLAIM, String.class))
                .isEqualTo("orders\nk-1");
    }

    @Test
    void completeClearsTheClaimAfterStoringTheResponse() throws Exception {
        FakeStore store = new FakeStore();
        Exchange exchange = exchange(store);
        begin(exchange);
        exchange.setBody("{\"ok\":true}");

        IdempotencyProcessors.complete("orders").process(exchange);

        assertThat(exchange.getProperty(TesseraqlProperties.IDEMPOTENCY_CLAIM, String.class))
                .isNull();
    }

    @Test
    void completeSnapshotsOnlyTheAllowlistedHeadersAndReplayReEmitsThem() throws Exception {
        // The htmx signals and the PRG Location replay; security headers stay the fresh
        // response's business (docs/idempotency-key.md decision 6).
        FakeStore store = new FakeStore();
        Exchange exchange = exchange(store);
        begin(exchange);
        exchange.setBody("{\"ok\":true}");
        exchange.response().header("HX-Trigger", "{\"hc:toast\":{\"message\":\"Saved\"}}");
        exchange.response().header("Location", "/orders/1");
        exchange.response().header("X-Frame-Options", "DENY");
        IdempotencyProcessors.complete("orders").process(exchange);

        assertThat(store.completedHeaders)
                .containsEntry("HX-Trigger", "{\"hc:toast\":{\"message\":\"Saved\"}}")
                .containsEntry("Location", "/orders/1")
                .doesNotContainKey("X-Frame-Options");

        FakeStore replaying = new FakeStore();
        replaying.answer = new IdempotencyStore.Replay(201, "{\"ok\":true}",
                "application/json", Map.of("HX-Trigger", "{\"hc:toast\":{}}"));
        Exchange replayed = exchange(replaying);
        begin(replayed);
        assertThat(replayed.response().header("HX-Trigger")).isEqualTo("{\"hc:toast\":{}}");
    }

    @Test
    void mismatchAndInFlightRefuseWithTheirOwnCodes() {
        FakeStore mismatch = new FakeStore();
        mismatch.answer = new IdempotencyStore.Conflict("reused", false);
        assertThatThrownBy(() -> begin(exchange(mismatch)))
                .isInstanceOfSatisfying(TqlException.class,
                        ex -> assertThat(ex.code().toString()).isEqualTo("TQL-IDEM-4221"));

        FakeStore inFlight = new FakeStore();
        inFlight.answer = new IdempotencyStore.Conflict("in progress", true);
        assertThatThrownBy(() -> begin(exchange(inFlight)))
                .isInstanceOfSatisfying(TqlException.class,
                        ex -> assertThat(ex.code().toString()).isEqualTo("TQL-IDEM-4090"));
    }

    @Test
    void theKeyFallsBackToTheFormFieldWhenNoHeaderArrives() throws Exception {
        // The no-JS form transport (docs/idempotency-key.md decision 5): the rendered
        // _idempotency hidden field carries the key when no header can.
        FakeStore store = new FakeStore();
        Exchange exchange = exchange(store);
        exchange.request().header("Idempotency-Key", "");
        exchange.request().formFields().put("_idempotency", List.of("field-key"));
        begin(exchange);

        assertThat(exchange.getProperty(TesseraqlProperties.IDEMPOTENCY_CLAIM, String.class))
                .isEqualTo("orders\nfield-key");
    }

    @Test
    void theHeaderWinsWhenBothTransportsCarryAKey() throws Exception {
        FakeStore store = new FakeStore();
        Exchange exchange = exchange(store);
        exchange.request().formFields().put("_idempotency", List.of("field-key"));
        begin(exchange);

        assertThat(exchange.getProperty(TesseraqlProperties.IDEMPOTENCY_CLAIM, String.class))
                .isEqualTo("orders\nk-1");
    }

    private static Principal principal(String subject) {
        return new Principal(subject, subject, subject, null, List.of(), List.of(), List.of(),
                Map.of(), List.of(), List.of());
    }
}
