package io.tesseraql.pipeline.sql;

import io.tesseraql.core.sql.ScopeResolver;
import io.tesseraql.core.sql.Sql2WayParser;
import io.tesseraql.core.sql.SqlNode;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.TesseraqlProperties;
import io.tesseraql.pipeline.tenant.TenantRouting;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.sql.DataSource;

/**
 * A statement the application ships as a file: {@code sql: {file: …}} on a route.
 *
 * <p>Every method here was a private member of {@link SqlStep} and is unchanged. What moved is
 * only where they live, so the step stops knowing that its statement came from a file at all.
 */
public final class FileSqlSource implements SqlSource {

    private final String sqlPath;
    private final String datasource;
    private final String dialect;

    private List<SqlNode> nodes;

    /** @param datasource the connector name, before tenant routing has its say */
    public FileSqlSource(String sqlPath, String datasource, String dialect) {
        this.sqlPath = sqlPath;
        this.datasource = datasource == null ? "main" : datasource;
        this.dialect = dialect;
    }

    @Override
    public Statement resolve(Exchange exchange, String mode) throws Exception {
        return new Statement(sqlPath, "route", dataSource(exchange), dialect, nodes(exchange),
                scopeResolver(exchange), filePathResolver(exchange), true);
    }

    /**
     * The parsed statement, read and parsed once.
     *
     * <p>A producer parsed it in {@code doStart}, which is the lifecycle a service has and a step
     * does not. Parsing on first use keeps the property that matters — the file is read once, not
     * per request — without inventing a lifecycle for it (docs/camel-removal.md decision 2).
     */
    private synchronized List<SqlNode> nodes(Exchange exchange) throws Exception {
        if (nodes == null) {
            Path file = io.tesseraql.core.dialect.DialectSqlResolver.resolve(
                    Path.of(sqlPath), dialect);
            nodes = Sql2WayParser.parse(Files.readString(file), SqlStep.functions(exchange));
        }
        return nodes;
    }

    /**
     * The data-scope resolver bound by the runtime (roadmap Phase 29), or a resolver that rejects
     * any {@code /*%scope%/} directive when none is configured — so a scope directive in an app
     * without scopes fails loudly rather than silently bypassing scoping.
     */
    private ScopeResolver scopeResolver(Exchange exchange) {
        ScopeResolver resolver = exchange.beans().lookup(TesseraqlProperties.SCOPE_RESOLVER_BEAN,
                ScopeResolver.class);
        return resolver != null ? resolver : ScopeResolver.UNSUPPORTED;
    }

    /**
     * The file-scope resolver bound by the runtime (docs/duckdb.md), narrowed to this endpoint's
     * datasource. File placeholders only resolve on a duckdb endpoint — on any other dialect, and
     * when no resolver is bound, the renderer's reject-any-placeholder default applies, so a
     * {@code ${scope.*}} outside an analytics datasource fails loudly.
     */
    private io.tesseraql.core.sql.FilePathResolver filePathResolver(Exchange exchange) {
        if (!"duckdb".equals(dialect)) {
            return io.tesseraql.core.sql.FilePathResolver.UNSUPPORTED;
        }
        DatasourceFilePathResolver resolver = exchange.beans().lookup(
                TesseraqlProperties.FILE_PATH_RESOLVER_BEAN,
                DatasourceFilePathResolver.class);
        if (resolver == null) {
            return io.tesseraql.core.sql.FilePathResolver.UNSUPPORTED;
        }
        return (channel, name, suffix, context) -> resolver.resolve(
                datasource, channel, name, suffix, context);
    }

    /** Resolves the datasource for the exchange; see {@link TenantRouting} for the routing rule. */
    private DataSource dataSource(Exchange exchange) {
        return TenantRouting.dataSource(exchange, datasource);
    }
}
