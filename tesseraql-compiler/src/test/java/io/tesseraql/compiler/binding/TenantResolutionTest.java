package io.tesseraql.compiler.binding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.compiler.binding.TenancySettings.ResolverType;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.tenant.TenantContext;
import io.tesseraql.security.Principal;
import java.util.List;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TenantResolutionTest {

    private static DefaultCamelContext camel;

    @BeforeAll
    static void start() {
        camel = new DefaultCamelContext();
        camel.start();
    }

    @AfterAll
    static void stop() {
        camel.stop();
    }

    private static Exchange exchange() {
        return new DefaultExchange(camel);
    }

    @Test
    void resolvesTenantFromHeader() {
        TenancySettings settings = new TenancySettings(
                true, "shared-schema", ResolverType.HEADER, "X-Tenant-Id", true);
        Exchange exchange = exchange();
        exchange.getMessage().setHeader("X-Tenant-Id", "acme");

        new TenantResolution(settings).process(exchange);

        TenantContext tenant = exchange.getProperty(TesseraqlProperties.TENANT, TenantContext.class);
        assertThat(tenant).isNotNull();
        assertThat(tenant.id()).isEqualTo("acme");
    }

    @Test
    void rejectsMissingTenantWhenRequired() {
        TenancySettings settings = new TenancySettings(
                true, "shared-schema", ResolverType.HEADER, "X-Tenant-Id", true);
        Exchange exchange = exchange();

        assertThatThrownBy(() -> new TenantResolution(settings).process(exchange))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("tenant");
        assertThat(exchange.getProperty(TesseraqlProperties.TENANT)).isNull();
    }

    @Test
    void skipsMissingTenantWhenOptional() {
        TenancySettings settings = new TenancySettings(
                true, "shared-schema", ResolverType.HEADER, "X-Tenant-Id", false);
        Exchange exchange = exchange();

        new TenantResolution(settings).process(exchange);

        assertThat(exchange.getProperty(TesseraqlProperties.TENANT)).isNull();
    }

    @Test
    void resolvesTenantFromPrincipalClaim() {
        TenancySettings settings = new TenancySettings(
                true, "shared-schema", ResolverType.CLAIM, "tenantId", true);
        Exchange exchange = exchange();
        exchange.setProperty(TesseraqlProperties.PRINCIPAL, new Principal(
                "u1", "u1", "User One", "globex",
                List.of(), List.of(), List.of(), Map.of()));

        new TenantResolution(settings).process(exchange);

        TenantContext tenant = exchange.getProperty(TesseraqlProperties.TENANT, TenantContext.class);
        assertThat(tenant).isNotNull();
        assertThat(tenant.id()).isEqualTo("globex");
    }

    @Test
    void resolvesTenantFromNestedClaim() {
        TenancySettings settings = new TenancySettings(
                true, "shared-schema", ResolverType.CLAIM, "claim.org", true);
        Exchange exchange = exchange();
        exchange.setProperty(TesseraqlProperties.PRINCIPAL, new Principal(
                "u1", "u1", "User One", null,
                List.of(), List.of(), List.of(), Map.of("org", "initech")));

        new TenantResolution(settings).process(exchange);

        assertThat(exchange.getProperty(TesseraqlProperties.TENANT, TenantContext.class).id())
                .isEqualTo("initech");
    }
}
