package io.tesseraql.compiler.binding;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.core.files.FileTransferService;
import io.tesseraql.core.tenant.TenantContext;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.RuntimeContext;
import io.tesseraql.pipeline.TesseraqlProperties;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * A route sees its own transfers and nothing else (docs/edge-hygiene.md E0). The route half is
 * proven end to end by {@code TransferRouteScopeIntegrationTest}; the application half is
 * here, because one runtime hosts one application and no integration test can start a second
 * one with a route of the same id against the same table.
 *
 * <p>And the tenant half (docs/audit-low-leads.md G31): a transfer recorded under a tenant is
 * its own only for a request resolved to that tenant.
 */
class TransferScopeTest {

    private static RuntimeContext context;

    @BeforeAll
    static void start() throws Exception {
        context = new RuntimeContext();
        context.start();
    }

    @AfterAll
    static void stop() {
        context.close();
    }

    @Test
    void theRouteThatCreatedTheTransferSeesIt() {
        assertThat(TransferScope.own(serving("shop", "orders.export", null), "t-1", "shop",
                "orders.export", exchange(null))).isPresent();
    }

    @Test
    void anotherRouteOfTheSameApplicationDoesNot() {
        assertThat(TransferScope.own(serving("shop", "orders.export", null), "t-1", "shop",
                "orders.exportPublic", exchange(null))).isEmpty();
    }

    @Test
    void theSameRouteIdInAnotherApplicationDoesNot() {
        assertThat(TransferScope.own(serving("shop", "orders.export", null), "t-1", "warehouse",
                "orders.export", exchange(null))).isEmpty();
    }

    @Test
    void noServiceIsNoTransfer() {
        assertThat(TransferScope.own(null, "t-1", "shop", "orders.export", exchange(null)))
                .isEmpty();
    }

    @Test
    void theTenantThatStartedTheTransferSeesItAndAnotherDoesNot() {
        assertThat(TransferScope.own(serving("shop", "orders.export", "acme"), "t-1", "shop",
                "orders.export", exchange("acme"))).isPresent();
        assertThat(TransferScope.own(serving("shop", "orders.export", "acme"), "t-1", "shop",
                "orders.export", exchange("globex"))).isEmpty();
        // A request resolved to no tenant sees no tenant's transfer, and a transfer recorded
        // under no tenant is not a tenant's.
        assertThat(TransferScope.own(serving("shop", "orders.export", "acme"), "t-1", "shop",
                "orders.export", exchange(null))).isEmpty();
        assertThat(TransferScope.own(serving("shop", "orders.export", null), "t-1", "shop",
                "orders.export", exchange("acme"))).isEmpty();
    }

    private static Exchange exchange(String tenantId) {
        Exchange exchange = new Exchange(context.beans());
        if (tenantId != null) {
            exchange.setProperty(TesseraqlProperties.TENANT, new TenantContext(tenantId, Map.of()));
        }
        return exchange;
    }

    /** A service that answers {@code status} with one transfer of the given owner. */
    private static FileTransferService serving(String appName, String routeId, String tenantId) {
        FileTransferService.TransferStatus status = new FileTransferService.TransferStatus(
                "t-1", routeId, appName, "export", "COMPLETED", 2, null, List.of(),
                "orders.csv", false, null, tenantId);
        return (FileTransferService) Proxy.newProxyInstance(
                FileTransferService.class.getClassLoader(),
                new Class<?>[]{FileTransferService.class},
                (proxy, method, args) -> {
                    if ("status".equals(method.getName())) {
                        return "t-1".equals(args[0]) ? Optional.of(status) : Optional.empty();
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
