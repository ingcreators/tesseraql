package io.tesseraql.pipeline.iam;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.identity.IdentityService;
import io.tesseraql.identity.RealmConfig;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.Step;
import io.tesseraql.pipeline.TesseraqlProperties;
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
        Map<String, Object> context = exchange.getProperty(TesseraqlProperties.CONTEXT, Map.class);
        // Declarative pagination reaches a contract the way it reaches a statement. It did not
        // before: the framework applies it in the SQL step, one layer a contract source never
        // passes through, so every admin directory rendered its whole table and the user
        // directory's bulk form posted one checkbox per row of tql_users.
        io.tesseraql.pipeline.PageRequest page = exchange.getProperty(TesseraqlProperties.PAGE,
                io.tesseraql.pipeline.PageRequest.class);
        boolean paged = page != null && !"update".equals(mode) && "main".equals(resultKey);
        if ("update".equals(mode)) {
            result.put("affectedRows", identity.executeUpdate(realm, contractName(), params));
        } else if (paged) {
            // One extra row answers hasNext without a second query, exactly as the SQL step does.
            List<Map<String, Object>> rows = new java.util.ArrayList<>(identity.execute(realm,
                    contractName(), params, page.size() + 1L, page.offset()));
            boolean hasNext = rows.size() > page.size();
            if (hasNext) {
                rows = new java.util.ArrayList<>(rows.subList(0, page.size()));
            }
            result.put("rows", rows);
            result.put("rowCount", rows.size());
            if (context != null) {
                context.put("page", pageInfo(page, hasNext));
            }
        } else {
            List<Map<String, Object>> rows = identity.execute(realm, contractName(), params);
            result.put("rows", rows);
            result.put("rowCount", rows.size());
        }

        if (context != null) {
            io.tesseraql.pipeline.ContextResults.put(context, resultKey, result);
        }
        exchange.setBody(result);
    }

    /** The metadata a pager renders from, in the shape the SQL step already publishes. */
    private static Map<String, Object> pageInfo(io.tesseraql.pipeline.PageRequest page,
            boolean hasNext) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("number", page.number());
        info.put("size", page.size());
        info.put("hasNext", hasNext);
        info.put("hasPrev", page.number() > 1);
        return info;
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
