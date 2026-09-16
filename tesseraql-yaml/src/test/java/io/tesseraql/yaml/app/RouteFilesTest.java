package io.tesseraql.yaml.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.manifest.ManifestLoader;
import io.tesseraql.yaml.model.RouteDefinition;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The one resolver every altitude reads a document's files through (docs/audit-low-leads.md
 * slice 14): a reference resolves against the declaring directory and must land inside the
 * application home — {@code ../shared/} inside it is legal, {@code ../../outside/} is not,
 * whether the file is there or not, and an absolute path outside is the same refusal. A
 * statement must be there (its dialect variant counts); a page template beside the document
 * or under {@code templates/}.
 */
class RouteFilesTest {

    private static final String HEAD = RouteFiles.head("shop", "route 'orders'",
            "sources.main.file");

    @Test
    void aReferenceInsideTheHomeResolvesWhereverItSits(@TempDir Path home) throws Exception {
        Path route = Files.createDirectories(home.resolve("web/orders/detail"));
        Files.createDirectories(home.resolve("shared"));
        Files.writeString(home.resolve("shared/order.sql"), "select 1\n");
        Files.writeString(home.resolve("web/orders/order.sql"), "select 1\n");

        assertThat(RouteFiles.resolve(home, route, "../order.sql", HEAD))
                .isEqualTo(home.resolve("web/orders/order.sql").toAbsolutePath().normalize());
        assertThat(RouteFiles.resolve(home, route, "../../../shared/order.sql", HEAD))
                .isEqualTo(home.resolve("shared/order.sql").toAbsolutePath().normalize());
        assertThat(RouteFiles.resolve(home, route, "q/rows.sql", HEAD))
                .as("a subdirectory, whether or not the file is there")
                .isEqualTo(route.resolve("q/rows.sql").toAbsolutePath().normalize());
    }

    @Test
    void aReferenceOutsideTheHomeIsRefusedNamingTheKeyAndTheValue(@TempDir Path root)
            throws Exception {
        Path home = Files.createDirectories(root.resolve("app"));
        Path route = Files.createDirectories(home.resolve("web/orders"));
        Files.writeString(root.resolve("outside.sql"), "select 1\n");

        assertThatThrownBy(() -> RouteFiles.resolve(home, route, "../../../outside.sql", HEAD))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-YAML-1075")
                .hasMessageContaining("app 'shop': route 'orders' sources.main.file:")
                .hasMessageContaining("'../../../outside.sql' resolves outside the application"
                        + " home");
        assertThatThrownBy(() -> RouteFiles.resolve(home, route, "../../../nowhere.sql", HEAD))
                .as("whether or not the file is there")
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-YAML-1075");
        assertThatThrownBy(() -> RouteFiles.resolve(home, route,
                root.resolve("outside.sql").toString(), HEAD))
                .as("an absolute path outside the home")
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-YAML-1075")
                .hasMessageContaining("resolves outside the application home");
        // A path the filesystem cannot express is refused the way an escape is, not as a
        // raw InvalidPathException.
        assertThatThrownBy(() -> RouteFiles.resolve(home, route, "bad\0name.sql", HEAD))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-YAML-1075")
                .hasMessageContaining("is not a file path");
    }

    @Test
    void aStatementMustBeThereAndItsDialectVariantCounts(@TempDir Path home) throws Exception {
        Path route = Files.createDirectories(home.resolve("web/orders"));
        Files.writeString(route.resolve("list.postgres.sql"), "select 1\n");

        assertThatThrownBy(() -> RouteFiles.sql(home, route, "list.sql", null, HEAD))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-SQL-2103")
                .hasMessageContaining(HEAD + "referenced SQL file is missing: list.sql");
        assertThat(RouteFiles.sql(home, route, "list.sql", "postgres", HEAD))
                .as("the variant satisfies the check; the file as declared is returned")
                .isEqualTo(route.resolve("list.sql").toAbsolutePath().normalize());
        Files.writeString(route.resolve("list.sql"), "select 1\n");
        assertThat(RouteFiles.sql(home, route, "list.sql", null, HEAD))
                .isEqualTo(route.resolve("list.sql").toAbsolutePath().normalize());
    }

