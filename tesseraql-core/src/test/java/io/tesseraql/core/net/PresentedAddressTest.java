package io.tesseraql.core.net;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The bare host out of what a request presented (docs/access-governance.md decision 8). */
class PresentedAddressTest {

    @Test
    void aBareAddressIsLeftAlone() {
        assertThat(PresentedAddress.hostOf("203.0.113.9")).isEqualTo("203.0.113.9");
        assertThat(PresentedAddress.hostOf("::1")).isEqualTo("::1");
        assertThat(PresentedAddress.hostOf("0:0:0:0:0:0:0:1")).isEqualTo("0:0:0:0:0:0:0:1");
    }

    /** What a Vert.x peer address renders as — the shape that made this class necessary. */
    @Test
    void aPeerAddressLosesItsPort() {
        assertThat(PresentedAddress.hostOf("127.0.0.1:52344")).isEqualTo("127.0.0.1");
        assertThat(PresentedAddress.hostOf("0:0:0:0:0:0:0:1:52344"))
                .as("IPv6 renders without brackets, so the port is the last segment")
                .isEqualTo("0:0:0:0:0:0:0:1");
        assertThat(PresentedAddress.hostOf("[2001:db8::1]:443")).isEqualTo("2001:db8::1");
    }

    @Test
    void somethingThatIsNotAnAddressIsReturnedUnchanged() {
        assertThat(PresentedAddress.hostOf("edge.example.com")).isEqualTo("edge.example.com");
        assertThat(PresentedAddress.hostOf("edge.example.com:8443"))
                .as("a host with a port is still not an address to match a block against")
                .isEqualTo("edge.example.com:8443");
        assertThat(PresentedAddress.hostOf(null)).isNull();
        assertThat(PresentedAddress.hostOf("  ")).isEmpty();
    }
}
