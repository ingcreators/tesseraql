package io.tesseraql.camel.service;

import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.service.ServiceProviders;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

/**
 * Invokes a named {@link io.tesseraql.core.service.ServiceProvider} with the route-resolved
 * parameters and stores the result into the execution context under the endpoint's result key
 * (design ch. 47), mirroring how the sql/iam producers publish their result sets.
 */
public class ServiceStep implements Processor {

    private static final TqlErrorCode UNSUPPORTED = new TqlErrorCode(TqlDomain.CAMEL, 1301);
    private static final TqlErrorCode NOT_CONFIGURED = new TqlErrorCode(TqlDomain.CAMEL, 1302);

    private final String operation;
    private final String name;
    private final String resultKey;

    /** One service call, with the settings its endpoint URI used to carry. */
    public ServiceStep(String operation, String name, String resultKey) {
        this.operation = operation;
        this.name = name;
        this.resultKey = resultKey == null ? "main" : resultKey;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) {
        if (!"call".equals(operation)) {
            throw new TqlException(UNSUPPORTED, "Unsupported tesseraql-service operation: "
                    + operation);
        }
        ServiceProviders providers = exchange.getContext().getRegistry().lookupByNameAndType(
                TesseraqlProperties.SERVICE_PROVIDERS_BEAN, ServiceProviders.class);
        if (providers == null) {
            throw new TqlException(NOT_CONFIGURED, "Service provider registry is not configured");
        }
        Map<String, Object> params = exchange.getProperty(
                TesseraqlProperties.SQL_PARAMS, Map.of(), Map.class);

        Object result = providers.require(name).invoke(params);

        Map<String, Object> context = exchange.getProperty(TesseraqlProperties.CONTEXT, Map.class);
        if (context != null) {
            io.tesseraql.camel.ContextResults.put(context, resultKey, result);
        }
        exchange.getMessage().setBody(result);
    }
}
