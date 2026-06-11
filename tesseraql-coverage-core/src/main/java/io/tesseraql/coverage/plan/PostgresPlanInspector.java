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
 * Obtains query plans from PostgreSQL via {@code EXPLAIN (FORMAT JSON)} (design ch. 46.5). This is
 * the {@code explain} level: the statement is planned but not executed.
 */
public final class PostgresPlanInspector implements PlanInspector {

    private static final TqlErrorCode EXPLAIN_ERROR = new TqlErrorCode(TqlDomain.PLAN, 1500);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String dialect() {
        return "postgres";
    }

    @Override
    public QueryPlan explain(Connection connection, String sql, List<Object> params) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("EXPLAIN (FORMAT JSON) " + sql)) {
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
            JsonNode root = MAPPER.readTree(json);
            JsonNode planNode = root.get(0).get("Plan");
            return toPlan(planNode);
        } catch (Exception ex) {
            throw new TqlException(EXPLAIN_ERROR, "Failed to parse EXPLAIN output: " + ex.getMessage());
        }
    }

    private QueryPlan toPlan(JsonNode node) {
        List<QueryPlan> children = new ArrayList<>();
        JsonNode plans = node.get("Plans");
        if (plans != null && plans.isArray()) {
            for (JsonNode child : plans) {
                children.add(toPlan(child));
            }
        }
        return new QueryPlan(
                text(node, "Node Type"),
                text(node, "Relation Name"),
                text(node, "Index Name"),
                node.path("Total Cost").asDouble(0),
                node.path("Plan Rows").asLong(0),
                children);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
