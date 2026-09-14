package io.tesseraql.core.http;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The open-redirect gate shared by the login {@code next}, {@code _return}, OIDC and SAML. */
class BasePathsTest {

    /**
     * A wire URL under a base named in Japanese is returned to base-relative form
     * (docs/router-unicode-names.md R1): the base's wire spelling is what the URL carries, and
     * comparing against the raw base kept the prefix, which the redirect helper then doubled.
     */
    @Test
    void relativeStripsTheWireSpellingOfANonAsciiBase() {
        assertThat(BasePaths.relative("/受注", "/%E5%8F%97%E6%B3%A8/things?page=2"))
                .isEqualTo("/things?page=2");
        assertThat(BasePaths.relative("/受注", "/%E5%8F%97%E6%B3%A8")).isEqualTo("/");
        // The raw spelling still strips, for a caller that passes decoded text.
        assertThat(BasePaths.relative("/受注", "/受注/things")).isEqualTo("/things");
        // ASCII is its own wire form; a stranger's prefix stays.
        assertThat(BasePaths.relative("/shop", "/shop/things")).isEqualTo("/things");
        assertThat(BasePaths.relative("/受注", "/shop/things")).isEqualTo("/shop/things");
    }

    @Test
    void isLocalRefusesEveryControlCharacter() {
        // A browser deletes a tab, CR or LF from a URL before parsing it, so /<TAB>/host is
        // //host — off-site, past the protocol-relative check. No control has a place here.
        assertThat(BasePaths.isLocal("/\t/evil.example")).isFalse();
        assertThat(BasePaths.isLocal("/\t\\evil.example")).isFalse();
        assertThat(BasePaths.isLocal("/ok\r\nLocation: https://evil")).isFalse();
        assertThat(BasePaths.isLocal("/a\u007Fb")).isFalse();
        assertThat(BasePaths.isLocal("/a\u000Bb")).isFalse();
    }

    @Test
    void isLocalKeepsAcceptingLocalPathsAndRefusingTheOldTricks() {
        assertThat(BasePaths.isLocal("/a b?x=y#z")).isTrue();
        assertThat(BasePaths.isLocal("/受注一覧?page=2")).isTrue();
        assertThat(BasePaths.isLocal("/")).isTrue();
        assertThat(BasePaths.isLocal("//evil.example")).isFalse();
        assertThat(BasePaths.isLocal("/\\evil.example")).isFalse();
        assertThat(BasePaths.isLocal("relative/path")).isFalse();
        assertThat(BasePaths.isLocal(null)).isFalse();
    }
}
