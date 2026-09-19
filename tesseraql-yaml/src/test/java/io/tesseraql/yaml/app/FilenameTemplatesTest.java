package io.tesseraql.yaml.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.yaml.app.ExportDeclarations.Site;
import io.tesseraql.yaml.app.ExportDeclarations.Surface;
import io.tesseraql.yaml.app.ExportDeclarations.Violation;
import io.tesseraql.yaml.app.FilenameTemplates.Problem;
import io.tesseraql.yaml.model.ExportSpec;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The one judgement of a download name's placeholders (docs/route-filename-placeholders.md
 * decision 5): four arms, each an error, from the roots the site resolves; {@code {key}} is
 * another rule's. The export-block predicate carries the arm, so a route's lint and build and a
 * job's lint and boot all refuse the same template with the same sentence.
 */
class FilenameTemplatesTest {

    /** An authenticated route declaring {@code month} under a URL carrying {@code {id}}. */
    private static final Site ROUTE = new Site("t", "route 'orders.dump'", Surface.QUERY_EXPORT,
            Set.of("month"), true, true, Map.of(), Set.of("id"));

    private static final Site PUBLIC = new Site("t", "route 'orders.dump'", Surface.QUERY_EXPORT,
            Set.of("month"), false, true, Map.of(), Set.of("id"));

    private static final Site STEP = new Site("t", "job 'daily' step 'report'", Surface.JOB,
            Set.of("region"), false, true);

    private static List<Violation> violations(Site site, String template) {
        return FilenameTemplates.violations(site, "export.filename", template, Set.of());
    }

    @Test
    void aResolvablePlaceholderIsNotAViolationOnEitherAltitude() {
        for (String template : List.of("orders-{params.month}.csv", "orders-{query.month}.csv",
                "order-{path.id}.pdf", "{principal.subject}.csv", "{tenant}.csv",
                "{request.locale}.csv", "{flags.beta}.csv", "{preference.theme}.csv",
                "{body.month}.csv", "orders-{key}.csv", "orders.csv", "")) {
            assertThat(violations(ROUTE, template)).as(template).isEmpty();
        }
        for (String template : List.of("report-{batch.businessDate}.csv",
                "report-{params.region}.csv", "{steps.extract.filename}", "{tenant}-{key}.csv")) {
            assertThat(violations(STEP, template)).as(template).isEmpty();
        }
        assertThat(violations(ROUTE, null)).isEmpty();
    }

    @Test
    void eachOfTheFourArmsIsAnErrorNamingTheSiteTheKeyAndThePlaceholder() {
        assertThat(violations(ROUTE, "orders-{batch.business-date}.csv")).singleElement()
                .satisfies(v -> {
                    assertThat(v.code()).isEqualTo(FilenameTemplates.UNRESOLVABLE_PLACEHOLDER);
                    assertThat(v.code().toString()).isEqualTo("TQL-YAML-1076");
                    assertThat(v.key()).isEqualTo("export.filename");
                    assertThat(v.message()).contains("app 't': route 'orders.dump'",
                            "export.filename:", "{batch.business-date}",
                            "not a dotted path", "delivers it literally");
                });
        assertThat(violations(ROUTE, "orders-{now}.csv")).singleElement()
                .extracting(Violation::message).asString()
                .contains("{now}", "names no request root", "params, query, path, body",
                        ", principal", "would render _");
        assertThat(violations(ROUTE, "orders-{params.year}.csv")).singleElement()
                .extracting(Violation::message).asString()
                .contains("{params.year}", "does not declare under input:");
        assertThat(violations(ROUTE, "orders-{query.year}.csv")).singleElement()
                .extracting(Violation::message).asString().contains("{query.year}");
        assertThat(violations(ROUTE, "order-{path.orderId}.pdf")).singleElement()
                .extracting(Violation::message).asString()
                .contains("{path.orderId}", "path parameter the route's URL does not declare");
    }

    @Test
    void aPrincipalResolvesOnlyWhereOneCanBeBound() {
        assertThat(violations(ROUTE, "{principal.subject}.csv")).isEmpty();
        assertThat(violations(PUBLIC, "{principal.subject}.csv")).singleElement()
                .extracting(Violation::message).asString()
                .contains("{principal.subject}", "names no request root",
                        "principal on an authenticated route");
    }

    @Test
    void aJobStepResolvesItsOwnRootsAndItsDeclaredInputs() {
        assertThat(violations(STEP, "{params.region}-{params.area}.csv")).singleElement()
                .extracting(Violation::message).asString()
                .contains("job 'daily' step 'report'", "{params.area}",
                        "the job does not declare under input:");
        assertThat(violations(STEP, "{query.region}.csv")).singleElement()
                .extracting(Violation::message).asString()
                .contains("{query.region}", "names no job context root",
                        "params, steps, batch, tenant");
        assertThat(violations(STEP, "{path.id}.csv")).singleElement()
                .extracting(Violation::message).asString().contains("names no job context root");
    }

    @Test
    void aDeclaredSourceIsARootWhereTheCallerSaysSo() {
        Site file = new Site("t", "route 'orders.print'", Surface.QUERY_EXPORT, Set.of(), true,
                true, Map.of(), Set.of());
        assertThat(FilenameTemplates.violations(file, "response.file.filename",
                "orders-{main.rowCount}.pdf", Set.of("main"))).isEmpty();
        assertThat(FilenameTemplates.violations(file, "response.file.filename",
                "orders-{main.rowCount}.pdf", Set.of())).singleElement()
                .extracting(Violation::message).asString()
                .contains("response.file.filename:", "{main.rowCount}");
    }

    @Test
    void everyWrittenPlaceholderIsClassifiedInTemplateOrderAndTheKeyIsNamedNotRefused() {
        assertThat(FilenameTemplates.classify(
                "{key}-{now}-{params.month}-{params.x}-{bad-one}-{path.z}", ROUTE, Set.of()))
                .extracting(f -> f.problem() + ":" + f.path())
                .containsExactly("KEY:key", "UNKNOWN_ROOT:now", "UNDECLARED_INPUT:params.x",
                        "MALFORMED:bad-one", "UNKNOWN_PATH_PARAMETER:path.z");
        assertThat(FilenameTemplates.classify("{key}", ROUTE, Set.of()))
                .extracting(FilenameTemplates.Finding::problem).containsExactly(Problem.KEY);
        assertThat(violations(ROUTE, "{key}.csv")).as("{key} is the split rule's").isEmpty();
    }

    @Test
    void theExportBlockPredicateCarriesTheArmForBothAltitudes() {
        ExportSpec spec = new ExportSpec("csv", "orders-{now}.csv", null, null, null, List.of(),
                null, null, null, null, null, null, null, null);
        assertThat(ExportDeclarations.violations(ROUTE, spec, null, null))
                .filteredOn(v -> v.code().equals(FilenameTemplates.UNRESOLVABLE_PLACEHOLDER))
                .singleElement().extracting(Violation::key).isEqualTo("export.filename");
        // A step with no format: is refused for the format AND for the name — the name arm
        // does not wait behind the format's early return.
        ExportSpec noFormat = new ExportSpec(null, "orders-{now}.csv", null, null, null,
                List.of(), null, null, null, null, null, null, null, null);
        assertThat(ExportDeclarations.violations(STEP, noFormat, null, null))
                .extracting(v -> v.code().toString())
                .contains("TQL-YAML-1076", "TQL-YAML-1041");
    }
}
