package io.tesseraql.compiler.binding;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.security.Principal;
import java.util.List;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.Test;

/**
 * The opt-in access log line (roadmap Phase 45): method, path, status, duration, route id and
 * the authenticated user. (MDC correlation is asserted in tesseraql-cli, where the structured
 * log provider supplies a real MDC adapter — this module has none on the test classpath.)
 */
class RouteTelemetryTest {

    @Test
    void theAccessLineCarriesRequestIdentityAndPrincipal() throws Exception {
        try (DefaultCamelContext context = new DefaultCamelContext()) {
            Exchange exchange = new DefaultExchange(context);
            RouteTelemetry telemetry = new RouteTelemetry("users.search", "GET", "/api/users",
                    "app", true);

            assertThat(telemetry.accessLine(exchange, 200, 12))
                    .isEqualTo("GET /api/users 200 12ms route=users.search");

            exchange.setProperty(TesseraqlProperties.PRINCIPAL, new Principal(
                    "sub-1", "alice", "Alice", null, List.of(), List.of(), List.of(),
                    Map.of()));
            assertThat(telemetry.accessLine(exchange, 403, 3))
                    .isEqualTo("GET /api/users 403 3ms route=users.search user=alice");
        }
    }
}
