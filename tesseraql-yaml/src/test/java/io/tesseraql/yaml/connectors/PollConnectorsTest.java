package io.tesseraql.yaml.connectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.config.AppConfig;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PollConnectorsTest {

    private static AppConfig config(Map<String, Object> poll, Map<String, String> env) {
        return new AppConfig(
                Map.of("tesseraql", Map.of("connectors", Map.of("poll", poll))), env::get);
    }

    @Test
    void loadsAllowListAndCredentials() {
        PollConnectors poll = PollConnectors.load(config(Map.of(
                "allowedHosts", List.of("sftp.partner.example", "*.internal.example"),
                "credentials", Map.of(
                        "partner", Map.of("username", "svc", "password", "static-pass"))),
                Map.of()));

        assertThat(poll.isEmpty()).isFalse();
        assertThat(poll.allowedHosts())
                .containsExactly("sftp.partner.example", "*.internal.example");
        assertThat(poll.requireCredential("partner").require("username")).isEqualTo("svc");
        assertThat(poll.requireCredential("partner").require("password")).isEqualTo("static-pass");
    }

    @Test
    void remoteHostsAreDenyByDefaultWithExactAndWildcardMatching() {
        PollConnectors poll = PollConnectors.load(config(Map.of(
                "allowedHosts", List.of("sftp.partner.example", "*.internal.example")), Map.of()));

        assertThat(poll.isHostAllowed("sftp.partner.example")).isTrue();
        assertThat(poll.isHostAllowed("eu.internal.example")).isTrue();
        assertThat(poll.isHostAllowed("internal.example")).isFalse();
        assertThat(poll.isHostAllowed("evil.test")).isFalse();
    }

    @Test
    void credentialSettingsResolvePlaceholdersLazilyPerRead() {
        Map<String, String> env = new HashMap<>();
        PollConnectors poll = PollConnectors.load(config(Map.of(
                "credentials", Map.of(
                        "partner", Map.of("username", "svc", "password", "${SFTP_PASS}"))),
                env));

        PollConnectors.Credential partner = poll.requireCredential("partner");
        env.put("SFTP_PASS", "s3cr3t");
        assertThat(partner.require("password")).isEqualTo("s3cr3t");
    }

    @Test
    void aMalformedCredentialFailsAtLoadTime() {
        assertThatThrownBy(() -> PollConnectors.load(config(Map.of(
                "credentials", Map.of("bad", "not-a-map")), Map.of())))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-YAML-1104");
    }

    @Test
    void anUnknownCredentialFailsLoudly() {
        PollConnectors poll = PollConnectors.load(new AppConfig(Map.of(), name -> null));

        assertThat(poll.isEmpty()).isTrue();
        assertThat(poll.isHostAllowed("sftp.partner.example")).isFalse();
        assertThatThrownBy(() -> poll.requireCredential("missing"))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-BATCH-5310");
    }
}
