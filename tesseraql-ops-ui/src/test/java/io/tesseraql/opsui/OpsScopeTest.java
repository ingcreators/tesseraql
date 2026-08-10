package io.tesseraql.opsui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class OpsScopeTest {

    private static final Set<String> SERVED = Set.of("billing", "shop");

    @Test
    void scopedGrantsLimitVisibilityToTheNamedApps() {
        Predicate<String> scope = OpsScope.allowedApps(
                List.of("ops.batch.view", "ops.app.billing", "ops.app.shop"), SERVED);
        assertThat(scope.test("billing")).isTrue();
        assertThat(scope.test("shop")).isTrue();
        assertThat(scope.test("hr")).isFalse();
        assertThat(scope.test(null)).isFalse();
    }

    @Test
    void wildcardGrantSeesEveryAppTheRuntimeServes() {
        Predicate<String> scope = OpsScope.allowedApps(List.of("ops.app.*"), SERVED);
        assertThat(scope.test("billing")).isTrue();
        assertThat(scope.test("shop")).isTrue();
    }

    @Test
    void withoutScopedGrantsNothingIsVisible() {
        // Deny by default: the ops.batch.view entry permission opens the console, but batch
        // data appears only for explicitly granted apps (or ops.app.*).
        assertThat(OpsScope.allowedApps(List.of("ops.batch.view"), SERVED).test("billing"))
                .isFalse();
        assertThat(OpsScope.allowedApps(null, SERVED).test("billing")).isFalse();
        assertThat(OpsScope.allowedApps("not-a-list", SERVED).test("billing")).isFalse();
    }

    /**
     * The console reports on its own runtime's applications and no others
     * (docs/app-isolation-model.md decision 4).
     *
     * <p>The ops tables live in a business database several runtimes may share, so a grant on its
     * own was enough to list another runtime's jobs, executions and transfers — rows this console
     * has no relationship with. A wildcard grant is the sharpest case: it used to mean "every app
     * in the database", and now means "every app this runtime serves".
     */
    @Test
    void anAppThisRuntimeDoesNotServeIsInvisibleHoweverBroadTheGrant() {
        assertThat(OpsScope.allowedApps(List.of("ops.app.*"), SERVED).test("someone-elses-app"))
                .as("a wildcard grant stops at what this runtime serves")
                .isFalse();

        assertThat(OpsScope
                .allowedApps(List.of("ops.app.someone-elses-app"), SERVED)
                .test("someone-elses-app"))
                .as("naming it explicitly does not reach another runtime's rows either")
                .isFalse();
    }

    @Test
    void aRuntimeServingNothingShowsNothing() {
        assertThat(OpsScope.allowedApps(List.of("ops.app.*"), Set.of()).test("billing")).isFalse();
        assertThat(OpsScope.allowedApps(List.of("ops.app.*"), null).test("billing")).isFalse();
    }
}
