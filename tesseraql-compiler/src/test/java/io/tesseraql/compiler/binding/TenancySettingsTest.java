package io.tesseraql.compiler.binding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.config.AppConfig;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The tenancy block's resolver vocabulary is three names, refused at load outside them: a
 * {@code claims} or {@code Header} used to read as a header resolver and every request resolved
 * by the wrong source, silently (docs/audit-low-leads.md G25).
 */
class TenancySettingsTest {

    @Test
    void theThreeResolverTypesLoad() {
        assertThat(settings("header", "X-Tenant-Id").resolver())
                .isEqualTo(TenancySettings.ResolverType.HEADER);
        assertThat(settings("claim", "tenantId").resolver())
                .isEqualTo(TenancySettings.ResolverType.CLAIM);
        assertThat(settings("host", "{tenant}.example.com").resolver())
                .isEqualTo(TenancySettings.ResolverType.HOST);
    }

    @Test
    void aMisspelledResolverTypeIsRefusedAtLoad() {
        assertThatThrownBy(() -> settings("claims", "tenantId"))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-TENANT-4032")
                .hasMessageContaining("claims");
        assertThatThrownBy(() -> settings("Header", "X-Tenant-Id"))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-TENANT-4032");
    }

    @Test
    void aDisabledTenancyIsNotRead() {
        AppConfig config = new AppConfig(Map.of("tenancy", Map.of("enabled", "false",
                "resolver", Map.of("type", "claims"))));
        assertThat(TenancySettings.from(config).enabled()).isFalse();
    }

    private static TenancySettings settings(String type, String source) {
        return TenancySettings.from(new AppConfig(Map.of("tenancy", Map.of(
                "enabled", "true", "mode", "shared-schema",
                "resolver", Map.of("type", type, "source", source)))));
    }
}
