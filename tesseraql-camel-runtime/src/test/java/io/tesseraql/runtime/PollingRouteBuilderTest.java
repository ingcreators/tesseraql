package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.connectors.PollConnectors;
import io.tesseraql.yaml.model.PollSpec;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The consumer-URI construction for {@code poll:} triggers: with
 * {@code tesseraql.connectors.poll.knownHostsFile} set, the SFTP endpoint verifies the server's
 * SSH host key against that file (strict checking); without it, the historical unchecked
 * behavior stays, so existing apps keep polling.
 */
class PollingRouteBuilderTest {

    @TempDir
    Path home;

    @Test
    void sftpVerifiesTheHostKeyAgainstAConfiguredKnownHostsFile() {
        String uri = builder(Map.of(
                "allowedHosts", List.of("sftp.partner.example"),
                "knownHostsFile", "security/known_hosts")).endpointUri(sftp());

        assertThat(uri)
                .startsWith("sftp://sftp.partner.example:22/outbound?")
                // The path resolves against the app home, like other configured file paths.
                .contains("knownHostsFile="
                        + home.resolve("security/known_hosts").toAbsolutePath())
                .contains("strictHostKeyChecking=yes")
                .doesNotContain("strictHostKeyChecking=no");
    }

    @Test
    void anAbsoluteKnownHostsFilePassesThroughUnchanged() {
        Path pinned = home.resolve("etc/ssh/known_hosts").toAbsolutePath();
        String uri = builder(Map.of("knownHostsFile", pinned.toString())).endpointUri(sftp());

        assertThat(uri).contains("knownHostsFile=" + pinned)
                .contains("strictHostKeyChecking=yes");
    }

    @Test
    void withoutAKnownHostsFileTheHostKeyStaysUnchecked() {
        String uri = builder(Map.of("allowedHosts", List.of("sftp.partner.example")))
                .endpointUri(sftp());

        assertThat(uri).contains("strictHostKeyChecking=no")
                .doesNotContain("knownHostsFile=");
    }

    private PollingRouteBuilder builder(Map<String, Object> poll) {
        AppConfig config = new AppConfig(
                Map.of("tesseraql", Map.of("connectors", Map.of("poll", poll))), name -> null);
        return new PollingRouteBuilder(List.of(), PollConnectors.load(config), "app", Map.of(),
                home);
    }

    private static PollSpec sftp() {
        return new PollSpec("sftp", "sftp.partner.example", null, "/outbound", null, null, null,
                null, null);
    }
}
