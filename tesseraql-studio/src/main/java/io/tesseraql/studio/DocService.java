package io.tesseraql.studio;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.docs.RouteSpec;
import io.tesseraql.yaml.docs.RouteSpecGenerator;
import io.tesseraql.yaml.docs.RouteSpecModel;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.openapi.HtmxContractGenerator;
import io.tesseraql.yaml.openapi.OpenApiDiff;
import io.tesseraql.yaml.openapi.OpenApiGenerator;
import io.tesseraql.yaml.scaffold.CatalogSchema;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The backend for the in-Studio documentation portal (documentation portal v1): it serves the
 * spec-layer model for the app's routes and the Markdown bodies, all read-only.
 *
 * <p>Following the artifact-read boundary, it prefers the deterministic {@code spec.json} the build
 * packages under {@link #SPEC_PATH} (the full model, including the test cross-references that live
 * above {@code tesseraql-yaml}). When that artifact is absent — an unpackaged source/dev run — it
 * falls back to a reduced live model generated from the manifest by the yaml-side
 * {@link RouteSpecGenerator} (routes/SQL/migrations only, no test cross-references), so the portal
 * works in edit mode without pulling the test runner into Studio's dependencies.
 *
 * <p>All file access is confined to the app home (no {@code ../} traversal, design ch. 20.2).
 */
public final class DocService {

    /** App-home-relative location the build packages the documentation spec at (see AppPackager). */
    public static final String SPEC_PATH = ".tesseraql/docs/spec.json";

    /** App-home-relative location the {@code report} goal writes the run overlay at (portal v2). */
    public static final String REPORT_PATH = ".tesseraql/docs/report.json";

    /** App-home-relative location the {@code report} goal writes the run-trend ring at (portal v2). */
    public static final String HISTORY_PATH = ".tesseraql/docs/history.json";

    /** App-home-relative location the {@code schema} goal writes the catalog overlay at (portal v3). */
    public static final String SCHEMA_PATH = ".tesseraql/docs/schema.json";

    /** App-home-relative location of the optional schema baseline to diff the current schema against. */
    public static final String SCHEMA_BASELINE_PATH = ".tesseraql/docs/schema.baseline.json";

    /** App-home-relative location of the optional OpenAPI baseline to diff the current spec against. */
    public static final String OPENAPI_BASELINE_PATH = ".tesseraql/docs/openapi.baseline.json";

    private static final String ROUTE_URL = "/_tesseraql/studio/ui/docs/route?id=";
    private static final String TABLE_URL = "/_tesseraql/studio/ui/docs/schema/table?";

