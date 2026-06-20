package io.tesseraql.security.session;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LoginRedirectsTest {

    @Test
    void honorsOnlySameOriginAbsolutePaths() {
        assertThat(LoginRedirects.isSafe("/_tesseraql/studio/ui")).isTrue();
        assertThat(LoginRedirects.isSafe("/")).isTrue();
        assertThat(LoginRedirects.isSafe("/a?b=c#d")).isTrue();
    }

    @Test
    void rejectsOffSiteAndMalformedTargets() {
        // protocol-relative, absolute URLs, backslash tricks, header-splitting, and non-paths.
        assertThat(LoginRedirects.isSafe("//evil.example.com")).isFalse();
        assertThat(LoginRedirects.isSafe("/\\evil.example.com")).isFalse();
        assertThat(LoginRedirects.isSafe("https://evil.example.com")).isFalse();
        assertThat(LoginRedirects.isSafe("javascript:alert(1)")).isFalse();
        assertThat(LoginRedirects.isSafe("relative/path")).isFalse();
        assertThat(LoginRedirects.isSafe("/ok\r\nLocation: https://evil")).isFalse();
        assertThat(LoginRedirects.isSafe(null)).isFalse();
        assertThat(LoginRedirects.isSafe("")).isFalse();
    }

    @Test
    void sanitizeFallsBackForUnsafeTargets() {
        assertThat(LoginRedirects.sanitize("/safe", "/home")).isEqualTo("/safe");
        assertThat(LoginRedirects.sanitize("//evil", "/home")).isEqualTo("/home");
        assertThat(LoginRedirects.sanitize(null, "/home")).isEqualTo("/home");
    }
}
