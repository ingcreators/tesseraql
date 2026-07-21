package io.tesseraql.yaml.docs;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.sql.Sql2WayParser;
import io.tesseraql.core.sql.SqlNode;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.MigrationFile;
import io.tesseraql.yaml.manifest.RouteFile;
import io.tesseraql.yaml.model.InputField;
import io.tesseraql.yaml.model.NotifySpec;
import io.tesseraql.yaml.model.ResponseSpec;
import io.tesseraql.yaml.model.RouteDefinition;
import io.tesseraql.yaml.model.SecuritySpec;
import io.tesseraql.yaml.model.SqlBinding;
import io.tesseraql.yaml.model.ValidationRule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Generates the deterministic spec-layer documentation model from the route manifest (documentation
 * portal v1). Like {@code OpenApiGenerator} and {@code HtmxContractGenerator} the output is a
 * derived artifact — the Simple YAML routes and 2-way SQL remain the source of truth — and is
 * ordered for byte-stable serialization (routes by path then method, inputs by name, the rest in
 * authored order). Errors are raised in the {@link TqlDomain#REPORT} domain.
 *
 * <p>For each route it projects the request surface, security, validation, notifications, response
 * shape, and the bound SQL: the statement text plus the declared binds and {@code if}/{@code for}/
 * {@code scope} structure parsed by {@link Sql2WayParser}. It does not depend on test specs or a
 * database; {@code tesseraql-report}'s {@code AppDocGenerator} adds those.
 */
public final class RouteSpecGenerator {

    private static final TqlErrorCode GEN_ERROR = new TqlErrorCode(TqlDomain.REPORT, 2003);

    /** Builds the documentation model (deterministically ordered). */
    public RouteSpecModel generate(AppManifest manifest) {
        List<RouteFile> routes = new ArrayList<>(manifest.routes());
        routes.sort(Comparator.comparing(RouteFile::urlPath).thenComparing(RouteFile::httpMethod));
        List<RouteSpec> routeSpecs = new ArrayList<>();
        for (RouteFile route : routes) {
            routeSpecs.add(routeSpec(route));
        }
        List<RouteSpecModel.Migration> migrations = new ArrayList<>();
        for (MigrationFile migration : manifest.migrations()) {
            migrations.add(migration(manifest.appHome(), migration));
        }
        return new RouteSpecModel(routeSpecs, migrations);
    }

    private RouteSpec routeSpec(RouteFile route) {
        RouteDefinition definition = route.definition();
        return new RouteSpec(definition.id(), route.httpMethod(), route.urlPath(),
                definition.recipe(), definition.kind(), inputs(definition), security(definition),
                validations(definition), notifications(definition), response(definition),
                sqlStatements(route));
    }

    /** Declared inputs sorted by name — {@code RouteDefinition.input()} is unordered. */
    private List<RouteSpec.Input> inputs(RouteDefinition definition) {
        return definition.input().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> input(entry.getKey(), entry.getValue()))
                .toList();
    }

    private RouteSpec.Input input(String name, InputField field) {
        return new RouteSpec.Input(name, field.type() == null ? "string" : field.type(),
                field.required(), field.defaultValue(), field.min(), field.max(),
                field.maxLength(), field.enumValues(), field.format());
    }

    private RouteSpec.Security security(RouteDefinition definition) {
        SecuritySpec security = definition.security();
        if (security == null) {
            return null;
        }
        return new RouteSpec.Security(security.auth(), security.policy(), security.provider(),
                Boolean.TRUE.equals(security.csrf()));
    }

    private List<RouteSpec.Validation> validations(RouteDefinition definition) {
        List<RouteSpec.Validation> out = new ArrayList<>();
        definition.validate().forEach((id, rule) -> out.add(new RouteSpec.Validation(id,
                ruleKind(rule), rule.rule(), rule.file(), rule.field(), rule.when())));
        return out;
    }

    private static String ruleKind(ValidationRule rule) {
        if (rule.isExpression()) {
            return "expression";
        }
        return rule.isSql() ? "sql" : "unknown";
    }

    private List<RouteSpec.Notification> notifications(RouteDefinition definition) {
        List<RouteSpec.Notification> out = new ArrayList<>();
        definition.notifications().forEach((id, spec) -> out.add(notification(id, spec)));
        return out;
    }

    private RouteSpec.Notification notification(String id, NotifySpec spec) {
        return new RouteSpec.Notification(id, spec.channel(), spec.when(),
                List.copyOf(spec.payload().keySet()));
    }

    private RouteSpec.Response response(RouteDefinition definition) {
        ResponseSpec response = definition.response();
        if (response == null) {
            return null;
        }
        if (response.json() != null) {
            return new RouteSpec.Response("json", response.json().effectiveStatus(), null, null,
                    null);
        }
        if (response.html() != null) {
            return new RouteSpec.Response("html", response.html().effectiveStatus(),
                    response.html().template(), null, null);
        }
        if (response.stream() != null) {
            return new RouteSpec.Response("stream", null, null, response.stream().contentType(),
                    null);
        }
        if (response.redirect() != null) {
            return new RouteSpec.Response("redirect", response.redirect().effectiveStatus(), null,
                    null, response.redirect().location());
        }
        if (response.file() != null) {
            return new RouteSpec.Response("file", response.file().effectiveStatus(),
                    response.file().template(), response.file().effectiveContentType(), null);
        }
        return null;
    }

    /** The bound SQL statements: the main {@code sql}, ordered steps and queries, and transfer SQL. */
    private List<RouteSpec.SqlStatement> sqlStatements(RouteFile route) {
        Path dir = route.source().getParent();
        RouteDefinition definition = route.definition();
        List<RouteSpec.SqlStatement> out = new ArrayList<>();
        if (definition.sql() != null) {
            out.add(statement("sql", dir, definition.sql()));
        }
        definition.steps().forEach((id, binding) -> out.add(statement("step:" + id, dir, binding)));
        definition.queries()
                .forEach((id, binding) -> out.add(statement("query:" + id, dir, binding)));
        if (definition.fileImport() != null && definition.fileImport().sql() != null) {
            out.add(statement("import", dir, definition.fileImport().sql()));
        }
        if (definition.fileExport() != null) {
            if (definition.fileExport().sql() != null) {
                out.add(statement("export", dir, definition.fileExport().sql()));
            }
            if (definition.fileExport().after() != null
                    && definition.fileExport().after().sql() != null) {
                out.add(statement("export.after", dir, definition.fileExport().after().sql()));
            }
        }
        return out;
    }

    private RouteSpec.SqlStatement statement(String label, Path dir, SqlBinding binding) {
        String text = null;
        List<String> binds = List.of();
        List<RouteSpec.Control> structure = List.of();
        if (binding.file() != null) {
            text = readIfPresent(dir.resolve(binding.file()).normalize());
            if (text != null) {
                LinkedHashSet<String> bindSet = new LinkedHashSet<>();
                List<RouteSpec.Control> controls = new ArrayList<>();
                walk(parseQuietly(text), 0, bindSet, controls);
                binds = List.copyOf(bindSet);
                structure = controls;
            }
        }
        return new RouteSpec.SqlStatement(label, binding.file(), binding.contract(),
                binding.service(), binding.effectiveMode(), text, binds, structure);
    }

    /** Collects distinct binds (first-seen order) and the flattened control directives with depth. */
    private void walk(List<SqlNode> nodes, int depth, LinkedHashSet<String> binds,
            List<RouteSpec.Control> controls) {
        for (SqlNode node : nodes) {
            switch (node) {
                case SqlNode.Bind bind -> binds.add(bind.expressionSource());
                case SqlNode.ListBind bind -> binds.add(bind.expressionSource());
                case SqlNode.If conditional -> {
                    boolean first = true;
                    for (SqlNode.If.Branch branch : conditional.branches()) {
                        String kind = branch.conditionSource() == null
                                ? "else"
                                : (first ? "if" : "elseif");
                        controls.add(new RouteSpec.Control(kind, branch.conditionSource(), depth));
                        walk(branch.body(), depth + 1, binds, controls);
                        first = false;
                    }
                }
                case SqlNode.For loop -> {
                    String expression = loop.itemVar() + " : " + loop.listExpressionSource()
                            + (loop.separator() != null
                                    ? " separator '" + loop.separator() + "'"
                                    : "");
                    controls.add(new RouteSpec.Control("for", expression, depth));
                    walk(loop.body(), depth + 1, binds, controls);
                }
                case SqlNode.Scope scope -> {
                    String expression = scope.name()
                            + (scope.alias() != null ? " on " + scope.alias() : "")
                            + (scope.asBoolean() ? " as boolean" : "");
                    controls.add(new RouteSpec.Control("scope", expression, depth));
                }
                case SqlNode.Text ignored -> {
                    // Literal SQL text carries no binds or control structure.
                }
                case SqlNode.Embedded ignored -> {
                    // An embedded variable interpolates into the SQL text, not a ? bind, and adds no
                    // control structure; its placeholders are surfaced through the route's inputs.
                }
                case SqlNode.FilePath filePath -> binds.add(
                        "${" + filePath.channel() + "." + filePath.name() + "}"
                                + filePath.suffix());
            }
        }
    }

    private RouteSpecModel.Migration migration(Path appHome, MigrationFile migration) {
        String relative = appHome.relativize(migration.path()).toString().replace('\\', '/');
        return new RouteSpecModel.Migration(migration.datasource(), migration.vendor(),
                migration.version(), migration.description(), relative);
    }

    private static String readIfPresent(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return Files.readString(file);
        } catch (IOException ex) {
            throw new TqlException(GEN_ERROR,
                    "Failed to read SQL file " + file + ": " + ex.getMessage());
        }
    }

    /** Parses the SQL, degrading to no structure on a malformed template so docs still render. */
    private static List<SqlNode> parseQuietly(String text) {
        try {
            return Sql2WayParser.parse(text);
        } catch (TqlException ex) {
            return List.of();
        }
    }
}
