package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.config.AppConfig;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** The per-tenant pool fallback must fail closed in an isolation mode (silent-tolerance S2). */
class TenantDataSourcesTest {

    /** A do-nothing DataSource stand-in — only its identity matters to these tests. */
    private static final DataSource SHARED = (DataSource) Proxy.newProxyInstance(
            TenantDataSourcesTest.class.getClassLoader(), new Class<?>[]{DataSource.class},
            (proxy, method, args) -> {
                throw new UnsupportedOperationException(method.getName());
            });

    @Test
    void perTenantModeRejectsATenantWithNoPoolRatherThanFallingBack() {
        AppConfig config = new AppConfig(Map.of(
                "tenancy", Map.of("mode", "database-per-tenant")));
        TenantDataSources pools = TenantDataSources.load(config);

        assertThatThrownBy(() -> pools.dataSourceFor("globex", SHARED))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-TENANT-4031")
                .hasMessageContaining("globex");
    }

    @Test
    void schemaPerTenantModeAlsoFailsClosed() {
        AppConfig config = new AppConfig(Map.of(
                "tenancy", Map.of("mode", "schema-per-tenant")));
        TenantDataSources pools = TenantDataSources.load(config);

        assertThatThrownBy(() -> pools.dataSourceFor("acme", SHARED))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-TENANT-4031");
    }

    @Test
    void sharedSchemaResolvesEveryTenantToTheSharedPool() {
        AppConfig config = new AppConfig(Map.of(
                "tenancy", Map.of("mode", "shared-schema", "tenants", List.of("acme", "globex"))));
        TenantDataSources pools = TenantDataSources.load(config);

        assertThat(pools.dataSourceFor("acme", SHARED)).isSameAs(SHARED);
        assertThat(pools.dataSourceFor("globex", SHARED)).isSameAs(SHARED);
    }

    @Test
    void noTenancyModeResolvesToTheSharedPool() {
        TenantDataSources pools = TenantDataSources.load(new AppConfig(Map.of()));

        assertThat(pools.dataSourceFor("acme", SHARED)).isSameAs(SHARED);
    }
}
