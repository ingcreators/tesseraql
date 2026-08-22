package io.tesseraql.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The request's own contracts, pinned where they are declared (docs/vertx-native.md structural
 * decisions 1 and 2). These behaviours carry real defects' fixes — a form field replacing a path
 * segment was an authorization bug (#927) before {@link Request#param} declared its order — and
 * until now they were pinned only from neighbouring modules' integration suites.
 */
class RequestTest {

    /** The one merged view's order, declared once: the URL outranks the caller's appendix,
     * which outranks the body. */
    @Test
    void paramResolvesPathThenQueryThenForm() {
        Request request = new Request();
        request.formFields().put("id", List.of("from-form"));
        assertThat(request.param("id")).isEqualTo("from-form");

        request.queryParams().put("id", List.of("from-query"));
        assertThat(request.param("id")).isEqualTo("from-query");

        request.pathParams().put("id", "from-path");
        assertThat(request.param("id")).isEqualTo("from-path");

        assertThat(request.param("absent")).isNull();
    }

    /** An empty multi-value entry is absence, not an empty answer. */
    @Test
    void paramFallsThroughAnEmptyQueryEntry() {
        Request request = new Request();
        request.queryParams().put("id", List.of());
        request.formFields().put("id", List.of("from-form"));

        assertThat(request.param("id")).isEqualTo("from-form");
    }

    /** HTTP/2 lower-cases every name on the wire; a reader spelling {@code Cookie} must find it. */
    @Test
    void headerNamesMatchWithoutRegardToCase() {
        Request request = new Request().header("Cookie", "a=b");

        assertThat(request.header("cookie")).isEqualTo("a=b");
        assertThat(request.header("COOKIE")).isEqualTo("a=b");
    }

    /** {@code addHeader} appends in arrival order; {@code header} replaces the whole name. */
    @Test
    void headerReplacesAndAddHeaderAppends() {
        Request request = new Request()
                .addHeader("Accept", "text/html")
                .addHeader("accept", "application/json");
        assertThat(request.headers("Accept"))
                .containsExactly("text/html", "application/json");
        assertThat(request.header("Accept")).isEqualTo("text/html");

        request.header("Accept", "text/plain");
        assertThat(request.headers("Accept")).containsExactly("text/plain");
    }

    /** The multi-value read is a copy: a caller cannot edit the request through it. */
    @Test
    void headersReturnsACopy() {
        Request request = new Request().addHeader("Accept", "text/html");
        List<String> read = request.headers("Accept");

        assertThat(read).containsExactly("text/html");
        request.addHeader("Accept", "application/json");
        assertThat(read).containsExactly("text/html");
        assertThat(request.headers("absent")).isEmpty();
    }

    /**
     * The forwarding copy is deep where the values are mutable: the ops shell hands one
     * pipeline's request to another, and edits on the source must not bleed into the target.
     */
    @Test
    void becomeCopyOfIsDeepForTheMultiValueMaps() {
        Request source = new Request().addHeader("Cookie", "a=b").method("POST").uri("/x?y=1")
                .path("/x").query("y=1").remoteAddress("10.0.0.1");
        source.queryParams().put("y", new java.util.ArrayList<>(List.of("1")));

        Request target = new Request();
        target.becomeCopyOf(source);
        source.addHeader("Cookie", "c=d");
        source.queryParams().get("y").add("2");

        assertThat(target.headers("Cookie")).containsExactly("a=b");
        assertThat(target.queryParams().get("y")).containsExactly("1");
        assertThat(target.method()).isEqualTo("POST");
        assertThat(target.uri()).isEqualTo("/x?y=1");
        assertThat(target.path()).isEqualTo("/x");
        assertThat(target.query()).isEqualTo("y=1");
        assertThat(target.remoteAddress()).isEqualTo("10.0.0.1");
    }
}
