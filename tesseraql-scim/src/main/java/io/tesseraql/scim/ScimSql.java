package io.tesseraql.scim;

import io.tesseraql.core.sql.BoundParameter;
import io.tesseraql.core.sql.BoundSql;
import io.tesseraql.core.sql.SqlRenderer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

/** Renders and runs SCIM contract 2-way SQL against a datasource (design ch. 10.15). */
final class ScimSql {

    private ScimSql() {
    }

    static List<Map<String, Object>> queryAll(DataSource dataSource, String sql,
            Map<String, Object> params) throws SQLException {
        BoundSql bound = SqlRenderer.render(sql, params);
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(bound.sql())) {
            bind(statement, bound);
            try (ResultSet resultSet = statement.executeQuery()) {
                return readRows(resultSet);
            }
        }
    }

    static Map<String, Object> queryOne(DataSource dataSource, String sql,
            Map<String, Object> params)
            throws SQLException {
        List<Map<String, Object>> rows = queryAll(dataSource, sql, params);
        return rows.isEmpty() ? null : rows.get(0);
    }

    static int update(DataSource dataSource, String sql, Map<String, Object> params)
            throws SQLException {
        BoundSql bound = SqlRenderer.render(sql, params);
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(bound.sql())) {
            bind(statement, bound);
            return statement.executeUpdate();
        }
    }

    private static void bind(PreparedStatement statement, BoundSql bound) throws SQLException {
        List<BoundParameter> parameters = bound.parameters();
        for (int i = 0; i < parameters.size(); i++) {
            statement.setObject(i + 1, parameters.get(i).value());
        }
    }

    private static List<Map<String, Object>> readRows(ResultSet resultSet) throws SQLException {
        ResultSetMetaData metaData = resultSet.getMetaData();
        int columns = metaData.getColumnCount();
        List<Map<String, Object>> rows = new ArrayList<>();
        while (resultSet.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int col = 1; col <= columns; col++) {
                row.put(metaData.getColumnLabel(col), resultSet.getObject(col));
            }
            rows.add(row);
        }
        return rows;
    }
}
