package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.security.SecurityConfig;
import io.tesseraql.yaml.config.AppConfig;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Policy parsing, incl. the deny-all diagnostic for an unrecognized rule (silent-tolerance O10). */
class SecurityConfigFactoryTest {

    @Test
    void aRecognizedRuleBuildsARoleRule() {
        SecurityConfig config = SecurityConfigFactory.build(new AppConfig(Map.of(
                "tesseraql", Map.of("security", Map.of("policies", Map.of(
                        "app.read", Map.of("anyOf", List.of(Map.of("role", "READER")))))))));

        assertThat(config.policies()).containsKey("app.read");
        assertThat(config.policies().get("app.read").anyOf()).hasSize(1);
    }

    /**
     * The namespace fence (docs/stack-shells.md structural decision 1, TQL-YAML-1406) holds at
     * boot as well as at lint: a policy referencing a permission code that does not begin with
     * the application's own name — or that sits under the framework's {@code tql.} mark — fails
     * the start. Role rules stay free, and without a declared name the name rule owns the
     * refusal, so nothing is judged against a namespace that does not exist.
     */
    @Test
    void aPolicyCodeOutsideTheApplicationsNamespaceFailsTheBoot() {
        java.util.function.Function<String, AppConfig> config = code -> new AppConfig(Map.of(
                "tesseraql", Map.of(
                        "app", Map.of("name", "orders"),
                        "security", Map.of("policies", Map.of(
                                "orders.read",
                                Map.of("anyOf", List.of(Map.of("permission", code))))))));

        assertThat(SecurityConfigFactory.build(config.apply("orders.approve")).policies())
                .containsKey("orders.read");
        for (String refused : new String[]{"approve", "tql.ops.view.orders", "billing.approve"}) {
            org.assertj.core.api.Assertions
                    .assertThatThrownBy(() -> SecurityConfigFactory.build(config.apply(refused)))
                    .as(refused)
                    .isInstanceOf(io.tesseraql.core.error.TqlException.class)
                    .hasMessageContaining("TQL-YAML-1406");
        }
        // No declared name: parsed without the fence — the name rule reports separately.
        assertThat(SecurityConfigFactory.build(new AppConfig(Map.of(
                "tesseraql", Map.of("security", Map.of("policies", Map.of(
                        "x", Map.of("anyOf", List.of(Map.of("permission", "loose"))))))))))
                .isNotNull();
    }

    @Test
    void anUnrecognizedRuleYieldsAZeroRuleDenyAllPolicy() {
        // `permissions:` (plural) is a typo for `permission:`; the rule is unrecognized and
        // dropped. The policy is now logged, but its deny-all behavior (no rules) is preserved.
        SecurityConfig config = SecurityConfigFactory.build(new AppConfig(Map.of(
                "tesseraql", Map.of("security", Map.of("policies", Map.of(
                        "app.read",
                        Map.of("anyOf", List.of(Map.of("permissions", "items:read")))))))));

        assertThat(config.policies().get("app.read").anyOf()).isEmpty();
    }

    private static AppConfig mtlsClient(Map<String, Object> client) {
        return new AppConfig(Map.of("tesseraql", Map.of("security", Map.of("mtls", Map.of(
                "forwardedHeader", "ssl-client-cert",
                "clients", Map.of("billing", client))))));
    }

    @Test
    void aTypedSanMatcherIsParsedWithItsKind() {
        io.tesseraql.security.mtls.MtlsConfig.MtlsClient client = SecurityConfigFactory
                .build(mtlsClient(Map.of("sanUri", "spiffe://acme/ns/default/sa/billing")))
                .mtls().clients().get("billing");

        assertThat(client.san().type())
                .isEqualTo(io.tesseraql.security.mtls.MtlsConfig.SanType.URI);
        assertThat(client.san().value()).isEqualTo("spiffe://acme/ns/default/sa/billing");
    }

    /**
     * The removed untyped {@code san:} fails the boot instead of being ignored: dropping it would
     * leave the client with no matcher, and a service caller that silently stops authenticating is
     * the failure the typed grammar exists to prevent (silent-tolerance Wave S follow-up).
     */
    @Test
    void theRemovedUntypedSanFailsTheBoot() {
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> SecurityConfigFactory
                        .build(mtlsClient(Map.of("san", "spiffe://acme/ns/default/sa/billing"))))
                .isInstanceOf(io.tesseraql.core.error.TqlException.class)
                .hasMessageContaining("TQL-SEC-4066")
                .hasMessageContaining("sanDns/sanUri/sanEmail/sanIp");
    }
}
