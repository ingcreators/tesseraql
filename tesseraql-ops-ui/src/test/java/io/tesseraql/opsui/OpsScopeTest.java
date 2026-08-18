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
        Predicate<String> scope = OpsScope.view(
                List.of("tql.ops.view.billing", "tql.ops.view.shop"), SERVED);
        assertThat(scope.test("billing")).isTrue();
        assertThat(scope.test("shop")).isTrue();
        assertThat(scope.test("hr")).isFalse();
        assertThat(scope.test(null)).isFalse();
    }

    @Test
    void wildcardGrantSeesEveryAppTheRuntimeServes() {
        Predicate<String> scope = OpsScope.view(List.of("tql.ops.view.*"), SERVED);
        assertThat(scope.test("billing")).isTrue();
        assertThat(scope.test("shop")).isTrue();
    }

    @Test
    void withoutScopedGrantsNothingIsVisible() {
        // Deny by default: no tql.ops.view atoms means an empty surface, not an open one.
        assertThat(OpsScope.view(List.of(), SERVED).test("billing")).isFalse();
        assertThat(OpsScope.view(null, SERVED).test("billing")).isFalse();
        assertThat(OpsScope.view("not-a-list", SERVED).test("billing")).isFalse();
    }

    /**
     * View and act are different authorities to grant (docs/stack-shells.md structural
     * decision 1): one verb's grant says nothing about the other. This is the asymmetry —
     * <em>view broadly, act narrowly</em> — that the retired two-axis model
     * ({@code ops.batch.view}/{@code ops.batch.run} plus one {@code ops.app.<name>} set scoping
     * both verbs) could not express, pinned as its regression test.
     */
    @Test
    void theVerbsArePerApplicationAndIndependent() {
        List<String> grants = List.of("tql.ops.view.billing", "tql.ops.run.shop");
        assertThat(OpsScope.view(grants, SERVED).test("billing")).isTrue();
        assertThat(OpsScope.run(grants, SERVED).test("billing"))
                .as("seeing an application does not grant acting on it")
                .isFalse();
        assertThat(OpsScope.run(grants, SERVED).test("shop")).isTrue();
        assertThat(OpsScope.view(grants, SERVED).test("shop"))
                .as("acting is not seeing either — the verbs are granted separately")
                .isFalse();
    }

    /** The wildcard is a terminal {@code *} per verb: {@code tql.ops.view.*} grants no acting. */
    @Test
    void aWildcardBindsOnlyItsOwnVerb() {
        assertThat(OpsScope.run(List.of("tql.ops.view.*"), SERVED).test("billing")).isFalse();
        assertThat(OpsScope.view(List.of("tql.ops.run.*"), SERVED).test("billing")).isFalse();
    }

    /**
     * The surface reports on the applications in its reach and no others.
     *
     * <p>The ops tables live in a business database several runtimes may share, so a grant on its
     * own was enough to list another runtime's jobs, executions and transfers — rows this surface
     * has no relationship with. A wildcard grant is the sharpest case: it means "every app in
     * reach", never "every app in the database".
     */
    @Test
    void anAppOutsideTheServedSetIsInvisibleHoweverBroadTheGrant() {
        assertThat(OpsScope.view(List.of("tql.ops.view.*"), SERVED).test("someone-elses-app"))
                .as("a wildcard grant stops at what is served")
                .isFalse();

        assertThat(OpsScope
                .view(List.of("tql.ops.view.someone-elses-app"), SERVED)
                .test("someone-elses-app"))
                .as("naming it explicitly does not reach another runtime's rows either")
                .isFalse();
    }

    @Test
    void aRuntimeServingNothingShowsNothing() {
        assertThat(OpsScope.view(List.of("tql.ops.view.*"), Set.of()).test("billing")).isFalse();
        assertThat(OpsScope.view(List.of("tql.ops.view.*"), null).test("billing")).isFalse();
    }

    /** The stack-wide vitals open to any holder of any {@code tql.ops.view} grant. */
    @Test
    void anyViewGrantOpensTheVitals() {
        assertThat(OpsScope.holdsAnyView(List.of("tql.ops.view.billing"))).isTrue();
        assertThat(OpsScope.holdsAnyView(List.of("tql.ops.view.*"))).isTrue();
        assertThat(OpsScope.holdsAnyView(List.of("tql.ops.run.billing")))
                .as("a run grant is not a view grant")
                .isFalse();
        assertThat(OpsScope.holdsAnyView(List.of())).isFalse();
        assertThat(OpsScope.holdsAnyView(null)).isFalse();
    }
}
