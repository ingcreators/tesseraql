package io.tesseraql.yaml.lint;

import io.tesseraql.core.sql.SqlNode;
import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.RouteFile;
import io.tesseraql.yaml.model.Binding;
import io.tesseraql.yaml.model.RouteDefinition;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The DuckDB/Parquet analytics stack: engines, file scopes and the SQL that
 * reaches them.
 *
 * <p>Extracted verbatim from {@code AppLinter} (docs/lint-restructure.md decision 1).
 */
final class DuckDbRules implements LintRule {

    /** The run's memoized IO and cross-rule state, set at the top of {@link #lint}. */
    private LintContext context;

    @Override
    public void lint(LintContext context, AppManifest manifest,
            List<LintFinding> findings) {
        this.context = context;
        lintDuckDb(context.appHome(), manifest, findings);
    }

    /**
     * The duckdb datasource kind (docs/duckdb.md) is a query engine, never a system of record —
     * {@code TQL-YAML-1040} holds the structural constraints: {@code main} can never be duckdb,
     * a duckdb datasource has no migration tree, its {@code fileScopes} must declare
     * traversal-free roots (with {@code partitionBy} limited to {@code tenant}), it is never a
     * projection target, and route pipelines on it are read-shaped. {@code TQL-SQL-2111} holds the
     * SQL-content rules: file placeholders only on duckdb SQL, only naming declared scopes, and
     * file-reading functions never taking a raw argument.
     */
    void lintDuckDb(Path appHome, AppManifest manifest, List<LintFinding> findings) {
        AppConfig config = manifest.config();
        String configSource = "config/tesseraql.yml";
        if (duckDbDatasource(config, "main")) {
            findings.add(new LintFinding("TQL-YAML-1040", "error", configSource,
                    "tesseraql.datasources.main cannot be a duckdb datasource - the engine holds"
                            + " nothing durable and framework tables live on main"));
        }
        if (config.navigate("tesseraql.datasources") instanceof java.util.Map<?, ?> datasources) {
            for (Object nameKey : datasources.keySet()) {
                String name = String.valueOf(nameKey);
                if (!duckDbDatasource(config, name)) {
                    continue;
                }
                lintFileScopes(config, name, configSource, findings);
                lintDuckDbEngineConfig(config, name, configSource, findings);
                if (Files.isDirectory(appHome.resolve("db").resolve(name).resolve("migration"))) {
                    findings.add(new LintFinding("TQL-YAML-1040", "error",
                            "db/" + name + "/migration",
                            "a duckdb datasource is a query engine with nothing durable to"
                                    + " migrate - remove db/" + name + "/migration"));
                }
            }
        }
        for (RouteFile route : manifest.routes()) {
            lintDuckDbRoute(appHome, config, route, findings);
        }
        for (io.tesseraql.yaml.manifest.JobFile job : manifest.jobs()) {
            String source = appHome.relativize(job.source()).toString().replace('\\', '/');
            String datasource = DocumentRules.declaredDatasource(job.definition().datasource())
                    ? job.definition().datasource()
                    : "main";
            if (DocumentRules.declaredDatasource(job.definition().datasource())
                    && !"main".equals(job.definition().datasource())
                    && config.navigate(
                            "tesseraql.datasources." + job.definition().datasource()) == null) {
                findings.add(new LintFinding("TQL-YAML-1035", "error", source,
                        "datasource '" + job.definition().datasource()
                                + "' is not declared under tesseraql.datasources"));
            }
            for (io.tesseraql.yaml.model.PipelineStep step : job.definition().effectiveSteps()) {
                if (step.sql() != null) {
                    lintDuckDbSql(config, job.source().getParent(), step.sql(), datasource,
                            source, findings);
                }
            }
        }
        for (RouteFile consumer : manifest.consumers()) {
            String datasource = consumer.definition().datasource();
            if (DocumentRules.declaredDatasource(datasource)
                    && duckDbDatasource(config, datasource)) {
                findings.add(new LintFinding("TQL-YAML-1040", "error",
                        appHome.relativize(consumer.source()).toString().replace('\\', '/'),
                        "a duckdb datasource is not a projection target - it holds nothing"
                                + " durable; project into a server datasource instead"));
            }
        }
    }

