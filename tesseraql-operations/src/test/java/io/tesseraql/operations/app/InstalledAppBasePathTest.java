package io.tesseraql.operations.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The prefix an application is addressed under (docs/suite-architecture.md Decision 12).
 *
 * <p>Normalised on the way in so that concatenating it with a route path is always well-formed, and
 * so the gateway compares prefixes rather than parsing them — the origin root is the empty string,
 * not {@code "/"}, for the same reason `BasePaths` made that choice for an application's own view.
 */
class InstalledAppBasePathTest {

    @Test
    void theDefaultIsTheApplicationsOwnPrefix() {
        assertThat(entry(null).basePath()).isEqualTo("/apps/orders");
        assertThat(entry("").basePath()).isEqualTo("/apps/orders");
        assertThat(entry("   ").basePath()).isEqualTo("/apps/orders");
    }

    @Test
    void theOriginRootIsTheEmptyPrefix() {
        assertThat(entry("/").basePath()).isEmpty();
    }

    @Test
    void aDeclaredPrefixIsNormalisedNotRejected() {
        assertThat(entry("shop").basePath()).isEqualTo("/shop");
        assertThat(entry("/shop/").basePath()).isEqualTo("/shop");
        assertThat(entry("/shop///").basePath()).isEqualTo("/shop");
    }

    private static InstalledApp entry(String basePath) {
        return new InstalledApp("orders", "1.0.0", "orders/1.0.0", List.of(), basePath);
    }
}
