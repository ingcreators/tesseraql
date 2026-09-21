package io.tesseraql.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Every alert rule and every dashboard panel names a family the scrape renders
 * (docs/deployment-maturity.md decision 9, part 4).
 *
 * <p>A rules file is prose to Prometheus until it fires: an expression over a family that was
 * renamed, or never existed, evaluates to nothing and alerts on nothing — a silence that looks
 * like health. The families are harvested from the sources ({@code counter("…")},
 * {@code histogram("…")}, {@code gauge("…")} and the name constants the meter is called with),
 * normalised the way {@code PrometheusTextFormat} renders them, and each {@code tesseraql_…}
 * token in a rule or panel has to be one of them, with the suffix its kind carries. The variant
 * with a rule on an unknown family is red here.
 */
class PrometheusRulesLedgerTest {

    private static final Path REPO = Path.of("..");
    private static final Path RULES = REPO.resolve("deploy/prometheus/tesseraql-alerts.yml");
    private static final Path DASHBOARD = REPO.resolve("deploy/grafana/tesseraql-dashboard.json");

    /** A metric name literal in a main source: a meter name, or a constant the meter is called with. */
    private static final Pattern DECLARED = Pattern.compile("\"(tesseraql\\.[a-z][a-z.]*)\"");
    /** A family reference in an expression. */
    private static final Pattern REFERENCED = Pattern.compile("\\b(tesseraql_[a-z0-9_]+)");
    /** The suffixes a rendered family may carry, by what the meter records under the name. */
    private static final List<String> SUFFIXES = List.of("", "_total", "_seconds",
            "_seconds_bucket", "_seconds_count", "_seconds_sum");
    /** Prometheus' own families an expression may name. */
    private static final Set<String> BUILT_IN = Set.of("up");

    @Test
    void everyAlertRuleNamesAFamilyTheScrapeRenders() throws IOException {
        Set<String> families = declaredFamilies();
        List<String> expressions = expressionsOf(RULES, "expr:");
        assertThat(expressions).as("the rules file declares expressions").isNotEmpty();
        assertThat(unknownReferences(expressions, families))
                .as("families named by deploy/prometheus/tesseraql-alerts.yml that no source"
                        + " declares (a renamed or imagined family alerts on nothing)")
                .isEmpty();
    }

    @Test
    void everyDashboardPanelNamesAFamilyTheScrapeRenders() throws IOException {
        Set<String> families = declaredFamilies();
        List<String> expressions = expressionsOf(DASHBOARD, "\"expr\":");
        assertThat(expressions).as("the dashboard declares panel expressions").isNotEmpty();
        assertThat(unknownReferences(expressions, families))
                .as("families named by deploy/grafana/tesseraql-dashboard.json that no source"
                        + " declares")
                .isEmpty();
    }

    /** The capacity families of docs/deployment-maturity.md S2 are declared, and rendered by name. */
    @Test
    void theCapacityFamiliesAreDeclared() throws IOException {
        assertThat(declaredFamilies()).contains("tesseraql_http_in_flight",
                "tesseraql_http_refused", "tesseraql_lane_in_use", "tesseraql_lane_rejected",
                "tesseraql_pool_threads_awaiting");
    }

    private static Set<String> unknownReferences(List<String> expressions, Set<String> families) {
        Set<String> unknown = new TreeSet<>();
        for (String expression : expressions) {
            Matcher reference = REFERENCED.matcher(expression);
            while (reference.find()) {
                String name = reference.group(1);
                if (!BUILT_IN.contains(name) && SUFFIXES.stream()
                        .noneMatch(suffix -> name.endsWith(suffix)
                                && families.contains(
                                        name.substring(0, name.length() - suffix.length())))) {
                    unknown.add(name);
                }
            }
        }
        return unknown;
    }

    /** The lines of {@code file} that carry an expression, after the key. */
    private static List<String> expressionsOf(Path file, String key) throws IOException {
        assertThat(file).as("%s exists", file).exists();
        return Files.readAllLines(file).stream()
                .map(String::trim)
                .filter(line -> line.startsWith(key))
                .map(line -> line.substring(key.length()))
                .toList();
    }

    /** Every metric name the main sources declare, as the exposition spells it. */
    private static Set<String> declaredFamilies() throws IOException {
        Set<String> families = new TreeSet<>();
        try (Stream<Path> modules = Files.list(REPO)) {
            for (Path module : modules.filter(path -> path.getFileName().toString()
                    .startsWith("tesseraql-")).toList()) {
                Path sources = module.resolve("src/main/java");
                if (!Files.isDirectory(sources)) {
                    continue;
                }
                try (Stream<Path> files = Files.walk(sources)) {
                    for (Path file : files.filter(path -> path.toString().endsWith(".java"))
                            .toList()) {
                        Matcher declared = DECLARED.matcher(Files.readString(file));
                        while (declared.find()) {
                            families.add(declared.group(1).replace('.', '_'));
                        }
                    }
                }
            }
        }
        return families;
    }
}
