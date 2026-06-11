package io.tesseraql.camel.service;

import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.service.ServiceProviders;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.support.DefaultProducer;

/**
 * Invokes a named {@link io.tesseraql.core.service.ServiceProvider} with the route-resolved
 * parameters and stores the result into the execution context under the endpoint's result key
 * (design ch. 47), mirroring how the sql/iam producers publish their result sets.
 */
public class TesseraqlServiceProducer extends DefaultProducer {

    private static final TqlErrorCode UNSUPPORTED = new TqlErrorCode(TqlDomain.CAMEL, 1301);
    private static final TqlErrorCode NOT_CONFIGURED = new TqlErrorCode(TqlDomain.CAMEL, 1302);

    private final TesseraqlServiceEndpoint endpoint;

    public TesseraqlServiceProducer(TesseraqlServiceEndpoint endpoint) {
        super(endpoint);
        this.endpoint = endpoint;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) {
        if (!"call".equals(endpoint.getOperation())) {
            throw new TqlException(UNSUPPORTED, "Unsupported tesseraql-service operation: "
                    + endpoint.getOperation());
        }
        ServiceProviders providers = endpoint.getCamelContext().getRegistry().lookupByNameAndType(
                TesseraqlProperties.SERVICE_PROVIDERS_BEAN, ServiceProviders.class);
        if (providers == null) {
            throw new TqlException(NOT_CONFIGURED, "Service provider registry is not configured");
        }
        Map<String, Object> params = exchange.getProperty(
                TesseraqlProperties.SQL_PARAMS, Map.of(), Map.class);

        Object result = providers.require(endpoint.getName()).invoke(params);

        Map<String, Object> context = exchange.getProperty(TesseraqlProperties.CONTEXT, Map.class);
        if (context != null) {
            context.put(endpoint.getResultKey(), result);
        }
        exchange.getMessage().setBody(result);
    }
}
