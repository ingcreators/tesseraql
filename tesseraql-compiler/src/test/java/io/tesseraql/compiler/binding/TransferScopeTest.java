package io.tesseraql.compiler.binding;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.core.files.FileTransferService;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * A route sees its own transfers and nothing else (docs/edge-hygiene.md E0). The route half is
 * proven end to end by {@code TransferRouteScopeIntegrationTest}; the application half is
 * here, because one runtime hosts one application and no integration test can start a second
 * one with a route of the same id against the same table.
 */
class TransferScopeTest {

    @Test
    void theRouteThatCreatedTheTransferSeesIt() {
        assertThat(TransferScope.own(serving("shop", "orders.export"), "t-1", "shop",
                "orders.export")).isPresent();
    }

    @Test
    void anotherRouteOfTheSameApplicationDoesNot() {
        assertThat(TransferScope.own(serving("shop", "orders.export"), "t-1", "shop",
                "orders.exportPublic")).isEmpty();
    }

    @Test
    void theSameRouteIdInAnotherApplicationDoesNot() {
        assertThat(TransferScope.own(serving("shop", "orders.export"), "t-1", "warehouse",
                "orders.export")).isEmpty();
    }

    @Test
    void noServiceIsNoTransfer() {
        assertThat(TransferScope.own(null, "t-1", "shop", "orders.export")).isEmpty();
    }

    /** A service that answers {@code status} with one transfer of the given owner. */
    private static FileTransferService serving(String appName, String routeId) {
        FileTransferService.TransferStatus status = new FileTransferService.TransferStatus(
                "t-1", routeId, appName, "export", "COMPLETED", 2, null, List.of(),
                "orders.csv", false);
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
