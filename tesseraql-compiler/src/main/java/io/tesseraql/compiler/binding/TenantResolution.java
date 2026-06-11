package io.tesseraql.compiler.binding;

import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.expr.EvaluationContext;
import io.tesseraql.core.tenant.TenantContext;
import java.util.Arrays;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

/**
 * Resolves the request's tenant and publishes it into the execution context (design ch. 30).
 *
 * <p>The tenant id is read from a request header or from the authenticated principal (so this step
 * must run after authentication). With {@code required} enabled, a missing tenant is rejected with
 * {@code TQL-TENANT-4001} (400) — deny-by-default isolation. The resolved {@link TenantContext} is
 * stored as the {@link TesseraqlProperties#TENANT} property (read into the context by the request
 * binder) and recorded on the route telemetry span.
 */
public final class TenantResolution implements Processor {

    private static final TqlErrorCode MISSING_TENANT = new TqlErrorCode(TqlDomain.TENANT, 4001);

    private final TenancySettings settings;

    public TenantResolution(TenancySettings settings) {
        this.settings = settings;
    }

    @Override
    public void process(Exchange exchange) {
        String tenantId = switch (settings.resolver()) {
            case HEADER -> exchange.getMessage().getHeader(settings.source(), String.class);
            case CLAIM -> fromPrincipal(exchange);
        };

        if (tenantId == null || tenantId.isBlank()) {
            if (settings.required()) {
                throw new TqlException(MISSING_TENANT, "No tenant could be resolved for the request");
            }
            return;
        }

        TenantContext tenant = TenantContext.of(tenantId.trim());
        exchange.setProperty(TesseraqlProperties.TENANT, tenant);

        io.tesseraql.core.telemetry.Span span = exchange.getProperty(
                TesseraqlProperties.ROUTE_SPAN, io.tesseraql.core.telemetry.Span.class);
        if (span != null) {
            span.attribute("tenant", tenant.id());
        }
    }

    private String fromPrincipal(Exchange exchange) {
        Object principal = exchange.getProperty(TesseraqlProperties.PRINCIPAL);
        if (principal == null) {
            return null;
        }
        EvaluationContext context = new EvaluationContext(Map.of("principal", principal));
        Object value = context.resolve(Arrays.asList(("principal." + settings.source()).split("\\.")));
        return value == null ? null : String.valueOf(value);
    }
}
