package io.tesseraql.coverage.plan;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Obtains query plans from Oracle via {@code EXPLAIN PLAN} and the {@code PLAN_TABLE} (design ch. 46.5),
 * normalizing them into the dialect-independent {@link QueryPlan}. {@code TABLE ACCESS FULL} maps to the
 * common {@code Seq Scan} the guard recognizes; index operations map to {@code Index Scan}.
 *
 * <p>The {@code PLAN_TABLE} normalization is unit-tested against captured rows; the live
 * integration test runs against a real Oracle Free container behind
 * {@code -Dtesseraql.dialect.its=true} (the image is large, so it is opt-in).
 */
public final class OraclePlanInspector implements PlanInspector {

    private static final String PLAN_QUERY = "SELECT id, parent_id, operation, options, object_name, "
            + "cardinality, cost FROM plan_table WHERE statement_id = ? ORDER BY id";

    @Override
    public String dialect() {
        return "oracle";
    }

    @Override
    public QueryPlan explain(Connection connection, String sql, List<Object> params) throws SQLException {
        String statementId = "TQL_" + Long.toHexString(System.nanoTime());
        try (PreparedStatement delete =
                connection.prepareStatement("DELETE FROM plan_table WHERE statement_id = ?")) {
            delete.setString(1, statementId);
            delete.executeUpdate();
        }
        try (PreparedStatement explain = connection.prepareStatement(
                "EXPLAIN PLAN SET STATEMENT_ID = '" + statementId + "' FOR " + sql)) {
            for (int i = 0; i < params.size(); i++) {
                explain.setObject(i + 1, params.get(i));
            }
            explain.execute();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement(PLAN_QUERY)) {
            query.setString(1, statementId);
            try (ResultSet resultSet = query.executeQuery()) {
                while (resultSet.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", resultSet.getObject("id"));
                    row.put("parent_id", resultSet.getObject("parent_id"));
                    row.put("operation", resultSet.getString("operation"));
                    row.put("options", resultSet.getString("options"));
                    row.put("object_name", resultSet.getString("object_name"));
                    row.put("cardinality", resultSet.getObject("cardinality"));
                    row.put("cost", resultSet.getObject("cost"));
                    rows.add(row);
                }
            }
        }
        return parse(rows);
    }

    /** Normalizes {@code PLAN_TABLE} rows (keyed id/parent_id/operation/options/object_name/...). */
    static QueryPlan parse(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return new QueryPlan("Query Plan", null, null, 0, 0, List.of());
        }
        Map<Long, List<Map<String, Object>>> byParent = new LinkedHashMap<>();
        Map<String, Object> root = null;
        for (Map<String, Object> row : rows) {
            Long parent = asLong(row.get("parent_id"));
            if (parent == null) {
                root = row;
            } else {
                byParent.computeIfAbsent(parent, key -> new ArrayList<>()).add(row);
            }
        }
        if (root == null) {
            root = rows.get(0);
        }
        return build(root, byParent);
    }

    private static QueryPlan build(Map<String, Object> row, Map<Long, List<Map<String, Object>>> byParent) {
        Long id = asLong(row.get("id"));
        List<QueryPlan> children = new ArrayList<>();
        for (Map<String, Object> child : byParent.getOrDefault(id, List.of())) {
            children.add(build(child, byParent));
        }
        return new QueryPlan(
                mapOperation(string(row.get("operation")), string(row.get("options"))),
                string(row.get("object_name")),
                null,
                asDouble(row.get("cost")),
                asLong(row.get("cardinality")) == null ? 0 : asLong(row.get("cardinality")),
                children);
    }

    private static String mapOperation(String operation, String options) {
        if (operation == null) {
            return "Operator";
        }
        if ("TABLE ACCESS".equals(operation) && "FULL".equals(options)) {
            return "Seq Scan";
        }
        if (operation.startsWith("INDEX")) {
            return "Index Scan";
        }
        return options == null || options.isEmpty() ? operation : operation + " (" + options + ")";
    }

    private static Long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static double asDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0;
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
