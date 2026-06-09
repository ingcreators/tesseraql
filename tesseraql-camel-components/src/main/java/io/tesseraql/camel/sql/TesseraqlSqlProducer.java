package io.tesseraql.camel.sql;

import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.sql.BoundParameter;
import io.tesseraql.core.sql.BoundSql;
import io.tesseraql.core.sql.SqlNode;
import io.tesseraql.core.sql.Sql2WayParser;
import io.tesseraql.core.sql.SqlRenderer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.apache.camel.Exchange;
import org.apache.camel.support.DefaultProducer;

/**
 * Executes a 2-way SQL file against JDBC and publishes the result into the execution context
 * (design ch. 9.1). The SQL is parsed once at startup and rendered per exchange with the resolved
 * bind parameters held in the {@link TesseraqlProperties#SQL_PARAMS} property.
 */
public class TesseraqlSqlProducer extends DefaultProducer {

    private static final TqlErrorCode EXECUTION_ERROR = new TqlErrorCode(TqlDomain.SQL, 2500);
    private static final TqlErrorCode UNSUPPORTED_MODE = new TqlErrorCode(TqlDomain.SQL, 2501);
    private static final TqlErrorCode NO_DATASOURCE = new TqlErrorCode(TqlDomain.SQL, 2502);

    private final TesseraqlSqlEndpoint endpoint;
    private List<SqlNode> nodes;

    public TesseraqlSqlProducer(TesseraqlSqlEndpoint endpoint) {
        super(endpoint);
        this.endpoint = endpoint;
    }

    @Override
    protected void doStart() throws Exception {
        super.doStart();
        String source = Files.readString(Path.of(endpoint.getSqlPath()));
        this.nodes = Sql2WayParser.parse(source);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) throws Exception {
        if (!"query".equals(endpoint.getMode())) {
            throw new TqlException(UNSUPPORTED_MODE,
                    "Unsupported SQL mode '" + endpoint.getMode() + "' (only 'query' is supported)");
        }
        Map<String, Object> params = exchange.getProperty(
                TesseraqlProperties.SQL_PARAMS, Map.of(), Map.class);
        Map<String, Object> context = exchange.getProperty(
                TesseraqlProperties.CONTEXT, Map.class);

        BoundSql bound = SqlRenderer.render(nodes, params);
        Map<String, Object> result = executeQuery(bound);

        if (context != null) {
            context.put(endpoint.getResultKey(), result);
        }
        exchange.getMessage().setBody(result);
    }

    private Map<String, Object> executeQuery(BoundSql bound) {
        DataSource dataSource = endpoint.getCamelContext().getRegistry()
                .lookupByNameAndType(endpoint.getDatasource(), DataSource.class);
        if (dataSource == null) {
            throw new TqlException(NO_DATASOURCE,
                    "No DataSource named '" + endpoint.getDatasource() + "' in the registry");
        }
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(bound.sql())) {
            bindParameters(statement, bound.parameters());
            try (ResultSet resultSet = statement.executeQuery()) {
                List<Map<String, Object>> rows = readRows(resultSet);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("rows", rows);
                result.put("rowCount", rows.size());
                return result;
            }
        } catch (java.sql.SQLException ex) {
            throw TqlException.builder(EXECUTION_ERROR)
                    .message("SQL execution failed: " + ex.getMessage())
                    .source(endpoint.getSqlPath())
                    .cause(ex)
                    .build();
        }
    }

    private void bindParameters(PreparedStatement statement, List<BoundParameter> parameters)
            throws java.sql.SQLException {
        for (int i = 0; i < parameters.size(); i++) {
            statement.setObject(i + 1, parameters.get(i).value());
        }
    }

    private List<Map<String, Object>> readRows(ResultSet resultSet) throws java.sql.SQLException {
        ResultSetMetaData metaData = resultSet.getMetaData();
        int columnCount = metaData.getColumnCount();
        List<Map<String, Object>> rows = new ArrayList<>();
        while (resultSet.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int col = 1; col <= columnCount; col++) {
                row.put(metaData.getColumnLabel(col), normalize(resultSet.getObject(col)));
            }
            rows.add(row);
        }
        return rows;
    }

    /** Normalizes JDBC temporal types to ISO-8601 strings for stable JSON output. */
    private static Object normalize(Object value) {
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant().toString();
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate().toString();
        }
        if (value instanceof java.sql.Time time) {
            return time.toLocalTime().toString();
        }
        return value;
    }
}
