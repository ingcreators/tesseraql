package io.tesseraql.yaml.release;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestLoader;
import io.tesseraql.yaml.manifest.MigrationFile;
import io.tesseraql.yaml.manifest.RouteFile;
import io.tesseraql.yaml.openapi.OpenApiDiff;
import io.tesseraql.yaml.openapi.OpenApiGenerator;
import io.tesseraql.yaml.scaffold.CatalogSchema;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * "What does this deploy change" (roadmap Phase 46): a deterministic diff of two app trees —
 * the promotion baseline and the candidate — covering the served route surface, the OpenAPI
 * contract ({@link OpenApiDiff}), the migration list the deploy will run, security policy
 * changes, and (when both trees carry the {@code .tesseraql/docs/schema.json} sidecar) the
 * table-level schema delta. Pure file reads: no database, no network, byte-stable output —
 * fit for a CI governance gate and the docs portal alike.
 */
public final class ReleaseDiff {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ReleaseDiff() {
    }

    /** One route-surface change, keyed by the route's logical id. */
    public record RouteChange(String kind, String id, String method, String path,
            String detail) {
    }

    /** Security-policy changes under {@code tesseraql.security.policies}. */
    public record PolicyChanges(List<String> added, List<String> removed,
            List<String> changed) {
        public boolean isEmpty() {
            return added.isEmpty() && removed.isEmpty() && changed.isEmpty();
        }
    }

    /** Table-level schema delta from the two trees' introspection sidecars. */
    public record SchemaChanges(List<String> addedTables, List<String> removedTables,
            List<String> changedTables) {
        public boolean isEmpty() {
            return addedTables.isEmpty() && removedTables.isEmpty() && changedTables.isEmpty();
        }
    }

    /** The full report; {@code schema} is null when either tree lacks the sidecar. */
    public record Report(List<RouteChange> routes, OpenApiDiff.ApiChangelog api,
            List<String> newMigrations, PolicyChanges policies, SchemaChanges schema) {

        public boolean isEmpty() {
            return routes.isEmpty() && api.isEmpty() && newMigrations.isEmpty()
                    && policies.isEmpty() && (schema == null || schema.isEmpty());
        }
    }

    /** Diffs two app trees: the promotion {@code baseline} against the {@code candidate}. */
    public static Report between(Path baselineHome, Path candidateHome) {
        AppManifest baseline = new ManifestLoader().load(baselineHome);
        AppManifest candidate = new ManifestLoader().load(candidateHome);

        List<RouteChange> routes = routeChanges(baseline, candidate);
        OpenApiDiff.ApiChangelog api = new OpenApiDiff().diff(
                new OpenApiGenerator().toJson(baseline), new OpenApiGenerator().toJson(candidate));
        List<String> migrations = newMigrations(baseline, candidate);
        PolicyChanges policies = policyChanges(baseline, candidate);
        SchemaChanges schema = schemaChanges(baselineHome, candidateHome);
        return new Report(routes, api, migrations, policies, schema);
    }

    private static List<RouteChange> routeChanges(AppManifest baseline, AppManifest candidate) {
        Map<String, RouteFile> before = byId(baseline);
        Map<String, RouteFile> after = byId(candidate);
        List<RouteChange> out = new ArrayList<>();
        for (Map.Entry<String, RouteFile> entry : after.entrySet()) {
            RouteFile now = entry.getValue();
            RouteFile was = before.get(entry.getKey());
            if (was == null) {
                out.add(new RouteChange("ADDED", entry.getKey(), now.httpMethod(),
                        now.urlPath(), null));
            } else if (!was.httpMethod().equals(now.httpMethod())
                    || !was.urlPath().equals(now.urlPath())) {
                out.add(new RouteChange("CHANGED", entry.getKey(), now.httpMethod(),
                        now.urlPath(), "moved from " + was.httpMethod() + " " + was.urlPath()));
            } else if (!sameContent(was.source(), now.source())) {
                out.add(new RouteChange("CHANGED", entry.getKey(), now.httpMethod(),
                        now.urlPath(), "definition changed"));
            }
        }
        for (Map.Entry<String, RouteFile> entry : before.entrySet()) {
            if (!after.containsKey(entry.getKey())) {
                RouteFile was = entry.getValue();
                out.add(new RouteChange("REMOVED", entry.getKey(), was.httpMethod(),
                        was.urlPath(), null));
            }
        }
        out.sort(java.util.Comparator.comparing(RouteChange::id));
        return out;
    }

    private static Map<String, RouteFile> byId(AppManifest manifest) {
        Map<String, RouteFile> routes = new TreeMap<>();
        for (RouteFile route : manifest.routes()) {
            if (route.definition().id() != null) {
                routes.put(route.definition().id(), route);
            }
        }
        return routes;
    }

    private static boolean sameContent(Path a, Path b) {
        try {
            return Files.readString(a).equals(Files.readString(b));
        } catch (IOException ex) {
            return false;
        }
    }

    /** Migrations present in the candidate but not the baseline — what the deploy will run. */
    private static List<String> newMigrations(AppManifest baseline, AppManifest candidate) {
        TreeSet<String> before = new TreeSet<>();
        for (MigrationFile migration : baseline.migrations()) {
            before.add(migrationKey(migration));
        }
        List<String> out = new ArrayList<>();
        for (MigrationFile migration : candidate.migrations()) {
            String key = migrationKey(migration);
            if (!before.contains(key)) {
                out.add(key);
            }
        }
        out.sort(String::compareTo);
        return out;
    }

