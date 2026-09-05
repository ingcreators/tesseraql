package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.net.NetServerOptions;
import io.vertx.core.net.TCPSSLOptions;
import io.vertx.ext.web.handler.BodyHandler;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The transport defaults this edge relies on, and the values it declares over them
 * (docs/http-edge-robustness.md decision 12).
 *
 * <p>Two halves, and they fail at different moments on purpose.
 *
 * <p><b>The inherited constants are pinned</b> so that a Vert.x release moving one arrives as a
 * red test on the dependency's own pull request, where a human is already looking at that
 * version. Every entry carries the reason it matters here; a constant nobody's design depends
 * on does not belong on this list.
 *
 * <p><b>The declared values are read back</b> from the objects this runtime actually builds,
 * because a delta against Vert.x's defaults cannot see a setting whose declared value equals the
 * default it replaces — {@code DEFAULT_IDLE_TIMEOUT} is 0, so declaring 0 would be invisible to
 * any such comparison while being a decision all the same.
 */
class HttpEdgeDefaultsTest {

    /** What this framework would inherit if it declared nothing. */
    @Test
    void theInheritedTransportDefaultsAreWhatThisEdgeWasDesignedAgainst() {
        // Refused any snapshot list past roughly 250 rows, and the largest decision table
        // Studio can render; the reason tesseraql.http.maxFormFields exists.
        assertThat(HttpServerOptions.DEFAULT_MAX_FORM_FIELDS).isEqualTo(256);
        // Refused a textarea of about 2,700 characters of Japanese with an untyped 400.
        assertThat(HttpServerOptions.DEFAULT_MAX_FORM_ATTRIBUTE_SIZE).isEqualTo(8192);
        // Deliberately NOT raised: it bounds the undecoded remainder, not a field, and it is
        // the decoder's only self-termination. Pinned so a release that repurposes it is seen.
        assertThat(HttpServerOptions.DEFAULT_MAX_FORM_BUFFERED_SIZE).isEqualTo(1024);
        // The headroom arithmetic in serverOptions rests on this: one HTTP/1.1 delivery is at
        // most 8 KB, so 64 KB of headroom crosses the body counter on a strictly earlier chunk.
        assertThat(HttpServerOptions.DEFAULT_MAX_CHUNK_SIZE).isEqualTo(8192);
        assertThat(HttpServerOptions.DEFAULT_MAX_HEADER_SIZE).isEqualTo(8192);
        assertThat(HttpServerOptions.DEFAULT_MAX_INITIAL_LINE_LENGTH).isEqualTo(4096);
        // No idle bound at all: a peer that stops reading holds its connection indefinitely.
        assertThat(TCPSSLOptions.DEFAULT_IDLE_TIMEOUT).isZero();
        assertThat(TCPSSLOptions.DEFAULT_TCP_KEEP_ALIVE).isFalse();
        assertThat(NetServerOptions.DEFAULT_ACCEPT_BACKLOG).isEqualTo(-1);
        // The one that has already moved once, silently, and is the reason this test exists:
        // the Vert.x 5 upgrade changed the body limit from unlimited to 10 MB. The framework
        // declares its own now, so the value here is watched rather than relied on.
        assertThat(BodyHandler.DEFAULT_BODY_LIMIT).isEqualTo(10L * 1024 * 1024);
        // Relative to the process working directory, which is why the framework sets it.
        assertThat(BodyHandler.DEFAULT_UPLOADS_DIRECTORY).isEqualTo("file-uploads");
    }

    /** What this runtime declares over them, read back from the object it builds. */
    @Test
    void theRuntimeDeclaresItsOwnFormBounds() {
        HttpEdgeSettings settings = new HttpEdgeSettings(1_048_576L, Path.of("uploads"),
                4096, 1_048_576 + 65_536, 90);

        HttpServerOptions options = TesseraqlHttpServer.serverOptions(settings);

        assertThat(options.getMaxFormFields()).isEqualTo(4096);
        assertThat(options.getMaxFormAttributeSize()).isEqualTo(1_048_576 + 65_536);
        // Read back rather than diffed against the default: DEFAULT_IDLE_TIMEOUT is 0, so a
        // declared value would be invisible to any comparison against a fresh options object
        // while being a decision all the same.
        assertThat(options.getIdleTimeout()).isEqualTo(90);
        // Left inherited, and the assertion says so rather than leaving it unstated.
        assertThat(options.getMaxFormBufferedBytes())
                .isEqualTo(HttpServerOptions.DEFAULT_MAX_FORM_BUFFERED_SIZE);
    }

    /** The opt-out leaves the transport's own default, which is no bound at all. */
    @Test
    void theIdleBoundCanBeOptedOut() {
        HttpEdgeSettings off = new HttpEdgeSettings(1_048_576L, Path.of("uploads"),
                4096, 1_048_576 + 65_536, -1);

        assertThat(TesseraqlHttpServer.serverOptions(off).getIdleTimeout())
                .isEqualTo(TCPSSLOptions.DEFAULT_IDLE_TIMEOUT);
    }

    /** The front door carries the same bound: under the shipped image it is the client's socket. */
    @Test
    void theGatewayFrontDoorCarriesTheSameBound() {
        assertThat(StackRelay.frontOptions(0, false, 120).getIdleTimeout()).isEqualTo(120);
        assertThat(StackRelay.frontOptions(0, false, -1).getIdleTimeout())
                .isEqualTo(TCPSSLOptions.DEFAULT_IDLE_TIMEOUT);
    }

    /**
     * The transport's field count stays above the largest form this framework itself renders.
     *
     * <p>The two numbers have different owners — one is an operator's allocation bound, the
     * other a route's declared page — and nothing at runtime can reconcile them: the edge
     * settings are built before routes compile, and no lint can read an operator's value. So
     * the relationship is asserted over the framework's own defaults at build time, which is
     * the one place both are visible.
     *
     * <p>The guarded quantity is the whole form, not the cap alone. A snapshot list posts one
     * hidden membership field per capped row <em>and</em> one action checkbox per rendered row,
     * inside a single form, plus its chrome.
     */
    @Test
    void theShippedFieldCountExceedsTheLargestFormThisFrameworkRenders() {
        io.tesseraql.yaml.model.PageSpec widest = new io.tesseraql.yaml.model.PageSpec(
                io.tesseraql.yaml.model.PageSpec.SNAPSHOT, null, null, false, null, null);
        int worstCase = widest.effectiveCap() + widest.effectiveSize() + CHROME_HEADROOM;

        assertThat(SHIPPED_MAX_FORM_FIELDS)
                .as("tesseraql.http.maxFormFields must stay above a shipped page's own field"
                        + " budget (%d membership + %d rendered rows + %d chrome), or a list"
                        + " page meets the transport's refusal instead of its own",
                        widest.effectiveCap(), widest.effectiveSize(), CHROME_HEADROOM)
                .isGreaterThan(worstCase);
    }

    /** The default in {@code TesseraqlRuntime.maxFormFields}, restated where it is guarded. */
    private static final int SHIPPED_MAX_FORM_FIELDS = 10_000;

    /** A form's own inputs beside the rows: the CSRF token, the action, filters, sort, search. */
    private static final int CHROME_HEADROOM = 64;
}
