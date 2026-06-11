package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.yaml.config.AppConfig;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TenantRegistryTest {

    @Test
    void resolvesStaticTenantList() {
        AppConfig config = new AppConfig(Map.of(
                "tenancy", Map.of("tenants", List.of("acme", "globex"))));
        TenantDataSources pools = TenantDataSources.load(new AppConfig(Map.of()));

        assertThat(TenantRegistry.tenantIds(config, null, pools))
                .containsExactly("acme", "globex");
    }

    @Test
    void emptyWhenNoSourceConfigured() {
        AppConfig config = new AppConfig(Map.of("tenancy", Map.of("enabled", "true")));
        TenantDataSources pools = TenantDataSources.load(new AppConfig(Map.of()));

        assertThat(TenantRegistry.tenantIds(config, null, pools)).isEmpty();
    }
}
