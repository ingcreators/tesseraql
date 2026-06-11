package io.tesseraql.camel.sql;

import java.util.Map;
import org.apache.camel.Endpoint;
import org.apache.camel.support.DefaultComponent;

/**
 * Camel component scheme {@code tesseraql-sql} (design ch. 9.1).
 *
 * <p>URI form: {@code tesseraql-sql:file:/path/to/query.sql?datasource=main&mode=query&resultKey=sql}.
 * The component loads the 2-way SQL file, renders it against resolved bind parameters, executes it
 * through JDBC, and publishes the result into the execution context.
 */
public class TesseraqlSqlComponent extends DefaultComponent {

    @Override
    protected Endpoint createEndpoint(String uri, String remaining, Map<String, Object> parameters)
            throws Exception {
        String sqlPath = stripFilePrefix(remaining);
        TesseraqlSqlEndpoint endpoint = new TesseraqlSqlEndpoint(uri, this, sqlPath);
        setProperties(endpoint, parameters);
        return endpoint;
    }

    private static String stripFilePrefix(String remaining) {
        if (remaining.startsWith("file:")) {
            return remaining.substring("file:".length());
        }
        return remaining;
    }
}