    /** Validates a duckdb datasource's declared {@code fileScopes} block. */
    private void lintFileScopes(AppConfig config, String name, String configSource,
            List<LintFinding> findings) {
        Object scopes = config.navigate("tesseraql.datasources." + name + ".duckdb.fileScopes");
        if (!(scopes instanceof java.util.Map<?, ?> scopeMap)) {
            return;
        }
        for (Object scopeKey : scopeMap.keySet()) {
            String scopeName = String.valueOf(scopeKey);
            String prefix = "tesseraql.datasources." + name + ".duckdb.fileScopes." + scopeName
                    + ".";
            String root = config.getString(prefix + "root").orElse(null);
            if (root == null || root.isBlank()) {
                findings.add(new LintFinding("TQL-YAML-1040", "error", configSource,
                        "file scope '" + scopeName + "' on datasource '" + name
                                + "' declares no root: directory"));
            } else if (root.contains("..") || root.indexOf('\'') >= 0 || root.indexOf('\\') >= 0) {
                findings.add(new LintFinding("TQL-YAML-1040", "error", configSource,
                        "file scope '" + scopeName + "' on datasource '" + name
                                + "' must declare a plain directory root without '..', quotes,"
                                + " or backslashes"));
            }
            String partitionBy = config.getString(prefix + "partitionBy").orElse(null);
            if (partitionBy != null && !"tenant".equals(partitionBy)) {
                findings.add(new LintFinding("TQL-YAML-1040", "error", configSource,
                        "file scope '" + scopeName + "' on datasource '" + name
                                + "' partitionBy must be 'tenant', not '" + partitionBy + "'"));
            }
        }
    }

    /** The per-route duckdb rules: read-shaped pipelines and the SQL-content file rules. */
    private void lintDuckDbRoute(Path appHome, AppConfig config, RouteFile route,
            List<LintFinding> findings) {
        RouteDefinition definition = route.definition();
        String source = appHome.relativize(route.source()).toString().replace('\\', '/');
        String routeDatasource = DocumentRules.declaredDatasource(definition.datasource())
                ? definition.datasource()
                : "main";
        if (duckDbDatasource(config, routeDatasource)
                && !DocumentRules.READ_DATASOURCE_RECIPES.contains(definition.recipe())) {
            findings.add(new LintFinding("TQL-YAML-1040", "error", source,
                    "a duckdb datasource serves reads - the '" + definition.recipe()
                            + "' recipe carries durable state and belongs on a server"
                            + " datasource"));
        }
        definition.sources().forEach((name, query) -> lintDuckDbSql(config,
                route.source().getParent(), query,
                DocumentRules.declaredDatasource(query.datasource())
                        ? query.datasource()
                        : routeDatasource,
                source, findings));
    }

