package io.tesseraql.yaml.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.model.InputField;
import io.tesseraql.yaml.model.RouteDefinition;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The manifest loader resolves a {@code result:} entry's {@code domain:} the way it resolves an
 * input's (docs/temporal-semantics.md T3): resolution is compile-time, so the compiled artifact
 * carries a value only the domain has — a route-local match cannot make the runtime green for
 * the wrong reason.
 */
class ResultDomainResolutionTest {

    private Path app(@TempDir Path dir, String entry) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("domains"));
        Files.writeString(dir.resolve("domains/catalog.yml"), """
                version: tesseraql/v1
                domains:
                  order_date:
                    type: date
                    format: yyyy/MM/dd
                    locale: de-DE
                    description: the day the order was placed
                    maxLength: 10
                    pattern: "[0-9/]+"
                """);
        Files.createDirectories(dir.resolve("web/orders"));
        Files.writeString(dir.resolve("web/orders/get.yml"), """
                version: tesseraql/v1
                id: orders.list
                kind: route
                recipe: query-json
                security:
                  auth: public
                sources:
                  main:
                    sql:
                      file: orders.sql
                    result:
                      ordered_on: %s
                      amount: { type: number, format: "#,##0.00" }
                response:
                  json:
                    body:
                      data: main.rows
                """.formatted(entry));
        Files.writeString(dir.resolve("web/orders/orders.sql"),
                "select ordered_on, amount from orders\n");
        Files.createDirectories(dir.resolve("web/orders/mark"));
        Files.writeString(dir.resolve("web/orders/mark/post.yml"), """
                version: tesseraql/v1
                id: orders.mark
                kind: route
                recipe: command-json
                security:
                  auth: public
                steps:
                  - id: read
                    sql:
                      file: read.sql
                      mode: query
                    result:
                      ordered_on: { domain: order_date }
                  - id: write
                    sql:
                      file: write.sql
                response:
                  json:
                    body:
                      ok: "true"
                """);
        Files.writeString(dir.resolve("web/orders/mark/read.sql"),
                "select ordered_on from orders\n");
        Files.writeString(dir.resolve("web/orders/mark/write.sql"),
                "update orders set marked = 1\n");
        return dir;
    }

    @Test
    void theDomainsKeysMergeUnderAResultEntryOnASourceAndOnAStep(@TempDir Path dir)
            throws Exception {
        AppManifest manifest = new ManifestLoader().load(app(dir, "{ domain: order_date }"));

        RouteDefinition list = manifest.routes().stream()
                .filter(r -> r.definition().id().equals("orders.list")).findFirst()
                .orElseThrow().definition();
        InputField orderedOn = list.sources().get("main").result().get("ordered_on");
        assertThat(orderedOn.type()).isEqualTo("date");
        // A value only the domain has: the compiled declaration carries it.
        assertThat(orderedOn.format()).isEqualTo("yyyy/MM/dd");
        assertThat(orderedOn.description()).isEqualTo("the day the order was placed");
        assertThat(orderedOn.locale()).isEqualTo("de-DE");
        assertThat(orderedOn.domain()).isEqualTo("order_date");
        // By the read keys alone (decision 24): the domain's constraint keys do not reach a
        // result: entry, which is what makes one written on the entry refusable exactly.
        assertThat(orderedOn.maxLength()).isNull();
        assertThat(orderedOn.pattern()).isNull();
        // A route-local entry is untouched.
        assertThat(list.sources().get("main").result().get("amount").format())
                .isEqualTo("#,##0.00");

        RouteDefinition mark = manifest.routes().stream()
                .filter(r -> r.definition().id().equals("orders.mark")).findFirst()
                .orElseThrow().definition();
        assertThat(mark.steps().get("read").result().get("ordered_on").format())
                .isEqualTo("yyyy/MM/dd");
        assertThat(mark.steps().get("write").result()).isEmpty();
    }

    @Test
    void aRestatedFormatWinsOverTheDomains(@TempDir Path dir) throws Exception {
        AppManifest manifest = new ManifestLoader().load(
                app(dir, "{ domain: order_date, format: yyyyMMdd }"));

        InputField orderedOn = manifest.routes().stream()
                .filter(r -> r.definition().id().equals("orders.list")).findFirst()
                .orElseThrow().definition().sources().get("main").result().get("ordered_on");
        assertThat(orderedOn.type()).isEqualTo("date");
        assertThat(orderedOn.format()).isEqualTo("yyyyMMdd");
    }

    @Test
    void anUnknownDomainOnAResultEntryFailsTheLoad(@TempDir Path dir) throws Exception {
        Path app = app(dir, "{ domain: order_dat }");

        assertThatThrownBy(() -> new ManifestLoader().load(app))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("unknown domain 'order_dat'");
    }
}
