package io.tesseraql.test;

import io.tesseraql.core.expr.ExpressionFunctions;
import io.tesseraql.core.sql.BoundParameter;
import io.tesseraql.core.sql.BoundSql;
import io.tesseraql.core.sql.Sql2WayParser;
import io.tesseraql.core.sql.SqlNode;
import io.tesseraql.core.sql.SqlRenderer;
import io.tesseraql.coverage.SqlCoverableLines;
import io.tesseraql.coverage.SqlCoverage;
import io.tesseraql.identity.IdentityService;
import io.tesseraql.identity.RealmConfig;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestLoader;
import io.tesseraql.yaml.manifest.RouteFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

/**
 * What every case kind of a {@link TestRunner} run shares: the app home the declarations are
 * read from, the lazily loaded manifest, the datasource and the compiled scope resolver, the
 * identity service a contract case executes against, and the coverage recorder. The per-kind
 * evaluators hold one of these and nothing else of the runner's, so each kind stays readable on
 * its own.
 */
final class SuiteContext {

    private final DataSource dataSource;
    private final Path appHome;
    private final IdentityService identity;
    private final RealmConfig realm;
    private final SqlCoverage coverage;
    private final ExpressionFunctions functions;
    private AppManifest manifest;
    private io.tesseraql.core.sql.ScopeResolver scopeResolver;

    SuiteContext(DataSource dataSource, Path appHome, IdentityService identity, RealmConfig realm,
            SqlCoverage coverage, ExpressionFunctions functions) {
        this.dataSource = dataSource;
        this.appHome = appHome;
        this.identity = identity;
        this.realm = realm;
        this.coverage = coverage;
        this.functions = functions;
    }

    DataSource dataSource() {
        return dataSource;
    }

    Path appHome() {
        return appHome;
    }

    IdentityService identity() {
        return identity;
    }

    RealmConfig realm() {
        return realm;
    }

    SqlCoverage coverage() {
        return coverage;
    }

    /** The expression-function set every parse of this run resolves custom calls against. */
    ExpressionFunctions functions() {
        return functions;
    }

    AppManifest manifest() {
        if (manifest == null) {
            manifest = new ManifestLoader().load(appHome, functions);
        }
        return manifest;
    }

    /**
     * The production scope resolver over the app's {@code scope/} declarations
     * (docs/data-scoping.md), compiled once for the runner's database vendor — the same
     * arm matching and fragments the runtime uses, so a scoped statement renders here
     * exactly as it would on a request. An app with no scopes keeps the reject-any-scope
     * default: an accidental directive still fails loudly.
     */
    io.tesseraql.core.sql.ScopeResolver scopeResolver() {
        if (scopeResolver == null) {
            AppManifest loaded = manifest();
            scopeResolver = loaded.scopes().isEmpty()
                    ? io.tesseraql.core.sql.ScopeResolver.UNSUPPORTED
                    : new io.tesseraql.identity.scope.CompiledScopeResolver(loaded.scopes(),
                            io.tesseraql.core.util.DatabaseVendors.vendor(dataSource)
                                    .orElse("postgres"));
        }
        return scopeResolver;
    }

    /** The runner's database vendor, defaulted like every compile site of a suite run. */
    String vendor() {
        return io.tesseraql.core.util.DatabaseVendors.vendor(dataSource).orElse("postgres");
    }

    io.tesseraql.yaml.manifest.JobFile job(String jobId) {
        return manifest().jobs().stream()
                .filter(job -> jobId.equals(job.definition().id()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown job '" + jobId + "' in notify case"));
    }

    RouteFile route(String routeId) {
        if (routeId == null || routeId.isBlank()) {
            throw new IllegalArgumentException("A validation case needs a validate.route id");
        }
        return manifest().routes().stream()
                .filter(route -> routeId.equals(route.definition().id()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown route '" + routeId + "' in validation case"));
    }

    /** The coverage id of a SQL file: its app-home-relative path with forward slashes. */
    String sqlId(Path sqlFile) {
        return appHome.relativize(sqlFile).toString().replace('\\', '/');
    }

    /** Executes a 2-way SQL file on the given (case-transaction) connection, recording coverage. */
    SqlOutcome executeSql(Connection connection, Path sqlFile, Map<String, Object> params) {
        List<SqlNode> nodes = Sql2WayParser.parse(read(sqlFile), functions);
        BoundSql bound = SqlRenderer.render(nodes, params, scopeResolver(), params);
        if (coverage != null) {
            coverage.record(sqlId(sqlFile), bound.coverageTrace(),
                    SqlCoverableLines.compute(nodes));
        }
        try (PreparedStatement statement = connection.prepareStatement(bound.sql())) {
            for (int i = 0; i < bound.parameters().size(); i++) {
                BoundParameter parameter = bound.parameters().get(i);
                statement.setObject(i + 1, parameter.value());
            }
            if (statement.execute()) {
                try (ResultSet resultSet = statement.getResultSet()) {
                    return new SqlOutcome(readRows(resultSet), null);
                }
            }
            return new SqlOutcome(null, statement.getUpdateCount());
        } catch (java.sql.SQLException ex) {
            throw new IllegalStateException("SQL execution failed: " + ex.getMessage(), ex);
        }
    }

    static List<Map<String, Object>> readRows(ResultSet resultSet)
            throws java.sql.SQLException {
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

    /**
     * Seeds the case's {@code principal:} under the parameters (and so the scope context):
     * ambient {@code principal.*} binds and scope-arm params resolve against the same
     * {@link io.tesseraql.security.Principal} shape the runtime's request context carries.
     * Explicit {@code params.principal} wins, so pre-scoping suites keep their meaning.
     */
    static Map<String, Object> withPrincipal(Map<String, Object> params,
            TestSuite.PrincipalSpec principal) {
        if (principal == null || params.containsKey("principal")) {
            return params;
        }
        Map<String, Object> seeded = new java.util.LinkedHashMap<>(params);
        seeded.put("principal", new io.tesseraql.security.Principal(principal.subject(),
                principal.loginId(), null, null, principal.groups(), principal.roles(),
                principal.permissions(), principal.claims()));
        return seeded;
    }

    static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }
}
