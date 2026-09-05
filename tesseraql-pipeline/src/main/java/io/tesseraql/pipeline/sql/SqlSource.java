package io.tesseraql.pipeline.sql;

import io.tesseraql.core.sql.FilePathResolver;
import io.tesseraql.core.sql.ScopeResolver;
import io.tesseraql.core.sql.SqlNode;
import io.tesseraql.pipeline.Exchange;
import java.util.List;
import javax.sql.DataSource;

/**
 * Where a route's SQL comes from — a file the application ships, or a contract a realm satisfies.
 *
 * <p>The seam this draws is between WHERE a statement comes from and HOW it executes. Everything
 * about where it comes from — which file or contract, which connector, which vendor, what a render
 * may reach — is answered here. Everything about how it runs — the row bound, the statement
 * timeout, the span it hangs from, pagination, the count wrapper, error classification, the
 * slow-SQL ring, the result envelope, export — stays in the one {@link SqlStep} above it.
 *
 * <p>The seam exists because the alternative was a second step. A contract used to compile to its
 * own executor that took four constructor arguments where the SQL step took nine, so every
 * execution axis the framework gained — the statement timeout, tracing, pagination, the row bound —
 * had to be carried across that branch by hand, one at a time, and each arrived late. A source
 * answers one question and inherits the rest.
 *
 * <p>Resolution happens per exchange rather than at compile time, because a contract's realm is a
 * runtime bean: the compiler sees a mounted application's materialized home, not the deployment's
 * own, so a realm baked at build time would look for its SQL in a directory that cannot exist.
 */
public interface SqlSource {

    /**
     * The statement to run for this exchange.
     *
     * @param exchange the request being served, for the beans a source resolves against
     * @param mode the binding's mode, so a source can refuse a write it is not permitted to make
     */
    Statement resolve(Exchange exchange, String mode) throws Exception;

    /**
     * One resolved statement: its identity, its connection, its vendor and what a render may reach.
     *
     * <p>Every component is a core or JDK type, so a source may live in any module that already
     * depends on core — no module direction moves to accommodate this.
     *
     * @param id what names this statement in a span, a slow-SQL record and an error's source — a
     *     file path for a file, a contract name for a contract
     * @param surface the executor label the statement layer reports, so a dashboard can tell a
     *     route's statements from a bean's
     * @param dataSource the connection this statement runs on, already routed
     * @param dialect the vendor whose variants, label folding and pagination clause apply
     * @param nodes the parsed 2-way SQL
     * @param scopes how {@code ${scope.*}} resolves, or {@link ScopeResolver#UNSUPPORTED}
     * @param files how a file placeholder resolves, or {@link FilePathResolver#UNSUPPORTED}
     * @param exportable whether this statement may back a {@code query-export}
     */
    record Statement(String id, String surface, DataSource dataSource, String dialect,
            List<SqlNode> nodes, ScopeResolver scopes, FilePathResolver files, boolean exportable) {
    }
}