    /**
     * Validates a duckdb datasource's {@code extensions:} and {@code attach:} declarations so a
     * misdeclaration is a lint error here, not a boot failure — mirroring the runtime's checks.
     */
    private void lintDuckDbEngineConfig(AppConfig config, String name, String configSource,
            List<LintFinding> findings) {
        Object extensions = config.navigate("tesseraql.datasources." + name + ".duckdb.extensions");
        if (extensions instanceof java.util.List<?> list) {
            for (Object entry : list) {
                if (!String.valueOf(entry).matches("[a-z0-9_]+")) {
                    findings.add(new LintFinding("TQL-YAML-1040", "error", configSource,
                            "duckdb extension '" + entry + "' on datasource '" + name
                                    + "' is not a plain extension name"));
                }
            }
        }
        if (config.navigate(
                "tesseraql.datasources." + name + ".duckdb.lake") instanceof java.util.Map<?, ?>) {
            String prefix = "tesseraql.datasources." + name + ".duckdb.lake.";
            String catalog = config.getString(prefix + "catalog").orElse("main");
            String schema = config.getString(prefix + "schema").orElse("ducklake");
            String data = config.getString(prefix + "data").orElse(null);
            String alias = config.getString(prefix + "as").orElse("lake");
            String mode = config.getString(prefix + "mode").orElse("readonly");
            if (data == null || data.isBlank()) {
                findings.add(new LintFinding("TQL-YAML-1040", "error", configSource,
                        "duckdb lake on datasource '" + name + "' declares no data: directory"));
            } else if (data.contains("..") || data.indexOf('\'') >= 0 || data.indexOf('\\') >= 0) {
                findings.add(new LintFinding("TQL-YAML-1040", "error", configSource,
                        "duckdb lake data: on datasource '" + name + "' must be a plain"
                                + " directory path without '..', quotes, or backslashes"));
            }
            if (!"main".equals(catalog)
                    && config.navigate("tesseraql.datasources." + catalog) == null) {
                findings.add(new LintFinding("TQL-YAML-1035", "error", configSource,
                        "datasource '" + catalog + "' is not declared under"
                                + " tesseraql.datasources"));
            }
            if (duckDbDatasource(config, catalog)) {
                findings.add(new LintFinding("TQL-YAML-1040", "error", configSource,
                        "duckdb lake catalog '" + catalog + "' must be a PostgreSQL datasource"
                                + " holding the lake metadata"));
            }
            if (!io.tesseraql.core.sql.SqlIdentifiers.isIdentifier(schema)
                    || !io.tesseraql.core.sql.SqlIdentifiers.isIdentifier(alias)
                    || "main".equals(alias)) {
                findings.add(new LintFinding("TQL-YAML-1040", "error", configSource,
                        "duckdb lake schema/as on datasource '" + name + "' must be plain"
                                + " identifiers, and as: never 'main'"));
            }
            if (!"readonly".equals(mode) && !"readwrite".equals(mode)) {
                findings.add(new LintFinding("TQL-YAML-1040", "error", configSource,
                        "duckdb lake mode must be readonly or readwrite, not '" + mode + "'"));
            }
            Object lakeExtensions = config.navigate(
                    "tesseraql.datasources." + name + ".duckdb.extensions");
            if (!(lakeExtensions instanceof java.util.List<?> lakeList)
                    || !lakeList.contains("ducklake") || !lakeList.contains("postgres")) {
                findings.add(new LintFinding("TQL-YAML-1040", "error", configSource,
                        "duckdb lake on datasource '" + name + "' needs extensions:"
                                + " [ducklake, postgres] declared, so offline cache provisioning"
                                + " covers them"));
            }
            if (data != null && data.startsWith("s3://")) {
                if (!(lakeExtensions instanceof java.util.List<?> remoteList)
                        || !remoteList.contains("httpfs")) {
                    findings.add(new LintFinding("TQL-YAML-1040", "error", configSource,
                            "a remote duckdb lake on datasource '" + name
                                    + "' needs httpfs in extensions:"));
                }
                if (!data.endsWith("/")) {
                    findings.add(new LintFinding("TQL-YAML-1040", "error", configSource,
                            "duckdb lake data: on datasource '" + name + "' must be an s3://"
                                    + " prefix ending in '/' (the scoped secret covers exactly"
                                    + " this prefix)"));
                }
                Object credentials = config.navigate(
                        "tesseraql.datasources." + name + ".duckdb.lake.credentials");
                boolean keyed = credentials instanceof java.util.Map<?, ?> map
                        && map.containsKey("keyId") && map.containsKey("secret");
                boolean chain = "instance".equals(config.getString(
                        "tesseraql.datasources." + name + ".duckdb.lake.credentials")
                        .orElse(null));
                if (!keyed && !chain) {
                    findings.add(new LintFinding("TQL-YAML-1040", "error", configSource,
                            "a remote duckdb lake on datasource '" + name + "' needs"
                                    + " credentials: {keyId, secret} secret references or"
                                    + " 'instance' for the AWS credential chain"));
                }
                if (config.navigate("tesseraql.datasources." + name
                        + ".duckdb.fileScopes") instanceof java.util.Map<?, ?>) {
                    findings.add(new LintFinding("TQL-YAML-1040", "error", configSource,
                            "datasource '" + name + "' declares a remote lake and fileScopes:"
                                    + " - a remote-lake datasource has no governed local-file"
                                    + " surface; compose across two duckdb datasources"));
                }
            } else if (data != null && data.contains("://")) {
                findings.add(new LintFinding("TQL-YAML-1040", "error", configSource,
                        "duckdb lake data: on datasource '" + name + "' must be a local"
                                + " directory or an s3:// prefix (S3-compatible stores use"
                                + " s3:// plus endpoint:)"));
            }
        }
        if (config.navigate("tesseraql.datasources." + name
                + ".duckdb.remotes") instanceof java.util.Map<?, ?> remotes) {
            for (Object remoteName : remotes.keySet()) {
                String prefix = "tesseraql.datasources." + name + ".duckdb.remotes."
                        + remoteName + ".";
                String url = config.getString(prefix + "url").orElse("");
                if (!url.startsWith("s3://") || !url.endsWith("/")) {
                    findings.add(new LintFinding("TQL-YAML-1040", "error", configSource,
                            "duckdb remote '" + remoteName + "' on datasource '" + name
                                    + "' needs url: an s3:// prefix ending in '/'"));
                }
                boolean keyed = config.navigate(prefix.substring(0, prefix.length() - 1)
                        + ".credentials") instanceof java.util.Map<?, ?> map
                        && map.containsKey("keyId") && map.containsKey("secret");
                boolean chain = "instance".equals(
                        config.getString(prefix + "credentials").orElse(null));
                if (!keyed && !chain) {
                    findings.add(new LintFinding("TQL-YAML-1040", "error", configSource,
                            "duckdb remote '" + remoteName + "' on datasource '" + name
                                    + "' needs credentials: {keyId, secret} or 'instance'"));
                }
            }
            Object remoteExtensions = config.navigate(
                    "tesseraql.datasources." + name + ".duckdb.extensions");
            if (!(remoteExtensions instanceof java.util.List<?> extList)
                    || !extList.contains("httpfs")) {
                findings.add(new LintFinding("TQL-YAML-1040", "error", configSource,
                        "duckdb remotes: on datasource '" + name
                                + "' need httpfs in extensions:"));
            }
        }
        Object attach = config.navigate("tesseraql.datasources." + name + ".duckdb.attach");
        if (!(attach instanceof java.util.List<?> entries)) {
            return;
        }
        for (int i = 0; i < entries.size(); i++) {
            if (!(entries.get(i) instanceof java.util.Map<?, ?> entry)) {
                findings.add(new LintFinding("TQL-YAML-1040", "error", configSource,
                        "duckdb attach entry " + i + " on datasource '" + name
                                + "' must be a mapping with datasource:"));
                continue;
            }
            String target = entry.get("datasource") == null
                    ? null
                    : config.resolve(String.valueOf(entry.get("datasource")));
            String alias = entry.get("as") == null
                    ? target
                    : config.resolve(String.valueOf(entry.get("as")));
            String mode = entry.get("mode") == null
                    ? "readonly"
                    : config.resolve(String.valueOf(entry.get("mode")));
            if (target == null || target.isBlank()) {
                findings.add(new LintFinding("TQL-YAML-1040", "error", configSource,
                        "duckdb attach entry " + i + " on datasource '" + name
                                + "' declares no datasource:"));
                continue;
            }
            if (!"main".equals(target)
                    && config.navigate("tesseraql.datasources." + target) == null) {
                findings.add(new LintFinding("TQL-YAML-1035", "error", configSource,
                        "datasource '" + target + "' is not declared under"
                                + " tesseraql.datasources"));
            }
            if (duckDbDatasource(config, target)) {
                findings.add(new LintFinding("TQL-YAML-1040", "error", configSource,
                        "duckdb attach target '" + target + "' is itself a duckdb datasource;"
                                + " attach targets are server datasources"));
            }
            if (!io.tesseraql.core.sql.SqlIdentifiers.isIdentifier(alias)
                    || "main".equals(alias)) {
                findings.add(new LintFinding("TQL-YAML-1040", "error", configSource,
                        "duckdb attach '" + target + "' on datasource '" + name
                                + "' needs as: a plain identifier other than 'main' (DuckDB's"
                                + " own default schema is named main)"));
            }
            if (!"readonly".equals(mode) && !"readwrite".equals(mode)) {
                findings.add(new LintFinding("TQL-YAML-1040", "error", configSource,
                        "duckdb attach '" + target + "' mode must be readonly or readwrite,"
                                + " not '" + mode + "'"));
            }
        }
    }

