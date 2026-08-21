package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.connectors.FileConnectors;
import io.tesseraql.yaml.model.PollSpec;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * How a {@code poll:} trigger is wired: where a local source may point, which client a transport
 * gets, and what a {@code move:} may name.
 *
 * <p>The transports' own rules are asserted where they are decided — {@link SftpClientTest},
 * {@link FtpsClientTest} — and the cycle's rules in {@link PollLoopTest}. What is left here is the
 * wiring decision itself.
 */
class PollSourcesTest {

    @TempDir
    Path home;

    @Test
    void aLocalPathIsAnchoredUnderADeclaredRoot() {
        PollSource source = sources(Map.of("allowedPaths", List.of("inbox")))
                .sourceFor("poll.job", local("inbox/orders"));

        assertThat(source).isInstanceOf(LocalPollSource.class);
        assertThat(((LocalPollSource) source).directory())
                .isEqualTo(home.resolve("inbox/orders").toAbsolutePath());
    }

    @Test
    void aLocalPathThatClimbsOutOfEveryRootIsRefused() {
        PollSources sources = sources(Map.of("allowedPaths", List.of("inbox")));

        // A poll source does not only read: it moves what it reads, so a path that escapes the
        // root relocates a live directory's contents into .done.
        assertThatThrownBy(() -> sources.sourceFor("poll.job", local("inbox/../../secret")))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("outside every");
    }

    @Test
    void aLocalSourceWithNoDeclaredRootIsRefused() {
        PollSources sources = sources(Map.of());

        assertThatThrownBy(() -> sources.sourceFor("poll.job", local("anywhere")))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("allowedPaths");
    }

    @Test
    void aRemoteTransportGetsItsOwnClient() {
        PollSources sources = sources(Map.of(
                "allowedHosts", List.of("partner.example"),
                "credentials", Map.of("partner", Map.of("username", "svc", "password", "s")),
                "trustStore", Map.of("file", "ca.p12", "password", "s")));

        assertThat(sources.sourceFor("poll.job", remote("sftp"))).isInstanceOf(
                RemotePollSource.class);
        assertThat(sources.sourceFor("poll.job", remote("ftps"))).isInstanceOf(
                RemotePollSource.class);
    }

    @Test
    void anUnknownTransportIsRefusedRatherThanTreatedAsLocal() {
        PollSources sources = sources(Map.of("allowedPaths", List.of("inbox")));
        PollSpec unknown = new PollSpec("webdav", null, null, "inbox", null, null, null, null,
                null, null);

        assertThatThrownBy(() -> sources.sourceFor("poll.job", unknown))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported poll transport");
    }

    /**
     * A {@code move:} names a directory, not a path and not an expression.
     *
     * <p>A value like {@code ${file:parent}/../../escaped} would relocate the polled file outside
     * the poll tree entirely — an arbitrary-destination write of its contents from a plain YAML
     * scalar — so it is refused rather than escaped.
     */
    @Test
    void anArchiveDirectoryThatIsAPathOrAPlaceholderIsRefused() {
        assertThatThrownBy(() -> PollSources.archiveDirectory("move",
                "${file:parent}/../../escaped"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("relative directory name");
        assertThatThrownBy(() -> PollSources.archiveDirectory("moveFailed", "../outside"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("relative directory name");
        assertThatThrownBy(() -> PollSources.archiveDirectory("move", "/absolute"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("relative directory name");
        assertThat(PollSources.archiveDirectory("move", ".done")).isEqualTo(".done");
    }

    private PollSources sources(Map<String, Object> poll) {
        Map<String, Object> block = new LinkedHashMap<>(poll);
        AppConfig config = new AppConfig(
                Map.of("tesseraql", Map.of("connectors", Map.of("poll", block))), name -> null);
        return new PollSources(List.of(), FileConnectors.poll(config), "app", Map.of(), home,
                home.resolve("work"), new io.tesseraql.opsui.PollSourceStatus(),
                // Wiring is decided from the declaration; nothing touches the datasource until a
                // source that declares consumeOnce actually wires.
                new io.tesseraql.operations.poll.JdbcPollConsumedStore(null,
                        java.time.Duration.ofDays(30)));
    }

    private static PollSpec local(String path) {
        return new PollSpec("local", null, null, path, null, null, null, null, null, null);
    }

    private static PollSpec remote(String transport) {
        return new PollSpec(transport, "partner.example", null, "/outbound", "partner", null,
                null, null, null, null);
    }
}
