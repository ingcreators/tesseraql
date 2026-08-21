package io.tesseraql.camel.iam;

import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.identity.IdentityService;
import io.tesseraql.identity.RealmConfig;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.Step;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes an Identity SQL Contract and publishes the rows into the execution context, mirroring
 * the {@code tesseraql-sql} result shape so the same response renderers apply (design ch. 9.3).
 */
public class IamStep implements Step {

    private static final TqlErrorCode UNSUPPORTED = new TqlErrorCode(TqlDomain.IAM, 2000);
    private static final TqlErrorCode NOT_CONFIGURED = new TqlErrorCode(TqlDomain.IAM, 2001);

    private final String operation;
    private final String name;
    private final String mode;
    private final String resultKey;

    /** One identity-contract execution, with the settings its endpoint URI used to carry. */
    public IamStep(String operation, String name, String mode, String resultKey) {
        this.operation = operation;
        this.name = name;
        this.mode = mode == null ? "query" : mode;
        this.resultKey = resultKey == null ? "main" : resultKey;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) {
        if (!"contract".equals(operation)) {
            throw new TqlException(UNSUPPORTED, "Unsupported tesseraql-iam operation: "
                    + operation);
        }
        IdentityService identity = bean(exchange, IdentityService.class,
                TesseraqlProperties.IDENTITY_SERVICE_BEAN);
        RealmConfig realm = bean(exchange, RealmConfig.class,
                TesseraqlProperties.IDENTITY_REALM_BEAN);

        Map<String, Object> params = exchange.getProperty(
                TesseraqlProperties.SQL_PARAMS, Map.of(), Map.class);

        Map<String, Object> result = new LinkedHashMap<>();
        if ("update".equals(mode)) {
            result.put("affectedRows", identity.executeUpdate(realm, contractName(), params));
        } else {
            List<Map<String, Object>> rows = identity.execute(realm, contractName(), params);
            result.put("rows", rows);
            result.put("rowCount", rows.size());
        }

        Map<String, Object> context = exchange.getProperty(TesseraqlProperties.CONTEXT, Map.class);
        if (context != null) {
            io.tesseraql.camel.ContextResults.put(context, resultKey, result);
        }
        exchange.getMessage().setBody(result);
    }

    /** Strips a leading {@code identity.} qualifier to get the contract file name. */
    private String contractName() {
        return name.startsWith("identity.") ? name.substring("identity.".length()) : name;
    }

    private <T> T bean(Exchange exchange, Class<T> type, String name) {
        T bean = exchange.beans().lookup(name, type);
        if (bean == null) {
            throw new TqlException(NOT_CONFIGURED,
                    "Identity bean '" + name + "' is not configured");
        }
        return bean;
    }
}
