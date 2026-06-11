package io.tesseraql.coverage.plan;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * Obtains a normalized {@link QueryPlan} for a SQL statement (design ch. 46.5). One implementation
 * exists per dialect; {@link PostgresPlanInspector} is the first.
 */
public interface PlanInspector {

    /** Returns the dialect this inspector supports (e.g. {@code postgres}). */
    String dialect();

    /** Explains the SQL with the given positional bind values and returns the normalized plan. */
    QueryPlan explain(Connection connection, String sql, List<Object> params) throws SQLException;
}
