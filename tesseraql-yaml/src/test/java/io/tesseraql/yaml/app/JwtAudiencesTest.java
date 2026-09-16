package io.tesseraql.yaml.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The one reading of {@code tesseraql.security.jwt.audience} (docs/audit-low-leads.md XD-07i):
 * a string or a list, blank entries dropped, so {@code [""]} is "no audience" on both
 * altitudes — it used to lint clean and boot into a configuration no identity-provider token
 * could satisfy.
 */
class JwtAudiencesTest {

    @Test
    void aStringOrAListIsReadTrimmed() {
        assertThat(JwtAudiences.declared("api")).containsExactly("api");
        assertThat(JwtAudiences.declared(" api ")).containsExactly("api");
        assertThat(JwtAudiences.declared(List.of("api", " ops "))).containsExactly("api", "ops");
    }

    @Test
    void anAudienceThatSaysNothingIsNone() {
        assertThat(JwtAudiences.declared(null)).isEmpty();
        assertThat(JwtAudiences.declared("")).isEmpty();
        assertThat(JwtAudiences.declared(" ")).isEmpty();
        assertThat(JwtAudiences.declared(List.of())).isEmpty();
        assertThat(JwtAudiences.declared(List.of(""))).isEmpty();
        assertThat(JwtAudiences.declared(List.of(" "))).isEmpty();
        assertThat(JwtAudiences.declared(Arrays.asList((Object) null))).isEmpty();
        assertThat(JwtAudiences.declared(Map.of("x", "y"))).isEmpty();
        // The one declared entry survives beside the blank ones.
        assertThat(JwtAudiences.declared(Arrays.asList("", "api", null))).containsExactly("api");
    }
}
