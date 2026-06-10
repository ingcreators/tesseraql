package io.tesseraql.coverage.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Obtains query plans from MySQL via {@code EXPLAIN FORMAT=JSON} (design ch. 46.5) and normalizes
 * them into the dialect-independent {@link QueryPlan}. MySQL's {@code access_type} is mapped to the
 * common operator names the {@link PlanGuard} understands, so {@code ALL} (a full table scan) becomes
 * a {@code Seq Scan}.
 */
public final class MysqlPlanInspector implements PlanInspector {

    private static final TqlErrorCode EXPLAIN_ERROR = new TqlErrorCode(TqlDomain.PLAN, 1501);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String[] WRAPPERS =
            {"ordering_operation", "grouping_operation", "duplicates_removal"};

    @Override
    public String dialect() {
        return "mysql";
    }

    @Override
    public QueryPlan explain(Connection connection, String sql, List<Object> params) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("EXPLAIN FORMAT=JSON " + sql)) {
            for (int i = 0; i < params.size(); i++) {
                statement.setObject(i + 1, params.get(i));
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new TqlException(EXPLAIN_ERROR, "EXPLAIN returned no rows");
                }
                return parse(resultSet.getString(1));
            }
        }
    }

    private QueryPlan parse(String json) {
        try {
            return block(MAPPER.readTree(json).get("query_block"));
        } catch (TqlException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new TqlException(EXPLAIN_ERROR, "Failed to parse EXPLAIN output: " + ex.getMessage());
        }
    }

    private QueryPlan block(JsonNode queryBlock) {
        double cost = cost(queryBlock, "query_cost");
        JsonNode inner = queryBlock;
        for (String wrapper : WRAPPERS) {
            if (inner.has(wrapper)) {
                inner = inner.get(wrapper);
            }
        }
        if (inner.has("nested_loop") && inner.get("nested_loop").isArray()) {
            List<QueryPlan> children = new ArrayList<>();
            for (JsonNode entry : inner.get("nested_loop")) {
                if (entry.has("table")) {
                    children.add(table(entry.get("table")));
                }
            }
            return new QueryPlan("Nested Loop", null, null, cost,
                    inner.path("rows_produced_per_join").asLong(0), children);
        }
        if (inner.has("table")) {
            QueryPlan table = table(inner.get("table"));
            return new QueryPlan(table.nodeType(), table.relationName(), table.indexName(),
                    cost > 0 ? cost : table.totalCost(), table.estimatedRows(), table.children());
        }
        return new QueryPlan("Query Block", null, null, cost, 0, List.of());
    }

    private QueryPlan table(JsonNode table) {
        String access = text(table, "access_type");
        String key = text(table, "key");
        String nodeType = "ALL".equals(access)
                ? "Seq Scan"
                : (key != null ? "Index Scan" : (access != null ? access : "Table"));
        long rows = table.has("rows_examined_per_scan")
                ? table.path("rows_examined_per_scan").asLong(0)
                : table.path("rows_produced_per_join").asLong(0);
        return new QueryPlan(nodeType, text(table, "table_name"), key,
                cost(table, "prefix_cost"), rows, List.of());
    }

    /** Reads a {@code cost_info} field, which MySQL renders as a numeric string. */
    private static double cost(JsonNode node, String field) {
        JsonNode costInfo = node.get("cost_info");
        if (costInfo == null) {
            return 0;
        }
        JsonNode value = costInfo.get(field);
        if (value == null) {
            return 0;
        }
        try {
            return Double.parseDouble(value.asText());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
