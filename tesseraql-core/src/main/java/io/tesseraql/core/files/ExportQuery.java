package io.tesseraql.core.files;

import java.nio.file.Path;
import java.util.Map;

/**
 * A named query an export composes around its rows (docs/export-pipeline.md, decision 2): the
 * order header a line-item document labels itself with, the totals its footer prints, the master
 * data a template resolves codes against.
 *
 * <p>It runs on the extraction's connection, inside the extraction's transaction and before it, so
 * a document reads exactly the state its rows came from. The result lands in
 * {@link ExportModel#values()} under {@code name}, shaped like a read route's named query —
 * {@code rows} and {@code rowCount} — so a template written against one reads the same as the
 * other.
 *
 * <p>It binds exactly as a read route's named query does (decision 2): its own {@code params:}
 * over the request's ambient binds. The declaring surface carries the source expressions
 * ({@code paramSources}); the executing surface resolves them against the request context once,
 * at request time, into {@code params} — a file export runs off the request thread, so the
 * values travel with the request rather than the expressions. Until 0.18.0 the record carried a
 * name and a path only and every named query rendered with {@code main}'s map: a header query
 * with its own {@code params:} bound null and printed an empty header, silently
 * (docs/audit-low-leads.md G28).
 *
 * @param name         the key the template reads it under
 * @param sqlFile      the 2-way SQL file, already resolved against the route or job directory
 * @param paramSources the declared {@code params:}, bind name to a dotted context path
 * @param params       the resolved binds, layered over the request's map when the query renders
 */
public record ExportQuery(String name, Path sqlFile, Map<String, String> paramSources,
        Map<String, Object> params) {

    /** The pre-params shape: a name and a file. */
    public ExportQuery(String name, Path sqlFile) {
        this(name, sqlFile, Map.of(), Map.of());
    }

    public ExportQuery {
        paramSources = paramSources == null
                ? Map.of()
                : io.tesseraql.core.util.OrderedCopies.map(paramSources);
        params = params == null
                ? Map.of()
                : io.tesseraql.core.util.OrderedCopies.mapAllowingNulls(params);
    }

    /** This query with its {@code params:} resolved against the request context. */
    public ExportQuery resolved(Map<String, Object> context) {
        if (paramSources.isEmpty()) {
            return this;
        }
        io.tesseraql.core.expr.EvaluationContext evaluation = new io.tesseraql.core.expr.EvaluationContext(
                context == null ? Map.of() : context);
        Map<String, Object> values = new java.util.LinkedHashMap<>();
        paramSources.forEach((bindName, sourceExpr) -> values.put(bindName,
                evaluation.resolve(java.util.Arrays.asList(sourceExpr.split("\\.")))));
        return new ExportQuery(name, sqlFile, paramSources, values);
    }

    /** The request's map with this query's own binds layered over it, by name. */
    public Map<String, Object> bindsOver(Map<String, Object> requestParams) {
        if (params.isEmpty()) {
            return requestParams;
        }
        Map<String, Object> merged = new java.util.LinkedHashMap<>(
                requestParams == null ? Map.of() : requestParams);
        merged.putAll(params);
        return merged;
    }
}