    private static final TqlErrorCode TRAVERSAL = new TqlErrorCode(TqlDomain.STUDIO, 4003);
    private static final TqlErrorCode READ_ERROR = new TqlErrorCode(TqlDomain.STUDIO, 4041);
    private static final TqlErrorCode NOT_FOUND = new TqlErrorCode(TqlDomain.STUDIO, 4042);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final Pattern NON_WORD = Pattern.compile("[^a-z0-9]+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /** Overlay-aware search filters: {@code key:value} tokens that narrow by the run report. */
    private static final Set<String> FILTERS = Set.of("status:failing", "status:passing",
            "coverage:covered", "coverage:untested");

    private final AppManifest manifest;
    private final Path appHome;
    private volatile List<Hit> corpus;
    private volatile Map<String, Set<Integer>> inverted;
    private volatile Map<String, RouteUsage> tableUsage;

    public DocService(AppManifest manifest) {
        this.manifest = manifest;
        this.appHome = manifest.appHome();
    }

    /** The application name shown in the portal chrome. */
    public String appName() {
        return manifest.config().getString("tesseraql.app.name").orElse("app");
    }

    /** Whether the deterministic spec.json artifact was packaged with the app (vs. a live run). */
    public boolean hasPackagedSpec() {
        return Files.isRegularFile(appHome.resolve(SPEC_PATH));
    }

    /**
     * The documentation spec: the packaged {@code spec.json} when present (the full model with test
     * cross-references), otherwise a reduced live model from the manifest (no test cross-references).
     */
    public DocSpec spec() {
        Path spec = appHome.resolve(SPEC_PATH);
        if (Files.isRegularFile(spec)) {
            try {
                return MAPPER.readValue(spec.toFile(), DocSpec.class);
            } catch (IOException ex) {
                throw new TqlException(READ_ERROR,
                        "Failed to read " + SPEC_PATH + ": " + ex.getMessage());
            }
        }
        RouteSpecModel live = new RouteSpecGenerator().generate(manifest);
        List<RouteEntry> routes = new ArrayList<>();
        for (RouteSpec route : live.routes()) {
            routes.add(new RouteEntry(route, List.of()));
        }
        return new DocSpec(routes, live.migrations());
    }

    /** Whether the run overlay ({@code report.json}) is present in the app home (portal v2). */
    public boolean hasReport() {
        return Files.isRegularFile(appHome.resolve(REPORT_PATH));
    }

    /**
     * The run overlay ({@code report.json}) when present, otherwise {@code null}. The overlay is
     * optional and run-dependent (it is not packed into the {@code .tqlapp}); a corrupt or
     * unreadable overlay degrades to {@code null} so the spec-layer portal keeps working.
     */
    public ReportOverlay report() {
        Path report = appHome.resolve(REPORT_PATH);
        if (!Files.isRegularFile(report)) {
            return null;
        }
        try {
            return MAPPER.readValue(report.toFile(), ReportOverlay.class);
        } catch (IOException ex) {
            return null;
        }
    }

    /**
     * The bounded run-trend history ({@code history.json}), oldest run first, or an empty list when
     * the file is absent or unreadable. Feeds the coverage dashboard's trend sparklines (portal v2).
     */
    public List<HistoryPoint> history() {
        Path file = appHome.resolve(HISTORY_PATH);
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            return List.of(MAPPER.readValue(file.toFile(), HistoryPoint[].class));
        } catch (IOException ex) {
            return List.of();
        }
    }

    /** Whether the schema overlay ({@code schema.json}) is present in the app home (portal v3). */
    public boolean hasSchema() {
        return Files.isRegularFile(appHome.resolve(SCHEMA_PATH));
    }

