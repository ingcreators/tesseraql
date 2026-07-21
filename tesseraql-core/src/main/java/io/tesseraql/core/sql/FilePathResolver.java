package io.tesseraql.core.sql;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import java.util.Map;

/**
 * Resolves a {@code /* ${scope.name}/rel/path *}{@code /} or {@code /* ${dataset.param} *}{@code /}
 * file placeholder into an absolute filesystem path (docs/duckdb.md). File placeholders are the
 * only way SQL on an analytics datasource names a file: the resolver derives the path from a
 * declared file scope (root + optional tenant partition + the parser-validated relative suffix) or
 * a dataset reference, and the renderer binds the result as an ordinary {@code ?} parameter — SQL
 * text never carries a dynamic path.
 *
 * <p>The {@link SqlRenderer} owns no knowledge of datasources, scope declarations, or tenants —
 * those live in modules above {@code tesseraql-core}. Render paths that can never carry a file
 * placeholder use {@link #UNSUPPORTED}, so a placeholder outside an analytics datasource fails
 * loudly instead of leaking an unresolved path.
 */
public interface FilePathResolver {

    /** TQL-SQL-2111: a file placeholder was rendered where no resolver is configured. */
    TqlErrorCode UNSUPPORTED_CODE = new TqlErrorCode(TqlDomain.SQL, 2111);

    /** The default for render paths without file scopes: rejects any file placeholder. */
    FilePathResolver UNSUPPORTED = (channel, name, suffix, context) -> {
        throw new TqlException(UNSUPPORTED_CODE,
                "No file scope resolver is configured to resolve ${" + channel + "." + name
                        + "}; file placeholders only resolve on a duckdb datasource");
    };

    /**
     * Resolves a file placeholder for the request context.
     *
     * @param channel {@code scope} or {@code dataset}
     * @param name    the scope name or dataset parameter named by the placeholder
     * @param suffix  the parser-validated relative path after the placeholder ({@code /a/b.parquet}
     *                or empty)
     * @param context the request execution context (it carries {@code tenant} and {@code params})
     * @return the absolute path to bind
     */
    String resolve(String channel, String name, String suffix, Map<String, Object> context);
}