    /** Functions that read a file; on duckdb SQL their argument must be a file placeholder. */
    private static final Pattern FILE_FUNCTION = Pattern.compile(
            "\\b(?:read_csv_auto|read_csv|read_parquet|read_json_auto|read_json|read_text"
                    + "|read_blob|parquet_scan|glob)\\s*\\(\\s*([^\\s])");

    /** The SQL-content file rules for one binding, against its effective datasource. */
    private void lintDuckDbSql(AppConfig config, Path sourceDir, Binding sql,
            String datasource, String source, List<LintFinding> findings) {
        if (sql == null || sql.isContract() || sql.file() == null) {
            return;
        }
        Path sqlFile = sourceDir.resolve(sql.file());
        if (!Files.isRegularFile(sqlFile)) {
            return; // missing-file is reported separately
        }
        boolean duckDb = duckDbDatasource(config, datasource);
        String text = context.content(sqlFile);
        List<SqlNode> nodes = text == null ? null : context.sqlNodes(sqlFile);
        if (nodes == null) {
            return; // SQL syntax / IO errors surface through other checks
        }
        List<SqlNode.FilePath> filePaths = new ArrayList<>();
        SqlNode.walk(nodes, node -> {
            if (node instanceof SqlNode.FilePath filePath) {
                filePaths.add(filePath);
            }
        });
        for (SqlNode.FilePath filePath : filePaths) {
            String reference = "${" + filePath.channel() + "." + filePath.name() + "}";
            if (!duckDb) {
                findings.add(new LintFinding("TQL-SQL-2111", "error", source,
                        "File placeholder " + reference + " only resolves on a duckdb datasource;"
                                + " this SQL runs on '" + datasource + "'",
                        filePath.sourceLine(), null));
            } else if ("dataset".equals(filePath.channel())) {
                Map<String, String> params = sql.params() == null ? Map.of() : sql.params();
                if (!params.containsKey(filePath.name())) {
                    findings.add(new LintFinding("TQL-SQL-2111", "error", source,
                            "${dataset." + filePath.name() + "} needs a params: entry named '"
                                    + filePath.name() + "' binding the dataset reference",
                            filePath.sourceLine(), null));
                }
            } else if ("scope".equals(filePath.channel())
                    && (!(config.navigate("tesseraql.datasources." + datasource
                            + ".duckdb.fileScopes") instanceof java.util.Map<?, ?> scopeMap)
                            || !scopeMap.containsKey(filePath.name()))) {
                findings.add(new LintFinding("TQL-SQL-2111", "error", source,
                        "File scope '" + filePath.name() + "' is not declared under"
                                + " tesseraql.datasources." + datasource + ".duckdb.fileScopes",
                        filePath.sourceLine(), null));
            }
        }
        if (duckDb) {
            for (SqlNode.FilePath filePath : filePaths) {
                if ("remote".equals(filePath.channel())) {
                    if (!(config.navigate("tesseraql.datasources." + datasource
                            + ".duckdb.remotes") instanceof java.util.Map<?, ?> remoteMap)
                            || !remoteMap.containsKey(filePath.name())) {
                        findings.add(new LintFinding("TQL-SQL-2111", "error", source,
                                "Remote '" + filePath.name() + "' is not declared under"
                                        + " tesseraql.datasources." + datasource
                                        + ".duckdb.remotes",
                                filePath.sourceLine(), null));
                    }
                } else if (remoteTier(config, datasource)) {
                    findings.add(new LintFinding("TQL-SQL-2111", "error", source,
                            "A remote-tier datasource has no governed local-file surface;"
                                    + " ${scope.*}/${dataset.*} resolve on a local duckdb"
                                    + " datasource - compose across two datasources",
                            filePath.sourceLine(), null));
                }
            }
            lintEngineManagementStatements(text, source, findings);
            Matcher matcher = FILE_FUNCTION.matcher(text);
            while (matcher.find()) {
                // A placeholder site starts with the 2-way comment: `read_parquet(/* ${...} */ ...`.
                if (!"/".equals(matcher.group(1))) {
                    findings.add(new LintFinding("TQL-SQL-2111", "error", source,
                            "A file-reading function on a duckdb datasource must take a"
                                    + " ${scope.*} file placeholder, not a raw argument",
                            lineAt(text, matcher.start()), null));
                }
            }
        }
    }

