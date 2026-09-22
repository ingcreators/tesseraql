package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Lint rules for declarative views (roadmap Phase 39, {@code TQL-VIEW-33xx}). */
class AppLinterViewTest {

    /** A view-backed list page, its data SQL, and the POST action route a form derives from. */
    private static void writeApp(Path dir, String viewYaml) throws Exception {
        Files.createDirectories(dir.resolve("web/items"));
        Files.writeString(dir.resolve("web/items/list.sql"), "select id, name from items\n");
        Files.writeString(dir.resolve("web/items/get.yml"), """
                version: tesseraql/v1
                id: items.page
                kind: route
                recipe: query-html
                sources:
                  main:
                    sql:
                      file: list.sql
                response:
                  html:
                    view: items
                """);
        Files.writeString(dir.resolve("web/items/items.view.yml"), viewYaml);
        Files.createDirectories(dir.resolve("web/items/create"));
        Files.writeString(dir.resolve("web/items/create/insert.sql"),
                "insert into items (name) values (/* name */ 'x')\n");
        Files.writeString(dir.resolve("web/items/create/post.yml"), """
                version: tesseraql/v1
                id: items.create
                kind: route
                recipe: command-json
                input:
                  name: { type: string, required: true, maxLength: 200 }
                steps:
                  - id: main
                    sql:
                      file: insert.sql
                      mode: update
                      params:
                        name: params.name
                """);
    }

    private static List<String> viewCodes(List<LintFinding> findings) {
        return findings.stream().map(LintFinding::code).filter(c -> c.startsWith("TQL-VIEW"))
                .toList();
    }

