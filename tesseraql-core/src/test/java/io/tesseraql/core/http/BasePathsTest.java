package io.tesseraql.core.http;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The open-redirect gate shared by the login {@code next}, {@code _return}, OIDC and SAML. */
class BasePathsTest {

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