    /**
     * App SQL on a duckdb datasource must be plain queries: engine-management statements are
     * init-time concerns the runtime owns. The local tier's fence refuses them at runtime
     * anyway; on the remote tier this rule is the load-bearing control (docs/duckdb.md,
     * decision point 13), so it errors at build time on both.
     */
    private void lintEngineManagementStatements(String text, String source,
            List<LintFinding> findings) {
        int offset = 0;
        for (String statement : text.split(";")) {
            String stripped = statement
                    .replaceAll("(?s)/\\*.*?\\*/", " ")
                    .replaceAll("(?m)--.*$", " ")
                    .strip()
                    .toUpperCase(java.util.Locale.ROOT);
            boolean management = stripped.startsWith("ATTACH") || stripped.startsWith("DETACH")
                    || stripped.startsWith("INSTALL") || stripped.startsWith("FORCE INSTALL")
                    || stripped.startsWith("LOAD ") || stripped.equals("LOAD")
                    || stripped.startsWith("SET ") || stripped.startsWith("RESET")
                    || stripped.startsWith("PRAGMA")
                    || stripped.matches("CREATE\\s+(OR\\s+REPLACE\\s+)?(PERSISTENT\\s+)?SECRET.*")
                    || stripped.startsWith("DROP SECRET");
            if (management) {
                findings.add(new LintFinding("TQL-SQL-2111", "error", source,
                        "App SQL on a duckdb datasource must be plain queries -"
                                + " ATTACH/DETACH/INSTALL/LOAD/CREATE SECRET/SET/PRAGMA are"
                                + " init-time concerns the runtime owns (docs/duckdb.md)",
                        lineAt(text, offset), null));
            }
            offset += statement.length() + 1;
        }
    }

