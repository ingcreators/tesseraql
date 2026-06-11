package io.tesseraql.core.sql;

import java.util.List;

/**
 * The result of rendering a 2-way SQL template against a set of parameters (design ch. 8.2).
 *
 * <p>The {@link #sql()} is ready for a JDBC {@code PreparedStatement}: directive comments are
 * stripped and bind sites are replaced with {@code ?} placeholders, in the same order as
 * {@link #parameters()}. The remaining fields carry diagnostics used by execution, coverage,
 * and query-plan tooling.
 *
 * @param sql           executable SQL with {@code ?} placeholders
 * @param parameters    positional bind parameters, in placeholder order
 * @param sourceMap     mapping from rendered offsets to source line numbers
 * @param coverageTrace lines emitted and branches taken during this render
 * @param variant       structural variant identity of this render
 */
public record BoundSql(
        String sql,
        List<BoundParameter> parameters,
        SourceMap sourceMap,
        CoverageTrace coverageTrace,
        SqlVariant variant) {

    public BoundSql {
        parameters = List.copyOf(parameters);
    }
}
