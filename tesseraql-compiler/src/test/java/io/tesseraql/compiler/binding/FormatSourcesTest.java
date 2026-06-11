package io.tesseraql.compiler.binding;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.security.Principal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FormatSourcesTest {

    private static final Principal PRINCIPAL = new Principal("u-1", "anne", "Anne", null,
            List.of(), List.of(), List.of(),
            Map.of("locale", "de-DE", "zoneinfo", "Europe/Berlin"));

    @Test
    void literalsPassThroughUntouched() {
        assertThat(FormatSources.resolve(Map.of(), null, "ja-JP")).isEqualTo("ja-JP");
        assertThat(FormatSources.resolve(Map.of(), null, "Asia/Tokyo")).isEqualTo("Asia/Tokyo");
        assertThat(FormatSources.resolve(Map.of(), null, (String) null)).isNull();
    }

    @Test
    void principalExpressionsResolveTheStartingUsersClaims() {
        // The login user's claims decide the rendering: one route, per-user formats.
        assertThat(FormatSources.resolve(Map.of(), PRINCIPAL, "principal.claim.locale"))
                .isEqualTo("de-DE");
        assertThat(FormatSources.resolve(Map.of("principal", PRINCIPAL), null,
                "principal.claim.zoneinfo")).isEqualTo("Europe/Berlin");
    }

    @Test
    void unresolvableExpressionsYieldNullForThePlatformDefault() {
        assertThat(FormatSources.resolve(Map.of(), null, "principal.claim.locale")).isNull();
        assertThat(FormatSources.resolve(Map.of("query", Map.of()), null, "query.locale"))
                .isNull();
    }
}