    @Test
    void aPageTemplateIsBesideTheDocumentOrUnderTemplates(@TempDir Path home) throws Exception {
        Path route = Files.createDirectories(home.resolve("web/orders"));
        Files.createDirectories(home.resolve("templates"));
        Files.writeString(home.resolve("templates/layout.html"), "<p>shared</p>\n");
        String head = RouteFiles.head("shop", "route 'orders'", "response.html.template");

        assertThat(RouteFiles.page(home, route, "layout.html", head))
                .as("the shared templates/ root, when nothing is beside the document")
                .isEqualTo(home.resolve("templates/layout.html").toAbsolutePath().normalize());
        Files.writeString(route.resolve("layout.html"), "<p>own</p>\n");
        assertThat(RouteFiles.page(home, route, "layout.html", head))
                .as("beside the document first")
                .isEqualTo(route.resolve("layout.html").toAbsolutePath().normalize());
        assertThatThrownBy(() -> RouteFiles.page(home, route, "nowhere.html", head))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-TPL-2001")
                .hasMessageContaining(head + "'nowhere.html' resolves to no file beside the"
                        + " document or under templates/");
        assertThatThrownBy(() -> RouteFiles.page(home, route, "../../../etc/passwd", head))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-YAML-1075");
    }

    @Test
    void theReferencesOfADocumentAreEveryFileItNames(@TempDir Path home) throws Exception {
        Files.createDirectories(home.resolve("config"));
        Files.writeString(home.resolve("config/application.yml"), """
                tesseraql:
                  app:
                    name: shop
                """);
        Path route = Files.createDirectories(home.resolve("web/orders"));
        Files.writeString(route.resolve("post.yml"), """
                version: tesseraql/v1
                id: orders
                kind: route
                recipe: file-export
                security:
                  auth: public
                sources:
                  main:
                    sql:
                      file: q/rows.sql
                    enrich:
                      names:
                        on: { id: id }
                        sql:
                          file: ../shared/names.sql
                  totals:
                    sql:
                      file: totals.sql
                validate:
                  inStock:
                    file: rules/in-stock.sql
                    field: id
                export:
                  format: excel
                  template: tpl/report.xlsx
                  after:
                    sql:
                      file: mark.sql
                steps:
                  - id: audit
                    sql:
                      file: audit.sql
                      mode: update
                """);
        RouteDefinition definition = new ManifestLoader().load(home).routes().get(0)
                .definition();

        assertThat(RouteFiles.references(definition)).extracting(RouteFiles.Reference::key,
                RouteFiles.Reference::declared, RouteFiles.Reference::kind).containsExactly(
                        org.assertj.core.groups.Tuple.tuple("sources.main.file", "q/rows.sql",
                                RouteFiles.Kind.SQL),
                        org.assertj.core.groups.Tuple.tuple("sources.main.enrich.names.sql.file",
                                "../shared/names.sql", RouteFiles.Kind.SQL),
                        org.assertj.core.groups.Tuple.tuple("sources.totals.file", "totals.sql",
                                RouteFiles.Kind.SQL),
                        org.assertj.core.groups.Tuple.tuple("steps.audit.file", "audit.sql",
                                RouteFiles.Kind.SQL),
                        org.assertj.core.groups.Tuple.tuple("validate.inStock.file",
                                "rules/in-stock.sql", RouteFiles.Kind.SQL),
                        org.assertj.core.groups.Tuple.tuple("export.template", "tpl/report.xlsx",
                                RouteFiles.Kind.EXPORT_TEMPLATE),
                        org.assertj.core.groups.Tuple.tuple("export.after.sql.file", "mark.sql",
                                RouteFiles.Kind.SQL));
    }
}
