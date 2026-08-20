package io.tesseraql.security.net;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Where a session may be established from (docs/access-governance.md decision 8, layer A). */
class SignInAllowListTest {

    @Test
    void anUnconfiguredDeploymentAdmitsEverybody() {
        assertThat(SignInAllowList.parse(null).restricts()).isFalse();
        assertThatCode(() -> SignInAllowList.parse("  ").admit("203.0.113.9"))
                .doesNotThrowAnyException();
        assertThatCode(() -> SignInAllowList.EVERYWHERE.admit(null)).doesNotThrowAnyException();
    }

    @Test
    void aNamedNetworkAdmitsInsideAndRefusesOutside() {
        SignInAllowList office = SignInAllowList.parse("10.0.0.0/8, 192.168.1.5");

        assertThatCode(() -> office.admit("10.9.9.9")).doesNotThrowAnyException();
        assertThatCode(() -> office.admit("192.168.1.5")).doesNotThrowAnyException();
        assertThatThrownBy(() -> office.admit("203.0.113.9"))
                .hasMessageContaining("TQL-SEC-4149");
    }

    /**
     * A request that reached an HTTP server has a peer, so no address at all means something
     * upstream presented none — and admitting the unjudgeable would make the control skippable.
     */
    @Test
    void aRestrictedDeploymentRefusesAnAddressItCannotJudge() {
        assertThatThrownBy(() -> SignInAllowList.parse("10.0.0.0/8").admit(null))
                .hasMessageContaining("TQL-SEC-4149");
        assertThatThrownBy(() -> SignInAllowList.parse("10.0.0.0/8").admit("edge.example.com"))
                .hasMessageContaining("TQL-SEC-4149");
    }

    @Test
    void aMalformedNetworkIsRefusedAtBootRatherThanIgnored() {
        assertThatThrownBy(() -> SignInAllowList.parse("10.0.0.0/33"))
                .hasMessageContaining("tesseraql.security.network.allow")
                .hasMessageContaining("out of range");
    }
}
