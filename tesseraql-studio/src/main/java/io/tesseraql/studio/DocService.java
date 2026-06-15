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
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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

    /** The doc entry for one route id, or {@code null} when no such route exists. */
    public RouteEntry route(String id) {
        for (RouteEntry entry : spec().routes()) {
            if (entry.route() != null && id.equals(entry.route().id())) {
                return entry;
            }
        }
        return null;
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
        return hits.stream().filter(hit -> matchesFilters(hit.id(), overlay, filters)).toList();
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
            hits.add(new Hit(route.id(), route.method(), route.path(), 0));
            for (String term : tokenize(searchText(entry))) {
                index.computeIfAbsent(term, key -> new LinkedHashSet<>()).add(doc);
            }
            doc++;
        }
        this.corpus = List.copyOf(hits);
        this.inverted = index;
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
                    "Path escapes app home (design ch. 20.2): " + relativePath);
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

    /** One run's trend point from {@code history.json} (studio-side mirror of the build's entry). */
    public record HistoryPoint(String runId, String generatedAt, int total, long passed,
            long failed,
            double sqlLineRatio, double sqlBranchRatio, boolean gatePassed) {
    }

    /** A ranked search hit: the matched route's identity and its term-match score. */
    public record Hit(String id, String method, String path, int score) {

        Hit withScore(int score) {
            return new Hit(id, method, path, score);
        }
    }
}
