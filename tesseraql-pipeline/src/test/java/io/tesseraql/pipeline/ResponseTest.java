package io.tesseraql.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The response's own contracts (docs/vertx-native.md structural decision 1): it starts empty,
 * a null status means "nothing has answered yet" and goes on the wire as 200, and Set-Cookie is
 * the header whose repetition the multi-value map exists for — {@code header} replaces,
 * {@code addHeader} appends, and every cookie writer appends.
 */
class ResponseTest {

    @Test
    void anUnansweredResponseWiresAs200() {
        Response response = new Response();

        assertThat(response.status()).isNull();
        assertThat(response.statusOr200()).isEqualTo(200);

        response.status(404);
        assertThat(response.status()).isEqualTo(404);
        assertThat(response.statusOr200()).isEqualTo(404);
    }

    /** The collision the append semantics prevent: a second cookie must join, not replace. */
    @Test
    void setCookieRepeatsThroughAddHeader() {
        Response response = new Response()
                .addHeader("Set-Cookie", "tql_session=rotated; HttpOnly")
                .addHeader("Set-Cookie", "tesseraql_theme=dark");

        assertThat(response.headers().get("Set-Cookie"))
                .containsExactly("tql_session=rotated; HttpOnly", "tesseraql_theme=dark");
    }

    @Test
    void headerReplacesEveryValueUnderTheName() {
        Response response = new Response()
                .addHeader("Vary", "Accept-Language")
                .addHeader("Vary", "HX-Request");
        response.header("Vary", "Cookie");

        assertThat(response.headers().get("Vary")).containsExactly("Cookie");
        assertThat(response.header("vary")).isEqualTo("Cookie");
    }

    @Test
    void headerNamesMatchWithoutRegardToCase() {
        Response response = new Response().header("Content-Type", "text/plain");

        assertThat(response.header("content-type")).isEqualTo("text/plain");
        assertThat(response.header("absent")).isNull();
    }

    /**
     * Adopting another pipeline's answer replaces what was here and copies deep: the ops shell
     * edits neither exchange through the other afterwards.
     */
    @Test
    void becomeCopyOfReplacesAndCopiesDeep() {
        Response source = new Response().status(201).addHeader("Set-Cookie", "a=b");
        Response target = new Response().status(500);
        target.header("X-Old", "stale");

        target.becomeCopyOf(source);
        source.addHeader("Set-Cookie", "c=d");

        assertThat(target.status()).isEqualTo(201);
        assertThat(target.header("X-Old")).isNull();
        assertThat(target.headers().get("Set-Cookie")).containsExactly("a=b");
    }

    /** A copied unanswered response stays unanswered — the 200 default applies at the wire. */
    @Test
    void becomeCopyOfKeepsANullStatusNull() {
        Response target = new Response().status(500);
        target.becomeCopyOf(new Response());

        assertThat(target.status()).isNull();
        assertThat(target.statusOr200()).isEqualTo(200);
    }
}
