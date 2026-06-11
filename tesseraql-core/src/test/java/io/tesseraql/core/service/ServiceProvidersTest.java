package io.tesseraql.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ServiceProvidersTest {

    @Test
    void registersAndInvokesByName() {
        ServiceProviders providers = new ServiceProviders()
                .register("ops.overview", params -> Map.of("warning", false));

        assertThat(providers.require("ops.overview").invoke(Map.of()))
                .isEqualTo(Map.of("warning", false));
    }

    @Test
    void unknownProviderThrows() {
        assertThatThrownBy(() -> new ServiceProviders().require("nope"))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("No service provider named 'nope'");
    }

    @Test
    void duplicateRegistrationThrows() {
        ServiceProviders providers = new ServiceProviders().register("a", params -> 1);

        assertThatThrownBy(() -> providers.register("a", params -> 2))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("already registered");
    }
}
