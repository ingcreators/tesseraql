package io.tesseraql.yaml.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.config.AppConfig;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WebhookVerifiersTest {

    private static AppConfig config(Map<String, Object> webhooks, Map<String, String> env) {
        return new AppConfig(
                Map.of("tesseraql", Map.of("connectors", Map.of("webhooks", webhooks))), env::get);
    }

    @Test
    void loadsVerifiersWithDefaultsAndOverrides() {
        WebhookVerifiers verifiers = WebhookVerifiers.load(config(Map.of(
                "partner", Map.of("secret", "static"),
                "github", Map.of(
                        "secret", "gh",
                        "signatureHeader", "X-Hub-Signature-256",
                        "timestampHeader", "X-Hub-Timestamp",
                        "idHeader", "X-GitHub-Delivery",
                        "tolerance", "10m")),
                Map.of()));

        WebhookVerifiers.Verifier partner = verifiers.require("partner");
        assertThat(partner.secret()).isEqualTo("static");
        assertThat(partner.signatureHeader()).isEqualTo("X-TesseraQL-Signature");
        assertThat(partner.timestampHeader()).isEqualTo("X-TesseraQL-Timestamp");
        assertThat(partner.idHeader()).isEmpty();
        assertThat(partner.tolerance()).isEqualTo(Duration.ofMinutes(5));

        WebhookVerifiers.Verifier github = verifiers.require("github");
        assertThat(github.signatureHeader()).isEqualTo("X-Hub-Signature-256");
        assertThat(github.idHeader()).contains("X-GitHub-Delivery");
        assertThat(github.tolerance()).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void theSecretResolvesPlaceholdersLazilyPerRead() {
        Map<String, String> env = new HashMap<>();
        WebhookVerifiers verifiers = WebhookVerifiers.load(config(Map.of(
                "partner", Map.of("secret", "${WEBHOOK_SECRET}")), env));

        WebhookVerifiers.Verifier partner = verifiers.require("partner");
        env.put("WEBHOOK_SECRET", "s3cr3t");
        assertThat(partner.secret()).isEqualTo("s3cr3t");
    }

    @Test
    void aMalformedVerifierFailsAtLoadTime() {
        assertThatThrownBy(() -> WebhookVerifiers.load(config(Map.of(
                "bad", "not-a-map"), Map.of())))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-YAML-1105");
    }

    @Test
    void anUnknownVerifierFailsLoudly() {
        WebhookVerifiers verifiers = WebhookVerifiers.load(new AppConfig(Map.of(), name -> null));

        assertThat(verifiers.isEmpty()).isTrue();
        assertThatThrownBy(() -> verifiers.require("missing"))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-YAML-1105");
    }

    @Test
    void aMissingSecretFailsWhenRead() {
        WebhookVerifiers verifiers = WebhookVerifiers.load(config(Map.of(
                "partner", Map.of("signatureHeader", "X-Sig")), Map.of()));

        assertThatThrownBy(() -> verifiers.require("partner").secret())
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-YAML-1105");
    }
}
