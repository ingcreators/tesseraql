package io.tesseraql.yaml.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** CSRF resolution, incl. the absent-⇒-auto default (silent-tolerance S4). */
class SecuritySpecTest {

    @Test
    void absentCsrfProtectsABrowserStateChangingRoute() {
        // An absent csrf: is the documented `auto` default, not off — a browser POST with no
        // csrf key and no defaults rule must still enforce.
        SecuritySpec spec = new SecuritySpec("browser", null, null);
        assertThat(spec.csrfEnforced("POST")).isTrue();
        assertThat(spec.csrfEnforced("GET")).isFalse();
    }

    @Test
    void absentCsrfDoesNotProtectANonBrowserRoute() {
        assertThat(new SecuritySpec("bearer", null, null).csrfEnforced("POST")).isFalse();
    }

    @Test
    void explicitOffNeverEnforces() {
        assertThat(new SecuritySpec("browser", null, "off").csrfEnforced("POST")).isFalse();
    }

    @Test
    void explicitRequiredAlwaysEnforces() {
        assertThat(new SecuritySpec("bearer", null, "required").csrfEnforced("POST")).isTrue();
    }
}
