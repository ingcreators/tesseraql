package io.tesseraql.camel.iam;

import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.identity.IdentityService;
import io.tesseraql.identity.RealmConfig;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.support.DefaultProducer;

/**
 * Executes an Identity SQL Contract and publishes the rows into the execution context, mirroring
 * the {@code tesseraql-sql} result shape so the same response renderers apply (design ch. 9.3).
 */
public class TesseraqlIamProducer extends DefaultProducer {

    private static final TqlErrorCode UNSUPPORTED = new TqlErrorCode(TqlDomain.IAM, 2000);
    private static final TqlErrorCode NOT_CONFIGURED = new TqlErrorCode(TqlDomain.IAM, 2001);

    private final TesseraqlIamEndpoint endpoint;

    public TesseraqlIamProducer(TesseraqlIamEndpoint endpoint) {
        super(endpoint);
        this.endpoint = endpoint;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) {
        if (!"contract".equals(endpoint.getOperation())) {
            throw new TqlException(UNSUPPORTED, "Unsupported tesseraql-iam operation: "
                    + endpoint.getOperation());
        }
        IdentityService identity = bean(IdentityService.class, TesseraqlProperties.IDENTITY_SERVICE_BEAN);
        RealmConfig realm = bean(RealmConfig.class, TesseraqlProperties.IDENTITY_REALM_BEAN);

        Map<String, Object> params = exchange.getProperty(
                TesseraqlProperties.SQL_PARAMS, Map.of(), Map.class);
        List<Map<String, Object>> rows = identity.execute(realm, contractName(), params);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", rows);
        result.put("rowCount", rows.size());

        Map<String, Object> context = exchange.getProperty(TesseraqlProperties.CONTEXT, Map.class);
        if (context != null) {
            context.put(endpoint.getResultKey(), result);
        }
        exchange.getMessage().setBody(result);
    }

    /** Strips a leading {@code identity.} qualifier to get the contract file name. */
    private String contractName() {
        String name = endpoint.getName();
        return name.startsWith("identity.") ? name.substring("identity.".length()) : name;
    }

    private <T> T bean(Class<T> type, String name) {
        T bean = endpoint.getCamelContext().getRegistry().lookupByNameAndType(name, type);
        if (bean == null) {
            throw new TqlException(NOT_CONFIGURED, "Identity bean '" + name + "' is not configured");
        }
        return bean;
    }
}