    private static String migrationKey(MigrationFile migration) {
        StringBuilder key = new StringBuilder(migration.datasource());
        if (migration.vendor() != null) {
            key.append('[').append(migration.vendor()).append(']');
        }
        return key.append(": ").append(migration.path().getFileName()).toString();
    }

    private static PolicyChanges policyChanges(AppManifest baseline, AppManifest candidate) {
        Map<String, Object> before = policyMap(baseline);
        Map<String, Object> after = policyMap(candidate);
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        for (String id : new TreeSet<>(after.keySet())) {
            if (!before.containsKey(id)) {
                added.add(id);
            } else if (!java.util.Objects.equals(before.get(id), after.get(id))) {
                changed.add(id);
            }
        }
        for (String id : new TreeSet<>(before.keySet())) {
            if (!after.containsKey(id)) {
                removed.add(id);
            }
        }
        return new PolicyChanges(added, removed, changed);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> policyMap(AppManifest manifest) {
        Object policies = manifest.config().navigate("tesseraql.security.policies");
        return policies instanceof Map
                ? new TreeMap<>((Map<String, Object>) policies)
                : Map.of();
    }

    /**
     * Table-level delta from the two trees' {@code .tesseraql/docs/schema.json} sidecars; null
     * when either side has none (the sidecar is a run artifact — a captured baseline tree may
     * carry it, a fresh checkout may not).
     */
    private static SchemaChanges schemaChanges(Path baselineHome, Path candidateHome) {
        Map<String, CatalogSchema.Table> before = tables(baselineHome);
        Map<String, CatalogSchema.Table> after = tables(candidateHome);
        if (before == null || after == null) {
            return null;
        }
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        after.forEach((name, table) -> {
            CatalogSchema.Table was = before.get(name);
            if (was == null) {
                added.add(name);
            } else if (!was.equals(table)) {
                changed.add(name);
            }
        });
        before.keySet().forEach(name -> {
            if (!after.containsKey(name)) {
                removed.add(name);
            }
        });
        return new SchemaChanges(added, removed, changed);
    }

    /** {@code datasource.table -> Table} from the sidecar, or null when absent/corrupt. */
    private static Map<String, CatalogSchema.Table> tables(Path appHome) {
        Path sidecar = appHome.resolve(".tesseraql/docs/schema.json");
        if (!Files.isRegularFile(sidecar)) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(Files.readString(sidecar));
            Map<String, CatalogSchema.Table> out = new TreeMap<>();
            JsonNode datasources = root.path("datasources");
            for (Map.Entry<String, JsonNode> entry : datasources.properties()) {
                CatalogSchema schema = MAPPER.convertValue(entry.getValue(),
                        CatalogSchema.class);
                if (schema != null && schema.tables() != null) {
                    for (CatalogSchema.Table table : schema.tables()) {
                        out.put(entry.getKey() + "." + table.name(), table);
                    }
                }
            }
            return out;
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    /** The report as promotion-gate-friendly Markdown (deterministic ordering throughout). */
    public static String toMarkdown(Report report) {
        StringBuilder out = new StringBuilder("# Release diff\n\n");
        if (report.isEmpty()) {
            return out.append("No changes: the candidate serves the same surface, contract,"
                    + " schema, migrations and policies as the baseline.\n").toString();
        }
        out.append("## Routes (").append(report.routes().size()).append(")\n\n");
        for (RouteChange change : report.routes()) {
            out.append("- ").append(change.kind()).append(' ').append(change.id())
                    .append(" — ").append(change.method()).append(' ').append(change.path());
            if (change.detail() != null) {
                out.append(" (").append(change.detail()).append(')');
            }
            out.append('\n');
        }
        out.append("\n## API contract (").append(report.api().entries().size())
                .append(")\n\n");
        for (OpenApiDiff.ApiChangelog.Entry entry : report.api().entries()) {
            out.append("- ").append(entry.kind()).append(' ').append(entry.method())
                    .append(' ').append(entry.path());
            if (!entry.details().isEmpty()) {
                out.append(": ").append(String.join("; ", entry.details()));
            }
            out.append('\n');
        }
        out.append("\n## Migrations this deploy runs (").append(report.newMigrations().size())
                .append(")\n\n");
        for (String migration : report.newMigrations()) {
            out.append("- ").append(migration).append('\n');
        }
        out.append("\n## Security policies\n\n");
        PolicyChanges policies = report.policies();
        appendNames(out, "Added", policies.added());
        appendNames(out, "Removed", policies.removed());
        appendNames(out, "Changed", policies.changed());
        if (report.schema() != null) {
            out.append("\n## Schema (introspection sidecars)\n\n");
            appendNames(out, "Added tables", report.schema().addedTables());
            appendNames(out, "Removed tables", report.schema().removedTables());
            appendNames(out, "Changed tables", report.schema().changedTables());
        } else {
            out.append("\n## Schema\n\nNo sidecar on one or both trees"
                    + " (run the `schema` goal to capture one).\n");
        }
        return out.toString();
    }

    /** The report as stable JSON, for artifacts and the docs portal. */
    public static String toJson(Report report) {
        try {
            Map<String, Object> tree = new LinkedHashMap<>();
            tree.put("schemaVersion", 1);
            tree.put("routes", report.routes());
            tree.put("api", report.api().entries());
            tree.put("newMigrations", report.newMigrations());
            tree.put("policies", report.policies());
            tree.put("schema", report.schema());
            tree.put("empty", report.isEmpty());
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(tree);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static void appendNames(StringBuilder out, String label, List<String> names) {
        out.append("- ").append(label).append(": ")
                .append(names.isEmpty() ? "none" : String.join(", ", names)).append('\n');
    }
}
