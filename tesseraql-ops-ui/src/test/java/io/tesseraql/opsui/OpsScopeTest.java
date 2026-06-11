package io.tesseraql.opsui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class OpsScopeTest {

    @Test
    void scopedGrantsLimitVisibilityToTheNamedApps() {
        Predicate<String> scope = OpsScope.allowedApps(
                List.of("ops.batch.view", "ops.app.billing", "ops.app.shop"));
        assertThat(scope.test("billing")).isTrue();
        assertThat(scope.test("shop")).isTrue();
        assertThat(scope.test("hr")).isFalse();
        assertThat(scope.test(null)).isFalse();
    }

    @Test
    void wildcardGrantSeesEveryApp() {
        Predicate<String> scope = OpsScope.allowedApps(List.of("ops.app.*"));
        assertThat(scope.test("billing")).isTrue();
        assertThat(scope.test("anything")).isTrue();
    }

    @Test
    void withoutScopedGrantsNothingIsVisible() {
        // Deny by default: the ops.batch.view entry permission opens the console, but batch
        // data appears only for explicitly granted apps (or ops.app.*).
        assertThat(OpsScope.allowedApps(List.of("ops.batch.view")).test("billing")).isFalse();
        assertThat(OpsScope.allowedApps(null).test("billing")).isFalse();
        assertThat(OpsScope.allowedApps("not-a-list").test("billing")).isFalse();
    }
}
