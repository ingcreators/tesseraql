package io.tesseraql.yaml.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.config.AppConfig;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HttpOutboundTest {

    private static AppConfig config(Map<String, Object> outbound, Map<String, String> env) {
        return new AppConfig(
                Map.of("tesseraql", Map.of("http", Map.of("outbound", outbound))), env::get);
    }

    @Test
    void loadsAllowListTimeoutsCircuitBreakerAndCredentials() {
        HttpOutbound outbound = HttpOutbound.load(config(Map.of(
                "allowedHosts", List.of("api.partner.example", "*.internal.example"),
                "connectTimeout", "2s",
                "requestTimeout", "10s",
                "circuitBreaker", Map.of("failureThreshold", 3, "openDuration", "15s"),
                "credentials", Map.of(
                        "partner", Map.of("type", "bearer", "token", "static-token"))),
                Map.of()));

        assertThat(outbound.isEmpty()).isFalse();
        assertThat(outbound.allowedHosts())
                .containsExactly("api.partner.example", "*.internal.example");
        assertThat(outbound.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(outbound.requestTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(outbound.circuitBreakerThreshold()).isEqualTo(3);
        assertThat(outbound.circuitBreakerOpenDuration()).isEqualTo(Duration.ofSeconds(15));
        assertThat(outbound.requireCredential("partner").type()).isEqualTo("bearer");
        assertThat(outbound.requireCredential("partner").require("token"))
                .isEqualTo("static-token");
    }

    @Test
    void egressIsDenyByDefaultWithExactAndWildcardMatching() {
        HttpOutbound outbound = HttpOutbound.load(config(Map.of(
                "allowedHosts", List.of("api.partner.example", "*.internal.example")), Map.of()));

        // Exact host and any sub-domain of a wildcard are allowed (case-insensitively)...
        assertThat(outbound.isHostAllowed("api.partner.example")).isTrue();
        assertThat(outbound.isHostAllowed("API.PARTNER.EXAMPLE")).isTrue();
        assertThat(outbound.isHostAllowed("a.internal.example")).isTrue();
        assertThat(outbound.isHostAllowed("a.b.internal.example")).isTrue();
        // ...everything else is denied, including the bare wildcard domain and look-alikes.
        assertThat(outbound.isHostAllowed("internal.example")).isFalse();
        assertThat(outbound.isHostAllowed("api.partner.example.evil.test")).isFalse();
        assertThat(outbound.isHostAllowed("evil.test")).isFalse();
        assertThat(outbound.isHostAllowed(null)).isFalse();
    }

    @Test
    void appliesDefaultTimeoutsAndCircuitBreakerWhenUnspecified() {
        HttpOutbound outbound = HttpOutbound.load(config(Map.of(
                "allowedHosts", List.of("api.partner.example")), Map.of()));

        assertThat(outbound.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(outbound.requestTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(outbound.circuitBreakerThreshold()).isEqualTo(5);
        assertThat(outbound.circuitBreakerOpenDuration()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void credentialSettingsResolvePlaceholdersLazilyPerRead() {
        Map<String, String> env = new HashMap<>();
        HttpOutbound outbound = HttpOutbound.load(config(Map.of(
                "allowedHosts", List.of("api.partner.example"),
                "credentials", Map.of(
                        "partner", Map.of("type", "bearer", "token", "${PARTNER_TOKEN}"))),
                env));

        // The secret is not needed (nor resolved) at load time, and resolves on read.
        HttpOutbound.Credential partner = outbound.requireCredential("partner");
        env.put("PARTNER_TOKEN", "t0ken");
        assertThat(partner.require("token")).isEqualTo("t0ken");
    }

    @Test
    void anUnsupportedCredentialTypeFailsAtLoadTime() {
        assertThatThrownBy(() -> HttpOutbound.load(config(Map.of(
                "credentials", Map.of("bad", Map.of("type", "kerberos"))), Map.of())))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-YAML-1103");
    }

    @Test
    void anUnknownCredentialFailsLoudly() {
        HttpOutbound outbound = HttpOutbound.load(new AppConfig(Map.of(), name -> null));

        assertThat(outbound.isEmpty()).isTrue();
        assertThat(outbound.isHostAllowed("api.partner.example")).isFalse();
        assertThatThrownBy(() -> outbound.requireCredential("missing"))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-BATCH-5308");
    }
}
