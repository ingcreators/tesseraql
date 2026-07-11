package io.tesseraql.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The {@code work/embedded-db.jdbc} first-login hand-off marker: what {@code serve --embedded-db}
 * writes, what {@code identity-schema --app} reads back, and the resolution precedence — a
 * resolvable-and-reachable config beats the marker, and a stale marker (its database no longer
 * answers) is ignored. Probes are faked here; the real end-to-end run is in
 * {@link EmbeddedDbServeIntegrationTest}.
 */
class EmbeddedDbMarkerTest {

    private static final String MARKER_URL = "jdbc:postgresql://localhost:54321/postgres?user=postgres";
    private static final String CONFIG_URL = "jdbc:postgresql://localhost:5432/demo";

    @Test
    void writeCreatesTheMarkerAndReadReturnsTheUrl(@TempDir Path app) throws Exception {
        EmbeddedDbMarker.write(app, MARKER_URL);

        assertThat(app.resolve("work/embedded-db.jdbc")).exists();
        assertThat(Files.readString(app.resolve("work/embedded-db.jdbc")).trim())
                .isEqualTo(MARKER_URL);
        assertThat(EmbeddedDbMarker.read(app)).contains(MARKER_URL);
    }

    @Test
    void writeOverwritesAPreviousStart(@TempDir Path app) {
        EmbeddedDbMarker.write(app, "jdbc:postgresql://localhost:1111/postgres");
        EmbeddedDbMarker.write(app, MARKER_URL);

        assertThat(EmbeddedDbMarker.read(app)).contains(MARKER_URL);
    }

    @Test
    void readIsEmptyWhenTheMarkerIsMissingOrBlank(@TempDir Path app) throws Exception {
        assertThat(EmbeddedDbMarker.read(app)).isEmpty();

        Files.createDirectories(app.resolve("work"));
        Files.writeString(app.resolve("work/embedded-db.jdbc"), "  \n");
        assertThat(EmbeddedDbMarker.read(app)).isEmpty();
    }

    @Test
    void deleteIsBestEffortAndRemovesTheMarker(@TempDir Path app) {
        EmbeddedDbMarker.delete(app); // nothing to delete — no exception

        EmbeddedDbMarker.write(app, MARKER_URL);
        EmbeddedDbMarker.delete(app);
        assertThat(app.resolve("work/embedded-db.jdbc")).doesNotExist();
    }

    @Test
    void withoutAMarkerNothingIsProbedAndTheExistingResolutionRuns(@TempDir Path app) {
        List<String> probed = new ArrayList<>();

        assertThat(EmbeddedDbMarker.pick(app, CONFIG_URL, "demo", "demo", (url, user, pass) -> {
            probed.add(url);
            return true;
        })).isEmpty();
        assertThat(probed).isEmpty();
    }

    @Test
    void aReachableConfigBeatsTheMarker(@TempDir Path app) {
        EmbeddedDbMarker.write(app, MARKER_URL);

        assertThat(EmbeddedDbMarker.pick(app, CONFIG_URL, "demo", "demo",
                (url, user, pass) -> CONFIG_URL.equals(url))).isEmpty();
    }

    @Test
    void anUnreachableConfigFallsBackToTheRunningMarker(@TempDir Path app) {
        EmbeddedDbMarker.write(app, MARKER_URL);

        assertThat(EmbeddedDbMarker.pick(app, CONFIG_URL, "demo", "demo",
                (url, user, pass) -> MARKER_URL.equals(url))).contains(MARKER_URL);
    }

    @Test
    void anUnresolvableConfigFallsBackToTheRunningMarker(@TempDir Path app) {
        EmbeddedDbMarker.write(app, MARKER_URL);

        assertThat(EmbeddedDbMarker.pick(app, null, null, null,
                (url, user, pass) -> MARKER_URL.equals(url))).contains(MARKER_URL);
    }

    @Test
    void aStaleMarkerIsIgnored(@TempDir Path app) {
        EmbeddedDbMarker.write(app, MARKER_URL);

        assertThat(EmbeddedDbMarker.pick(app, null, null, null, (url, user, pass) -> false))
                .isEmpty();
    }
}