    /**
     * A view with its envelope and no {@code recipe:} — the mid-edit shape — used to escape the
     * linter as a NullPointerException (docs/audit-low-leads.md slice 8); it is one finding at
     * the document, the shape every other unparseable view already had.
     */
    @Test
    void aViewWithoutARecipeIsOneFindingNotACrash(@TempDir Path dir) throws Exception {
        writeApp(dir, "version: tesseraql/v1\nkind: view\n");
        List<LintFinding> findings = new AppLinter().lint(dir);
        assertThat(findings).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-VIEW-3301");
            assertThat(finding.source()).isEqualTo("web/items/items.view.yml");
            assertThat(finding.message()).contains("must declare recipe:");
        });
    }

    @Test
    void aWellFormedListViewProducesNoFindings(@TempDir Path dir) throws Exception {
        writeApp(dir, "version: tesseraql/v1\nkind: view\nrecipe: list\n");
        assertThat(viewCodes(new AppLinter().lint(dir))).isEmpty();
    }

    @Test
    void aPlainColumnLinkPlaceholderProducesNoFindings(@TempDir Path dir) throws Exception {
        writeApp(dir, """
                version: tesseraql/v1
                kind: view
                recipe: list
                columns:
                  - name: name
                    link: /items/{id}/lines/{line_no}
                """);
        assertThat(viewCodes(new AppLinter().lint(dir))).isEmpty();
    }

    @Test
    void aFilterMustNameADeclaredRouteInput(@TempDir Path dir) throws Exception {
        writeApp(dir, """
                version: tesseraql/v1
                kind: view
                recipe: list
                filters: [ghost]
                """);
        assertThat(viewCodes(new AppLinter().lint(dir))).contains("TQL-VIEW-3323");
    }

    @Test
    void aPresetParamMustNameADeclaredRouteInput(@TempDir Path dir) throws Exception {
        writeApp(dir, """
                version: tesseraql/v1
                kind: view
                recipe: list
                presets:
                  - name: Ghosts
                    params: { ghost: "1" }
                """);
        assertThat(viewCodes(new AppLinter().lint(dir))).contains("TQL-VIEW-3324");
    }

    @Test
    void aPresetMayPinTheFrameworkSortParams(@TempDir Path dir) throws Exception {
        writeApp(dir, """
                version: tesseraql/v1
                kind: view
                recipe: list
                presets:
                  - name: Newest
                    params: { sort: name, dir: desc }
                """);
        assertThat(viewCodes(new AppLinter().lint(dir))).isEmpty();
    }

    @Test
    void aBulkActionMustMatchAPostRoute(@TempDir Path dir) throws Exception {
        writeApp(dir, """
                version: tesseraql/v1
                kind: view
                recipe: list
                key: id
                actions:
                  - label: Ghost
                    action: /items/ghost
                """);
        assertThat(viewCodes(new AppLinter().lint(dir))).contains("TQL-VIEW-3325");
    }

    /**
     * The export lint's fixture (docs/list-export.md decision 6): a list route with a question
     * — a search, a filter, a sort — and an export route at the path the view names.
     */
    private static void writeExportApp(Path dir, String viewYaml, String exportDir,
            String exportFile, String exportYaml) throws Exception {
        Files.createDirectories(dir.resolve("web/items"));
        Files.writeString(dir.resolve("web/items/list.sql"),
                "select id, name, status from items /*# order by {sort} */\n");
        Files.writeString(dir.resolve("web/items/get.yml"), """
                version: tesseraql/v1
                id: items.page
                kind: route
                recipe: query-html
                input:
                  q: { type: string, required: false, maxLength: 100 }
                  status: { type: string, required: false, enum: [OPEN, CLOSED] }
                  sort:
                    type: sort
                    columns: [name, status]
                    default: name
                sources:
                  main:
                    sql:
                      file: list.sql
                      params:
                        q: query.q
                        status: query.status
                        sort: params.sortSql
                response:
                  html:
                    view: items
                """);
        Files.writeString(dir.resolve("web/items/items.view.yml"), viewYaml);
        Files.createDirectories(dir.resolve(exportDir));
        Files.writeString(dir.resolve(exportDir).resolve(exportFile), exportYaml);
    }

    /** The export route that accepts the list's question: its inputs, its statement. */
    private static final String MATCHING_EXPORT = """
            version: tesseraql/v1
            id: items.export
            kind: route
            recipe: %s
            input:
              q: { type: string, required: false, maxLength: 100 }
              status: { type: string, required: false, enum: [OPEN, CLOSED] }
              sort:
                type: sort
                columns: [name, status]
                default: name
            export:
              format: csv
              filename: items.csv
            sources:
              main:
                sql:
                  file: ../list.sql
                  params:
                    q: query.q
                    status: query.status
                    sort: params.sortSql
            """;

    private static final String EXPORTING_VIEW = """
            version: tesseraql/v1
            kind: view
            recipe: list
            search: q
            filters: [status]
            exports: [/items/export]
            """;

    private static List<String> exportFindings(List<LintFinding> findings) {
        return findings.stream().filter(f -> "TQL-VIEW-3331".equals(f.code()))
                .map(LintFinding::message).toList();
    }

    @Test
    void anExportTargetingAQueryExportGetOrAFileExportPostLintsClean(@TempDir Path dir)
            throws Exception {
        writeExportApp(dir, EXPORTING_VIEW, "web/items/export", "get.yml",
                MATCHING_EXPORT.formatted("query-export"));
        assertThat(viewCodes(new AppLinter().lint(dir))).isEmpty();
        writeExportApp(dir.resolve("async"), EXPORTING_VIEW, "web/items/export", "post.yml",
                MATCHING_EXPORT.formatted("file-export"));
        assertThat(viewCodes(new AppLinter().lint(dir.resolve("async")))).isEmpty();
    }

    @Test
    void anExportMustTargetAnExportRoute(@TempDir Path dir) throws Exception {
        // A path nothing answers, and a path a command route answers: neither is an export.
        writeExportApp(dir, """
                version: tesseraql/v1
                kind: view
                recipe: list
                exports: [/items/ghost, /items/create]
                """, "web/items/create", "post.yml", """
                version: tesseraql/v1
                id: items.create
                kind: route
                recipe: command-json
                input:
                  name: { type: string, required: true }
                steps:
                  - id: main
                    sql:
                      file: insert.sql
                      mode: update
                      params:
                        name: params.name
                """);
        Files.writeString(dir.resolve("web/items/create/insert.sql"),
                "insert into items (name) values (/* name */ 'x')\n");
        List<String> findings = exportFindings(new AppLinter().lint(dir));
        assertThat(findings).hasSize(2);
        assertThat(findings.get(0)).contains("export /items/ghost")
                .contains("targets no query-export GET route or file-export POST route");
        assertThat(findings.get(1)).contains("export /items/create")
                .contains("mounted there: POST command-json");
    }

    @Test
    void anExportRouteMustDeclareTheListsInputsWithTheirTypes(@TempDir Path dir)
            throws Exception {
        // status missing, q typed integer: each is a condition the kick-off would lose.
        writeExportApp(dir, EXPORTING_VIEW, "web/items/export", "get.yml", """
                version: tesseraql/v1
                id: items.export
                kind: route
                recipe: query-export
                input:
                  q: { type: integer, required: false }
                  sort:
                    type: sort
                    columns: [name, status]
                    default: name
                export:
                  format: csv
                sources:
                  main:
                    sql:
                      file: ../list.sql
                """);
        List<String> findings = exportFindings(new AppLinter().lint(dir));
        assertThat(findings).hasSize(2);
        assertThat(findings).anySatisfy(message -> assertThat(message)
                .contains("does not declare the list's input 'status'"));
        assertThat(findings).anySatisfy(message -> assertThat(message)
                .contains("declares 'q' as integer where the list declares string"));
    }

    @Test
    void anExportRoutesSortAllowlistMustAdmitTheListsColumns(@TempDir Path dir)
            throws Exception {
        writeExportApp(dir, EXPORTING_VIEW, "web/items/export", "get.yml", """
                version: tesseraql/v1
                id: items.export
                kind: route
                recipe: query-export
                input:
                  q: { type: string, required: false, maxLength: 100 }
                  status: { type: string, required: false, enum: [OPEN, CLOSED] }
                  sort:
                    type: sort
                    columns: [name]
                    default: name
                export:
                  format: csv
                sources:
                  main:
                    sql:
                      file: ../list.sql
                """);
        assertThat(exportFindings(new AppLinter().lint(dir))).singleElement().asString()
                .contains("sort allowlist lacks 'status', which the list's sort admits");
    }

    @Test
    void anExportRouteMayNotRequireWhatTheKickoffNeverSends(@TempDir Path dir)
            throws Exception {
        writeExportApp(dir, EXPORTING_VIEW, "web/items/export", "get.yml", """
                version: tesseraql/v1
                id: items.export
                kind: route
                recipe: query-export
                input:
                  q: { type: string, required: false, maxLength: 100 }
                  status: { type: string, required: false, enum: [OPEN, CLOSED] }
                  sort:
                    type: sort
                    columns: [name, status]
                    default: name
                  region: { type: string, required: true }
                export:
                  format: csv
                sources:
                  main:
                    sql:
                      file: ../list.sql
                """);
        assertThat(exportFindings(new AppLinter().lint(dir))).singleElement().asString()
                .contains("requires 'region', which the kick-off never sends");
    }

    @Test
    void anExportRouteMayNotDeclareAPathParameterTheListLacks(@TempDir Path dir)
            throws Exception {
        writeExportApp(dir, """
                version: tesseraql/v1
                kind: view
                recipe: list
                search: q
                filters: [status]
                exports: ["/items/{region}/export"]
                """, "web/items/{region}/export", "get.yml", """
                version: tesseraql/v1
                id: items.export
                kind: route
                recipe: query-export
                input:
                  q: { type: string, required: false, maxLength: 100 }
                  status: { type: string, required: false, enum: [OPEN, CLOSED] }
                  sort:
                    type: sort
                    columns: [name, status]
                    default: name
                  region: { type: string, required: false }
                export:
                  format: csv
                sources:
                  main:
                    sql:
                      file: ../../list.sql
                """);
        List<LintFinding> findings = new AppLinter().lint(dir);
        assertThat(exportFindings(findings)).as(String.valueOf(findings)).singleElement()
                .asString()
                .contains("declares path parameter {region}, which the list route's path does"
                        + " not");
    }

    @Test
    void aBulkActionTargetingARealPostRouteLintsClean(@TempDir Path dir) throws Exception {
        writeApp(dir, """
                version: tesseraql/v1
                kind: view
                recipe: list
                key: id
                actions:
                  - label: Create-ish
                    action: /items/create
                """);
        assertThat(viewCodes(new AppLinter().lint(dir))).isEmpty();
    }

    @Test
    void aDottedLinkPlaceholderIsAnError(@TempDir Path dir) throws Exception {
        // docs/list-surface.md decision 3: the runtime renders a dotted path but the ejector
        // rewrites placeholders per column — the divergence is refused at lint time.
        writeApp(dir, """
                version: tesseraql/v1
                kind: view
                recipe: list
                columns:
                  - name: name
                    link: /items/{row.id}
                """);
        assertThat(viewCodes(new AppLinter().lint(dir))).contains("TQL-VIEW-3321");
    }

    @Test
    void aMalformedLinkPlaceholderIsAnError(@TempDir Path dir) throws Exception {
        writeApp(dir, """
                version: tesseraql/v1
                kind: view
                recipe: list
                columns:
                  - name: name
                    link: /items/{}
                """);
        assertThat(viewCodes(new AppLinter().lint(dir))).contains("TQL-VIEW-3321");
    }

    @Test
    void aWellFormedFormViewProducesNoFindings(@TempDir Path dir) throws Exception {
        writeApp(dir, """
                version: tesseraql/v1
                kind: view
                recipe: form
                action: /items/create
                fields:
                  - name: name
                    widget: textarea
                """);
        assertThat(viewCodes(new AppLinter().lint(dir))).isEmpty();
    }

    @Test
    void viewAndTemplateTogetherAreAnError(@TempDir Path dir) throws Exception {
        writeApp(dir, "version: tesseraql/v1\nkind: view\nrecipe: list\n");
        Files.writeString(dir.resolve("web/items/index.html"), "<p>x</p>");
        Files.writeString(dir.resolve("web/items/get.yml"), """
                version: tesseraql/v1
                id: items.page
                kind: route
                recipe: query-html
                sources:
                  main:
                    sql:
                      file: list.sql
                response:
                  html:
                    template: index.html
                    view: items
                """);
        assertThat(viewCodes(new AppLinter().lint(dir))).contains("TQL-VIEW-3302");
    }

    @Test
    void anUnresolvedViewFileIsAnError(@TempDir Path dir) throws Exception {
        writeApp(dir, "version: tesseraql/v1\nkind: view\nrecipe: list\n");
        Files.delete(dir.resolve("web/items/items.view.yml"));
        assertThat(viewCodes(new AppLinter().lint(dir))).contains("TQL-VIEW-3302");
    }

    @Test
    void anInvalidViewDocumentIsAnError(@TempDir Path dir) throws Exception {
        writeApp(dir, "kind: view\nview: wizard\n");
        assertThat(viewCodes(new AppLinter().lint(dir))).contains("TQL-VIEW-3301");
    }

    @Test
    void aFormActionMatchingNoPostRouteIsAnError(@TempDir Path dir) throws Exception {
        writeApp(dir, "version: tesseraql/v1\nkind: view\nrecipe: form\naction: /nowhere\n");
        assertThat(viewCodes(new AppLinter().lint(dir))).contains("TQL-VIEW-3303");
    }

    @Test
    void aFieldTheActionDoesNotDeclareIsAnError(@TempDir Path dir) throws Exception {
        writeApp(dir, """
                version: tesseraql/v1
                kind: view
                recipe: form
                action: /items/create
                fields:
                  - name: ghost
                """);
        assertThat(viewCodes(new AppLinter().lint(dir))).contains("TQL-VIEW-3304");
    }

    @Test
    void anUnknownWidgetIsAnError(@TempDir Path dir) throws Exception {
        writeApp(dir, """
                version: tesseraql/v1
                kind: view
                recipe: form
                action: /items/create
                fields:
                  - name: name
                    widget: carousel
                """);
        assertThat(viewCodes(new AppLinter().lint(dir))).contains("TQL-VIEW-3305");
    }

    @Test
    void anUnknownSlotNameIsAnError(@TempDir Path dir) throws Exception {
        writeApp(dir, """
                version: tesseraql/v1
                kind: view
                recipe: list
                slots:
                  sidebar: frags.html::x
                """);
        assertThat(viewCodes(new AppLinter().lint(dir))).contains("TQL-VIEW-3306");
    }

    @Test
    void anUnresolvedSlotReferenceIsAnError(@TempDir Path dir) throws Exception {
        writeApp(dir, """
                version: tesseraql/v1
                kind: view
                recipe: list
                slots:
                  header: missing.html::x
                """);
        assertThat(viewCodes(new AppLinter().lint(dir))).contains("TQL-VIEW-3302");
    }

    @Test
    void aResolvedSlotIsClean(@TempDir Path dir) throws Exception {
        writeApp(dir, """
                version: tesseraql/v1
                kind: view
                recipe: list
                slots:
                  header: frags.html::newLink
                """);
        Files.createDirectories(dir.resolve("templates"));
        Files.writeString(dir.resolve("templates/frags.html"),
                "<a th:fragment=\"newLink\" href=\"/x\">x</a>");
        assertThat(viewCodes(new AppLinter().lint(dir))).isEmpty();
    }

    @Test
    void aChildSourceTheRouteDoesNotDeclareIsAnError(@TempDir Path dir) throws Exception {
        writeApp(dir, """
                version: tesseraql/v1
                kind: view
                recipe: detail
                children:
                  - source: ghost
                """);
        assertThat(viewCodes(new AppLinter().lint(dir))).contains("TQL-VIEW-3308");
    }

    /**
     * The document's own {@code source:} is a reference like a child's or a panel's — a typo
     * there used to be judged by nothing and rendered an empty page, COMPLETED
     * (docs/audit-low-leads.md slice 21, unfiled 22).
     */
    @Test
    void aViewsOwnSourceTheRouteDoesNotDeclareIsAnError(@TempDir Path dir) throws Exception {
        writeApp(dir, """
                version: tesseraql/v1
                kind: view
                recipe: list
                source: typo
                """);
        assertThat(new AppLinter().lint(dir))
                .filteredOn(f -> f.code().equals("TQL-VIEW-3308"))
                .singleElement()
                .matches(f -> f.message().contains("view items: source typo"));
    }

    /**
     * An embedded view reads the host route's sources (docs/view-composition.md wave 2b), and
     * the build judges them there — the lint used to judge the {@code view:} document alone,
     * so a wrong panel source inside an embedded document was the boot's refusal with no lint
     * finding before it (docs/audit-low-leads.md slice 21, unfiled 21).
     */
    @Test
    void anEmbeddedViewsSourceIsJudgedAgainstTheHostRoute(@TempDir Path dir) throws Exception {
        writeApp(dir, """
                version: tesseraql/v1
                kind: view
                recipe: dashboard
                panels:
                  - { type: view, view: items.recent }
                """);
        Files.writeString(dir.resolve("web/items/recent.view.yml"), """
                version: tesseraql/v1
                id: items.recent
                kind: view
                recipe: dashboard
                panels:
                  - { type: stat, source: ghost, column: n }
                """);
        assertThat(new AppLinter().lint(dir))
                .filteredOn(f -> f.code().equals("TQL-VIEW-3308"))
                .singleElement()
                .matches(f -> f.message().contains("view items.recent: panel source ghost")
                        && f.source().equals("web/items/get.yml"));
    }

    /**
     * The host entry's {@code source:} is what the embedded model reads through, so it stands
     * in for the embedded document's own: an embedded document naming a source only its hosts
     * know is clean when every host overrides it, and the override is the name judged.
     */
    @Test
    void aHostEntrysSourceOverrideStandsInForTheEmbeddedDocumentsOwn(@TempDir Path dir)
            throws Exception {
        writeApp(dir, """
                version: tesseraql/v1
                kind: view
                recipe: dashboard
                panels:
                  - { type: view, view: items.recent, source: main }
                """);
        Files.writeString(dir.resolve("web/items/recent.view.yml"), """
                version: tesseraql/v1
                id: items.recent
                kind: view
                recipe: list
                source: whateverTheHostSays
                """);
        assertThat(viewCodes(new AppLinter().lint(dir))).isEmpty();

        // Without the override the embedded document's own name is the one judged.
        Files.writeString(dir.resolve("web/items/items.view.yml"), """
                version: tesseraql/v1
                kind: view
                recipe: dashboard
                panels:
                  - { type: view, view: items.recent }
                """);
        assertThat(new AppLinter().lint(dir))
                .filteredOn(f -> f.code().equals("TQL-VIEW-3308"))
                .singleElement()
                .matches(
                        f -> f.message().contains("view items.recent: source whateverTheHostSays"));
    }

    /**
     * A {@code views:} part reads the template route's sources exactly as a {@code view:}
     * document does (RouteCompiler binds both with the route), so it is judged alike — the
     * lint used to resolve the id and stop.
     */
    @Test
    void aViewsBoundPartsSourceIsJudgedAgainstTheTemplateRoute(@TempDir Path dir)
            throws Exception {
        Files.createDirectories(dir.resolve("web/report"));
        Files.createDirectories(dir.resolve("templates"));
        Files.writeString(dir.resolve("templates/report.html"), "<div>report</div>\n");
        Files.writeString(dir.resolve("web/report/get.yml"), """
                version: tesseraql/v1
                id: report.page
                kind: route
                recipe: query-html
                response:
                  html:
                    template: report.html
                    views: [report.part]
                """);
        Files.writeString(dir.resolve("web/report/part.view.yml"), """
                version: tesseraql/v1
                id: report.part
                kind: view
                recipe: dashboard
                panels:
                  - { type: stat, source: ghost, column: n }
                """);
        assertThat(new AppLinter().lint(dir))
                .filteredOn(f -> f.code().equals("TQL-VIEW-3308"))
                .singleElement()
                .matches(f -> f.message().contains("view report.part: panel source ghost")
                        && f.source().equals("web/report/get.yml"));
    }

    @Test
    void aColumnDomainReferenceIsCheckedPerDocument(@TempDir Path dir) throws Exception {
        // docs/view-composition.md wave 3a: explicit read-side domain links must resolve.
        writeApp(dir, """
                version: tesseraql/v1
                kind: view
                recipe: list
                columns:
                  - { name: sku, domain: ghost }
                """);
        java.util.List<String> codes = new AppLinter().lint(dir).stream()
                .map(LintFinding::code).toList();
        assertThat(codes).contains("TQL-FIELD-4601");

        Files.createDirectories(dir.resolve("domains"));
        Files.writeString(dir.resolve("domains/catalog.yml"), """
                version: tesseraql/v1
                domains:
                  ghost:
                    type: string
                """);
        assertThat(viewCodes(new AppLinter().lint(dir))).isEmpty();
    }

    @Test
    void aChildSourceNamingAnHttpSourceIsClean(@TempDir Path dir) throws Exception {
        // docs/connectors.md http sources publish the same {rows} shape as a named query,
        // so a child/panel source: may name one (docs/view-composition.md wave 0).
        Files.createDirectories(dir.resolve("web/items"));
        Files.writeString(dir.resolve("web/items/list.sql"), "select id, name from items\n");
        Files.writeString(dir.resolve("web/items/get.yml"), """
                version: tesseraql/v1
                id: items.page
                kind: route
                recipe: query-html
                sources:
                  main:
                    sql:
                      file: list.sql
                  rates:
                    http:
                      url: ${tesseraql.connectors.fx.baseUrl}/v1/rates
                response:
                  html:
                    view: items
                """);
        Files.writeString(dir.resolve("web/items/items.view.yml"), """
                version: tesseraql/v1
                kind: view
                recipe: detail
                children:
                  - source: rates
                """);
        assertThat(viewCodes(new AppLinter().lint(dir))).isEmpty();
    }

    @Test
    void anOverrideWithoutTheFragmentSignatureIsAWarning(@TempDir Path dir) throws Exception {
        writeApp(dir, "version: tesseraql/v1\nkind: view\nrecipe: list\n");
        Files.createDirectories(dir.resolve("templates/tql/view"));
        Files.writeString(dir.resolve("templates/tql/view/form.html"), "<form></form>");
        List<LintFinding> findings = new AppLinter().lint(dir);
        assertThat(viewCodes(findings)).contains("TQL-VIEW-3307");
        assertThat(findings.stream().filter(f -> f.code().equals("TQL-VIEW-3307")).findFirst()
                .orElseThrow().severity()).isEqualTo("warning");
    }

    @Test
    void anOverrideWithTheFragmentSignatureIsClean(@TempDir Path dir) throws Exception {
        writeApp(dir, "version: tesseraql/v1\nkind: view\nrecipe: list\n");
        Files.createDirectories(dir.resolve("templates/tql/view"));
        Files.writeString(dir.resolve("templates/tql/view/form.html"),
                "<form th:fragment=\"view(v)\"></form>");
        Files.writeString(dir.resolve("templates/tql/view/field-date.html"),
                "<div th:fragment=\"field(f)\"></div>");
        assertThat(viewCodes(new AppLinter().lint(dir))).isEmpty();
    }

    @Test
    void aComposedPatternIsHeldToItsOwnSignature(@TempDir Path dir) throws Exception {
        // The patterns a view composes each declare their own fragment; holding an override of
        // one of those to view(v) reported a break that was not there.
        writeApp(dir, "version: tesseraql/v1\nkind: view\nrecipe: list\n");
        Files.createDirectories(dir.resolve("templates/tql/view"));
        Files.writeString(dir.resolve("templates/tql/view/report.html"),
                "<div th:fragment=\"report(r)\"></div>");
        Files.writeString(dir.resolve("templates/tql/view/table.html"),
                "<div th:fragment=\"table(tableId, columns, rows)\"></div>");
        Files.writeString(dir.resolve("templates/tql/view/lookup-dialog.html"),
                "<div th:fragment=\"dialog(d)\"></div>");
        assertThat(viewCodes(new AppLinter().lint(dir))).isEmpty();

        Files.writeString(dir.resolve("templates/tql/view/report.html"),
                "<div th:fragment=\"view(v)\"></div>");
        assertThat(viewCodes(new AppLinter().lint(dir))).contains("TQL-VIEW-3307");
    }
}