    /**
     * The schema overlay ({@code schema.json}) when present, otherwise {@code null}. Like the run
     * overlay it is optional and run-dependent (not packed into the {@code .tqlapp}); a corrupt or
     * unreadable file degrades to {@code null} so the spec-layer portal keeps working.
     */
    public SchemaOverlay schema() {
        Path file = appHome.resolve(SCHEMA_PATH);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return MAPPER.readValue(file.toFile(), SchemaOverlay.class);
        } catch (IOException ex) {
            return null;
        }
    }

    /** Whether a schema baseline sidecar is present to diff the current schema against. */
    public boolean hasSchemaBaseline() {
        return Files.isRegularFile(appHome.resolve(SCHEMA_BASELINE_PATH));
    }

    /**
     * The migration DDL transforming the schema baseline ({@link #SCHEMA_BASELINE_PATH}, a captured
     * {@code schema.json}) into the current schema — for capturing direct database changes back into a
     * migration (Studio backlog: migration authoring, schema diff). {@code null} when no baseline is
     * present; an empty string when the schemas match; otherwise additive DDL plus commented-out
     * destructive changes, generated by {@link SchemaDiff}. A corrupt baseline degrades to
     * {@code null}.
     */
    public String schemaDiffDdl() {
        Path baselineFile = appHome.resolve(SCHEMA_BASELINE_PATH);
        if (!Files.isRegularFile(baselineFile)) {
            return null;
        }
        SchemaOverlay baseline;
        try {
            baseline = MAPPER.readValue(baselineFile.toFile(), SchemaOverlay.class);
        } catch (IOException ex) {
            return null;
        }
        return SchemaDiff.generate(baseline, schema());
    }

    /** One introspected table by datasource and name, or {@code null} when no such table exists. */
    public CatalogSchema.Table table(String datasource, String name) {
        SchemaOverlay overlay = schema();
        if (overlay == null || name == null) {
            return null;
        }
        CatalogSchema catalog = overlay.datasource(datasource);
        if (catalog == null) {
            return null;
        }
        return catalog.tables().stream()
                .filter(table -> name.equals(table.name()))
                .findFirst().orElse(null);
    }

    /**
     * A lowercased table name &rarr; its schema table-page URL, across every datasource in the schema
     * overlay (empty when {@code schema.json} is absent). The route reference uses it to cross-link a
     * route's inferred data dependencies to the tables they touch (Studio backlog: the SQL&rarr;table
     * dependency graph); a dependency whose table is not introspected stays plain text. A name in two
     * datasources keeps the first (insertion-ordered) page.
     */
    public Map<String, String> tableLinks() {
        SchemaOverlay overlay = schema();
        if (overlay == null) {
            return Map.of();
        }
        Map<String, String> links = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, CatalogSchema> ds : overlay.datasources().entrySet()) {
            for (CatalogSchema.Table table : ds.getValue().tables()) {
                links.putIfAbsent(table.name().toLowerCase(Locale.ROOT),
                        tableUrl(ds.getKey(), table.name()));
            }
        }
        return links;
    }

    /**
     * Every introspected table name across the schema overlay, sorted and de-duplicated, for the
     * Studio DDL builder's table dropdown (migration authoring). Empty when no {@code schema.json} is
     * present (the builder then falls back to a free-text field).
     */
    public List<String> tableNames() {
        SchemaOverlay overlay = schema();
        if (overlay == null) {
            return List.of();
        }
        java.util.TreeSet<String> names = new java.util.TreeSet<>();
        for (CatalogSchema catalog : overlay.datasources().values()) {
            for (CatalogSchema.Table table : catalog.tables()) {
                names.add(table.name());
            }
        }
        return List.copyOf(names);
    }

    /**
     * The column names of the first introspected table matching {@code name}, for the DDL builder's
     * column autocomplete (migration authoring). Empty when no schema overlay or no such table.
     */
    public List<String> columnNames(String name) {
        SchemaOverlay overlay = schema();
        if (overlay == null || name == null) {
            return List.of();
        }
        for (CatalogSchema catalog : overlay.datasources().values()) {
            for (CatalogSchema.Table table : catalog.tables()) {
                if (name.equals(table.name())) {
                    return table.columns().stream().map(CatalogSchema.Column::name).toList();
                }
            }
        }
        return List.of();
    }

    /**
     * The first introspected table matching {@code name} across the schema overlay (with its columns
     * and primary key), for the 2-way SQL builder, or {@code null} when no schema overlay or no such
     * table.
     */
    public CatalogSchema.Table tableByName(String name) {
        SchemaOverlay overlay = schema();
        if (overlay == null || name == null) {
            return null;
        }
        for (CatalogSchema catalog : overlay.datasources().values()) {
            for (CatalogSchema.Table table : catalog.tables()) {
                if (name.equals(table.name())) {
                    return table;
                }
            }
        }
        return null;
    }

    /** The doc entry for one route id, or {@code null} when no such route exists. */
    public RouteEntry route(String id) {
        for (RouteEntry entry : spec().routes()) {
            if (entry.route() != null && id.equals(entry.route().id())) {
                return entry;
            }
        }
        return null;
    }

    /** One declared field domain with the routes referencing it (docs/field-domains.md). */
    public record DomainEntry(String name, io.tesseraql.yaml.model.InputField definition,
            List<RouteRef> referencedBy) {
    }

    /**
     * The app's field domains, each with the routes whose {@code input:} references it — drawn
     * from the same loader the manifest resolves with, so the page and the runtime agree.
     */
    public List<DomainEntry> domains() {
        io.tesseraql.yaml.domain.FieldDomains declared = io.tesseraql.yaml.domain.FieldDomains
                .load(appHome);
        List<DomainEntry> entries = new ArrayList<>();
        declared.domains().forEach((name, definition) -> {
            List<RouteRef> refs = new ArrayList<>();
            for (RouteEntry entry : spec().routes()) {
                RouteSpec route = entry.route();
                if (route == null) {
                    continue;
                }
                if (route.inputs().stream().anyMatch(in -> name.equals(in.domain()))) {
                    refs.add(new RouteRef(route.id(), route.method(), routeUrl(route.id())));
                }
            }
            entries.add(new DomainEntry(name, definition, refs));
        });
        entries.sort(java.util.Comparator.comparing(DomainEntry::name));
        return entries;
    }

    /** The app-level constraint catalog (docs/field-domains.md): DB constraint name to mapping. */
    public Map<String, io.tesseraql.yaml.model.ErrorsSpec.ConstraintMapping> constraintCatalog() {
        return io.tesseraql.yaml.domain.FieldDomains.load(appHome).constraints();
    }

    /**
     * Column name to domain name for one table, via the scaffolder's {@code <table>.<field>}
     * naming convention — the table page chips each column that has a live domain.
     */
    public Map<String, String> domainsForTable(String tableName) {
        Set<String> declared = io.tesseraql.yaml.domain.FieldDomains.load(appHome).domains()
                .keySet();
        Map<String, String> byColumn = new LinkedHashMap<>();
        if (tableName == null) {
            return byColumn;
        }
        String prefix = tableName.toLowerCase(Locale.ROOT) + ".";
        for (String name : declared) {
            if (name.startsWith(prefix)) {
                byColumn.put(snake(name.substring(prefix.length())), name);
            }
        }
        return byColumn;
    }

    /** camelCase field name back to its snake_case column (the scaffolder's forward mapping). */
    private static String snake(String camel) {
        StringBuilder out = new StringBuilder();
        for (char c : camel.toCharArray()) {
            if (Character.isUpperCase(c)) {
                out.append('_').append(Character.toLowerCase(c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * The routes whose SQL reads from or writes to the given table — the reverse of the route page's
     * data dependencies (Studio backlog: the SQL&rarr;table dependency graph). Computed over every
     * route's bound 2-way SQL by {@link SqlTableReferences} and cached at first use; the table page
     * cross-links each entry back to its route reference. An empty usage for an unknown/untouched
     * table.
     */
    public RouteUsage routesForTable(String tableName) {
        if (tableName == null) {
            return RouteUsage.EMPTY;
        }
        ensureTableUsage();
        return tableUsage.getOrDefault(tableName.toLowerCase(Locale.ROOT), RouteUsage.EMPTY);
    }

    /** Builds the reverse table&rarr;routes index once, keyed by lowercased table name. */
    private synchronized void ensureTableUsage() {
        if (tableUsage != null) {
            return;
        }
        Map<String, List<RouteRef>> readers = new HashMap<>();
        Map<String, List<RouteRef>> writers = new HashMap<>();
        for (RouteEntry entry : spec().routes()) {
            RouteSpec route = entry.route();
            if (route == null) {
                continue;
            }
            RouteRef ref = new RouteRef(route.id(), route.method(), routeUrl(route.id()));
            Set<String> reads = new LinkedHashSet<>();
            Set<String> writes = new LinkedHashSet<>();
            for (RouteSpec.SqlStatement statement : route.sql()) {
                if (statement.statement() == null) {
                    continue;
                }
                for (io.tesseraql.core.sql.SqlTableReferences.TableRef table : io.tesseraql.core.sql.SqlTableReferences
                        .extract(statement.statement())) {
                    (table.access() == io.tesseraql.core.sql.SqlTableReferences.Access.READ
                            ? reads
                            : writes).add(table.table().toLowerCase(Locale.ROOT));
                }
            }
            reads.forEach(name -> readers.computeIfAbsent(name, key -> new ArrayList<>()).add(ref));
            writes.forEach(
                    name -> writers.computeIfAbsent(name, key -> new ArrayList<>()).add(ref));
        }
        Set<String> names = new java.util.TreeSet<>();
        names.addAll(readers.keySet());
        names.addAll(writers.keySet());
        Map<String, RouteUsage> usage = new java.util.LinkedHashMap<>();
        for (String name : names) {
            usage.put(name,
                    new RouteUsage(sortById(readers.get(name)), sortById(writers.get(name))));
        }
        this.tableUsage = usage;
    }

    private static List<RouteRef> sortById(List<RouteRef> refs) {
        if (refs == null) {
            return List.of();
        }
        List<RouteRef> sorted = new ArrayList<>(refs);
        sorted.sort(Comparator.comparing(RouteRef::id));
        return sorted;
    }

    /**
     * Ranked route hits for a free-text query, over a small in-memory inverted index built once at
     * first use (option C — sufficient for an app-scoped corpus, adds no dependency). Each route is
     * a document over its id, path, recipe, inputs, security, bound SQL, and covering tests; a query
     * term matches a document term exactly or as a prefix (so live-search ranks as the user types),
     * and a route's score is the number of distinct query terms it matches. A blank query matches
     * nothing.
     */
    public List<Hit> search(String query) {
        if (query == null) {
            return List.of();
        }
        Set<String> filters = new LinkedHashSet<>();
        StringBuilder free = new StringBuilder();
        for (String token : WHITESPACE.split(query.trim())) {
            String lower = token.toLowerCase(Locale.ROOT);
            if (FILTERS.contains(lower)) {
                filters.add(lower);
            } else if (!token.isBlank()) {
                free.append(token).append(' ');
            }
        }
        List<String> terms = tokenize(free.toString());
        if (terms.isEmpty() && filters.isEmpty()) {
            return List.of();
        }
        ensureIndex();
        List<Hit> hits = terms.isEmpty() ? allHits() : scoredHits(terms);
        if (filters.isEmpty()) {
            return hits;
        }
        ReportOverlay overlay = report();
        // Status/coverage filters are route-only concepts; schema (table) hits drop out under them.
        return hits.stream()
                .filter(hit -> !"TABLE".equals(hit.method())
                        && matchesFilters(hit.id(), overlay, filters))
                .toList();
    }

    /** Routes ranked by the number of distinct query terms they match (descending), then by id. */
    private List<Hit> scoredHits(List<String> terms) {
        Map<Integer, Integer> scores = new HashMap<>();
        for (String term : terms) {
            Set<Integer> matched = new LinkedHashSet<>();
            for (Map.Entry<String, Set<Integer>> entry : inverted.entrySet()) {
                if (entry.getKey().startsWith(term)) {
                    matched.addAll(entry.getValue());
                }
            }
            for (Integer doc : matched) {
                scores.merge(doc, 1, Integer::sum);
            }
        }
        return scores.entrySet().stream()
                .sorted(Comparator.<Map.Entry<Integer, Integer>>comparingInt(Map.Entry::getValue)
                        .reversed().thenComparing(entry -> corpus.get(entry.getKey()).id()))
                .map(entry -> corpus.get(entry.getKey()).withScore(entry.getValue()))
                .toList();
    }

    /** Every indexed route, by id — the candidate set for a filter-only query (no free-text terms). */
    private List<Hit> allHits() {
        return corpus.stream().sorted(Comparator.comparing(Hit::id)).toList();
    }

    /** Whether a route satisfies every overlay status/coverage filter, AND-combined. */
    private static boolean matchesFilters(String routeId, ReportOverlay overlay,
            Set<String> filters) {
        ReportOverlay.RouteReport report = overlay == null ? null : overlay.routeReport(routeId);
        for (String filter : filters) {
            if (!matchesFilter(report, filter)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesFilter(ReportOverlay.RouteReport report, String filter) {
        return switch (filter) {
            case "coverage:covered" -> report != null && report.covered();
            case "coverage:untested" -> report == null || !report.covered();
            case "status:failing" -> report != null
                    && report.tests().stream().anyMatch(test -> !test.passed());
            case "status:passing" -> report != null && !report.tests().isEmpty()
                    && report.tests().stream().allMatch(ReportOverlay.CaseResult::passed);
            default -> true;
        };
    }

    private synchronized void ensureIndex() {
        if (inverted != null) {
            return;
        }
        List<Hit> hits = new ArrayList<>();
        Map<String, Set<Integer>> index = new HashMap<>();
        int doc = 0;
        for (RouteEntry entry : spec().routes()) {
            RouteSpec route = entry.route();
            hits.add(new Hit(route.id(), route.method(), route.path(), routeUrl(route.id()), 0));
            for (String term : tokenize(searchText(entry))) {
                index.computeIfAbsent(term, key -> new LinkedHashSet<>()).add(doc);
            }
            doc++;
        }
        // Schema tables join the corpus so a table or column name resolves to its table page
        // (portal v3). They carry the synthetic "TABLE" method so status/coverage filters skip them.
        SchemaOverlay schema = schema();
        if (schema != null) {
            for (Map.Entry<String, CatalogSchema> ds : schema.datasources().entrySet()) {
                for (CatalogSchema.Table table : ds.getValue().tables()) {
                    hits.add(new Hit(table.name(), "TABLE", ds.getKey(),
                            tableUrl(ds.getKey(), table.name()), 0));
                    for (String term : tokenize(tableSearchText(ds.getKey(), table))) {
                        index.computeIfAbsent(term, key -> new LinkedHashSet<>()).add(doc);
                    }
                    doc++;
                }
            }
        }
        this.corpus = List.copyOf(hits);
        this.inverted = index;
    }

    /** The searchable text of one table: its datasource, name, type, and column names. */
    private static String tableSearchText(String datasource, CatalogSchema.Table table) {
        List<String> parts = new ArrayList<>();
        add(parts, datasource, table.name(), table.type());
        table.columns().forEach(column -> add(parts, column.name()));
        return String.join(" ", parts);
    }

    private static String routeUrl(String id) {
        return ROUTE_URL + URLEncoder.encode(id == null ? "" : id, StandardCharsets.UTF_8);
    }

    private static String tableUrl(String datasource, String name) {
        return TABLE_URL + "ds="
                + URLEncoder.encode(datasource == null ? "" : datasource, StandardCharsets.UTF_8)
                + "&name=" + URLEncoder.encode(name == null ? "" : name, StandardCharsets.UTF_8);
    }

    /** The searchable text of one route: its identity, surface, security, SQL, and covering tests. */
    private static String searchText(RouteEntry entry) {
        RouteSpec route = entry.route();
        List<String> parts = new ArrayList<>();
        add(parts, route.id(), route.method(), route.path(), route.recipe(), route.kind());
        route.inputs().forEach(input -> add(parts, input.name()));
        if (route.security() != null) {
            add(parts, route.security().auth(), route.security().policy());
        }
        route.validations().forEach(rule -> add(parts, rule.id()));
        route.notifications().forEach(notify -> add(parts, notify.id(), notify.channel()));
        route.sql().forEach(statement -> {
            add(parts, statement.file(), statement.contract(), statement.service());
            statement.binds().forEach(bind -> add(parts, bind));
        });
        entry.tests().forEach(test -> add(parts, test.name(), test.target()));
        return String.join(" ", parts);
    }

    private static void add(List<String> parts, String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                parts.add(value);
            }
        }
    }

    private static List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        for (String token : NON_WORD.split(text.toLowerCase(Locale.ROOT))) {
            if (!token.isBlank()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    /**
     * The app's OpenAPI 3 document as pretty JSON, generated live from the manifest by the canonical
     * {@link OpenApiGenerator} (the same generator the build's {@code generate} goal writes
     * {@code openapi.json} with, so the portal download is byte-identical to the build artifact). A
     * derived view of the routes; the Simple YAML routes remain the source of truth.
     */
    public String openApiJson() {
        return new OpenApiGenerator().toJson(manifest);
    }

    /** Whether an OpenAPI baseline sidecar is present to diff the current spec against. */
    public boolean hasApiBaseline() {
        return Files.isRegularFile(appHome.resolve(OPENAPI_BASELINE_PATH));
    }

    /**
     * The API changelog of the current OpenAPI document against the baseline sidecar
     * ({@link #OPENAPI_BASELINE_PATH}) — what operations were added, removed, or changed since the
     * operator captured that baseline (typically a previously-released {@code openapi.json}). Returns
     * {@code null} when no baseline is present; a corrupt/unreadable baseline degrades to {@code null}
     * so the export page keeps working. Diffed by the canonical {@link OpenApiDiff}.
     */
    public OpenApiDiff.ApiChangelog apiChangelog() {
        Path baseline = appHome.resolve(OPENAPI_BASELINE_PATH);
        if (!Files.isRegularFile(baseline)) {
            return null;
        }
        try {
            return new OpenApiDiff().diff(Files.readString(baseline), openApiJson());
        } catch (IOException | TqlException ex) {
            return null;
        }
    }

    /**
     * The app's htmx interaction contract as pretty JSON, generated live from the manifest by the
     * canonical {@link HtmxContractGenerator} (the same generator the build writes
     * {@code htmx-contract.json} with). Like {@link #openApiJson()}, a derived, deterministic view.
     */
    public String htmxContractJson() {
        return new HtmxContractGenerator().toJson(manifest);
    }

    /**
     * The route catalog as flat rows for a printable export (documentation portal F8): one row per
     * route with its id, HTTP method, path, recipe, and covering-test count. The runtime renders
     * these rows to a PDF table through the canonical PDF codec; keeping the projection here (and the
     * rendering in the runtime) leaves Studio free of the optional {@code tesseraql-pdf} stack, like
     * the editor's PDF preview. Insertion-ordered so the PDF columns line up with the keys.
     */
    public List<Map<String, Object>> routeCatalog() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (RouteEntry entry : spec().routes()) {
            RouteSpec route = entry.route();
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("id", route.id());
            row.put("method", route.method());
            row.put("path", route.path());
            row.put("recipe", route.recipe());
            row.put("tests", entry.tests().size());
            rows.add(row);
        }
        return rows;
    }

    /** Reads a hand-written Markdown doc under the app home and renders it to CSP-safe HTML. */
    public String markdown(String relativePath) {
        Path file = resolve(relativePath);
        if (!Files.isRegularFile(file)) {
            throw new TqlException(NOT_FOUND, "No such doc: " + relativePath);
        }
        try {
            return DocMarkdown.toHtml(Files.readString(file));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private Path resolve(String relativePath) {
        Path resolved = appHome.resolve(relativePath).normalize();
        if (!resolved.startsWith(appHome)) {
            throw new TqlException(TRAVERSAL,
                    "Path escapes app home: " + relativePath);
        }
        return resolved;
    }

    /**
     * The documentation spec model (the studio-side mirror of the build's {@code spec.json}): each
     * route's spec with its covering test cases, plus the migration listing.
     */
    public record DocSpec(List<RouteEntry> routes, List<RouteSpecModel.Migration> migrations) {

        public DocSpec {
            routes = routes == null ? List.of() : List.copyOf(routes);
            migrations = migrations == null ? List.of() : List.copyOf(migrations);
        }
    }

    /** One route's reference: its spec and the test cases that cover it (empty in a live run). */
    public record RouteEntry(RouteSpec route, List<TestRef> tests) {

        public RouteEntry {
            tests = tests == null ? List.of() : List.copyOf(tests);
        }
    }

    /** A covering test case projected to the facts the portal shows. */
    public record TestRef(String name, String kind, String target) {
    }

    /** A reference to a route from the table page: its id, HTTP method, and detail link. */
    public record RouteRef(String id, String method, String url) {
    }

    /**
     * The routes that touch one table, split by access — the reverse of a route's data dependencies.
     * Each list is sorted by route id for a stable display.
     */
    public record RouteUsage(List<RouteRef> readers, List<RouteRef> writers) {

        static final RouteUsage EMPTY = new RouteUsage(List.of(), List.of());

        public RouteUsage {
            readers = List.copyOf(readers);
            writers = List.copyOf(writers);
        }

        /** Whether no route reads or writes the table. */
        public boolean isEmpty() {
            return readers.isEmpty() && writers.isEmpty();
        }
    }

    /** One run's trend point from {@code history.json} (studio-side mirror of the build's entry). */
    public record HistoryPoint(String runId, String generatedAt, int total, long passed,
            long failed,
            double sqlLineRatio, double sqlBranchRatio, boolean gatePassed) {
    }

    /**
     * A ranked search hit: the matched document's identity, its detail link, and its term-match
     * score. A hit is a route (an HTTP {@code method} and route id) or a schema table (the synthetic
     * {@code TABLE} method, the datasource as {@code path}, and the table name as {@code id}).
     */
    public record Hit(String id, String method, String path, String url, int score) {

        Hit withScore(int score) {
            return new Hit(id, method, path, url, score);
        }
    }
}
