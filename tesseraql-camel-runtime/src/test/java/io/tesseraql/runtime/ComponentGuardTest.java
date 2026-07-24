package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.config.ComponentPolicy;
import java.util.Map;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultComponent;
import org.junit.jupiter.api.Test;

/** Registration-time enforcement of the component policy (docs/component-guard.md). */
class ComponentGuardTest {

    /** A stand-in component carrying a dangerous name. */
    private static final class Stub extends DefaultComponent {
        @Override
        protected org.apache.camel.Endpoint createEndpoint(String uri, String remaining,
                Map<String, Object> parameters) {
            throw new UnsupportedOperationException();
        }
    }

    @Test
    void aBaselineDeniedComponentFailsRegistrationEvenWithoutConfig() throws Exception {
        try (DefaultCamelContext context = new DefaultCamelContext()) {
            ComponentGuard.install(context,
                    ComponentPolicy.from(new AppConfig(Map.of(), name -> null)),
                    java.util.Set.of());

            // The refusal propagates as-is out of the registration call — in the runtime
            // that aborts boot (registration is not rolled back; the context never starts).
            assertThatThrownBy(() -> context.addComponent("exec", new Stub()))
                    .isInstanceOf(TqlException.class)
                    .hasMessageContaining("TQL-SEC-4138")
                    .hasMessageContaining("exec");
        }
    }

    @Test
    void configDeniedAndAllowedListsAreEnforced() throws Exception {
        AppConfig config = new AppConfig(Map.of("tesseraql", Map.of("camel",
                Map.of("components", Map.of(
                        "allowed", java.util.List.of("smtp"),
                        "denied", java.util.List.of("ftp"))))),
                name -> null);
        try (DefaultCamelContext context = new DefaultCamelContext()) {
            ComponentGuard.install(context, ComponentPolicy.from(config), java.util.Set.of());

            assertThatThrownBy(() -> context.addComponent("ftp", new Stub()))
                    .hasMessageContaining("denied");
            assertThatThrownBy(() -> context.addComponent("kafka", new Stub()))
                    .hasMessageContaining("allowed");
            context.addComponent("smtp", new Stub());
            context.addComponent("direct", new Stub());
            assertThat(context.hasComponent("smtp")).isNotNull();
        }
    }
}
