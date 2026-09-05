package io.tesseraql.yaml.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * A {@code contract:} binding declares the bounds it runs under, and a {@code service:} binding
 * still cannot.
 *
 * <p>Both arms used to share one record, and {@code Binding.of} read {@code materialize:} and
 * {@code timeoutSeconds:} from the {@code sql} arm alone — so a contract binding that declared
 * either was parsed, accepted and ignored. That mattered once the contract path gained a row
 * bound: without a per-binding override, an application whose contract legitimately returns more
 * than the app-wide budget had no lever but raising the budget for every route, command and export
 * at once.
 *
 * <p>The arms are separate records rather than one shared one, and that is the point of this test:
 * putting those keys on the shared record would have opened them on {@code service:} too, where a
 * binding compiles to a three-argument step with no bounds concept — turning an unknown-key warning
 * into silent acceptance.
 */
class ContractBindingBoundsTest {

    @Test
    void aContractBindingCarriesItsOwnMaterializeBound() {
        Binding binding = Binding.of(null,
                new Binding.ContractCall("identity.list-users", null, null, null,
                        new Binding.Materialize(2, "warn"), null),
                null, null, null, null, null, null);

        assertThat(binding.isContract()).isTrue();
        assertThat(binding.materialize()).isNotNull();
        assertThat(binding.materialize().maxRows()).isEqualTo(2);
        assertThat(binding.materialize().onOverflow()).isEqualTo("warn");
    }

    @Test
    void aContractBindingCarriesItsOwnStatementTimeout() {
        Binding binding = Binding.of(null,
                new Binding.ContractCall("identity.list-users", null, null, null, null, 5),
                null, null, null, null, null, null);

        assertThat(binding.timeoutSeconds()).isEqualTo(5);
    }

    /**
     * The half that must NOT change. A service binding compiles to a step with no bounds concept,
     * so these keys stay unknown there and keep drawing their warning.
     */
    @Test
    void aServiceBindingHasNoPlaceToDeclareBounds() {
        Binding binding = Binding.of(null, null,
                new Binding.NamedCall("iam.grantHistory", null, null, null),
                null, null, null, null, null);

        assertThat(binding.isService()).isTrue();
        assertThat(binding.materialize()).isNull();
        assertThat(binding.timeoutSeconds()).isNull();
    }

    /** The contract arm's other components still arrive, so the split loses nothing. */
    @Test
    void theContractArmKeepsItsModeParamsAndExpect() {
        Binding binding = Binding.of(null,
                new Binding.ContractCall("identity.enable-user", "update",
                        java.util.Map.of("userId", "path.id"), null, null, null),
                null, null, null, null, null, null);

        assertThat(binding.effectiveMode()).isEqualTo("update");
        assertThat(binding.params()).containsEntry("userId", "path.id");
    }
}