    /** The 1-based line of a character offset in {@code text}. */
    private static int lineAt(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /** Whether the named duckdb datasource runs the remote tier (remote lake or remotes). */
    private static boolean remoteTier(AppConfig config, String name) {
        return remoteLake(config, name)
                || (duckDbDatasource(config, name)
                        && config.navigate("tesseraql.datasources." + name
                                + ".duckdb.remotes") instanceof java.util.Map<?, ?>);
    }

    /** Whether the named duckdb datasource declares a lake on object storage. */
    private static boolean remoteLake(AppConfig config, String name) {
        return duckDbDatasource(config, name)
                && config.navigate("tesseraql.datasources." + name
                        + ".duckdb.lake") instanceof java.util.Map<?, ?>
                && config.getString("tesseraql.datasources." + name + ".duckdb.lake.data")
                        .orElse("").startsWith("s3://");
    }

    /** Whether the named datasource resolves to the duckdb dialect (mirrors the compiler). */
    private static boolean duckDbDatasource(AppConfig config, String name) {
        String prefix = "tesseraql.datasources." + name + ".";
        String dialect = config.getString(prefix + "dialect").orElse(null);
        if (dialect != null) {
            return "duckdb".equalsIgnoreCase(dialect);
        }
        return config.getString(prefix + "jdbcUrl")
                .flatMap(io.tesseraql.core.dialect.Dialect::fromJdbcUrl)
                .filter(d -> d == io.tesseraql.core.dialect.Dialect.DUCKDB)
                .isPresent();
    }
}
