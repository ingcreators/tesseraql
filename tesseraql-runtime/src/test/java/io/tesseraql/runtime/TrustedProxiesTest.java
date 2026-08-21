package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Which peers count as the operator's edge (docs/stack-architecture.md decision 13). */
class TrustedProxiesTest {

    @Test
    void anEmptyListTrustsNobodyAndIsAskedNothing() {
        assertThat(TrustedProxies.parse(null).isEmpty()).isTrue();
        assertThat(TrustedProxies.parse("  ").isEmpty()).isTrue();
        assertThat(TrustedProxies.NONE.includes("10.1.2.3")).isFalse();
    }

    @Test
    void aBlockMatchesOnItsSignificantBitsOnly() {
        TrustedProxies edge = TrustedProxies.parse("10.0.0.0/8");

        assertThat(edge.includes("10.0.0.1")).isTrue();
        assertThat(edge.includes("10.255.255.254")).isTrue();
        assertThat(edge.includes("11.0.0.1")).isFalse();
        assertThat(edge.includes("9.255.255.255")).isFalse();
    }

    /** A prefix that does not land on a byte boundary is where an off-by-one hides. */
    @Test
    void aPrefixInsideAByteMasksThatByte() {
        TrustedProxies edge = TrustedProxies.parse("192.168.1.0/28");

        assertThat(edge.includes("192.168.1.0")).isTrue();
        assertThat(edge.includes("192.168.1.15")).isTrue();
        assertThat(edge.includes("192.168.1.16")).isFalse();
        assertThat(edge.includes("192.168.2.1")).isFalse();
    }

    @Test
    void aBareAddressIsTheSingleHostItNames() {
        TrustedProxies edge = TrustedProxies.parse("192.168.1.5");

        assertThat(edge.includes("192.168.1.5")).isTrue();
        assertThat(edge.includes("192.168.1.6")).isFalse();
    }

    @Test
    void severalBlocksAreAccepted() {
        TrustedProxies edge = TrustedProxies.parse(" 10.0.0.0/8 , 172.16.0.0/12 ,");

        assertThat(edge.includes("10.9.9.9")).isTrue();
        assertThat(edge.includes("172.16.0.1")).isTrue();
        assertThat(edge.includes("172.32.0.1")).isFalse();
    }

    @Test
    void ipv6IsMatchedAndDoesNotCrossFamilies() {
        TrustedProxies edge = TrustedProxies.parse("2001:db8::/32");

        assertThat(edge.includes("2001:db8::1")).isTrue();
        assertThat(edge.includes("2001:db9::1")).isFalse();
        assertThat(edge.includes("10.0.0.1")).as("an IPv4 peer is not inside an IPv6 block")
                .isFalse();
        assertThat(TrustedProxies.parse("10.0.0.0/8").includes("::1"))
                .as("and the reverse").isFalse();
    }

    /** A peer address is never a name; resolving one would put DNS on the event loop. */
    @Test
    void somethingThatIsNotAnAddressIsNotTrusted() {
        assertThat(TrustedProxies.parse("10.0.0.0/8").includes("edge.example.com")).isFalse();
        assertThat(TrustedProxies.parse("10.0.0.0/8").includes(null)).isFalse();
    }

    @Test
    void aMalformedRangeIsRefusedAtParseRatherThanIgnored() {
        assertThatThrownBy(() -> TrustedProxies.parse("10.0.0.0/33"))
                .hasMessageContaining("out of range");
        assertThatThrownBy(() -> TrustedProxies.parse("10.0.0.0/eight"))
                .hasMessageContaining("prefix length");
        assertThatThrownBy(() -> TrustedProxies.parse("not-an-address/8"))
                .hasMessageContaining("Not an address");
    }
}
