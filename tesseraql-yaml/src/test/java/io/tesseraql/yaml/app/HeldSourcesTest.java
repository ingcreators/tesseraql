package io.tesseraql.yaml.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.core.cache.HoldSpec;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestLoader;
import io.tesseraql.yaml.model.Binding;
import io.tesseraql.yaml.model.EnrichSpec;
import io.tesseraql.yaml.model.ResultCacheSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the held-source predicate hands the runtime (docs/caching.md decisions 2 and 5): the
 * tables every held source reads, in declaration order, and the spec a judged declaration
 * compiles to — a single-value {@code tables:} included.
 */
class HeldSourcesTest {

    private static AppManifest app(Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: t
                """);
        Path orders = Files.createDirectories(dir.resolve("web/orders"));
        Files.writeString(orders.resolve("orders.sql"), "select 1 as id\n");
        Files.writeString(orders.resolve("partners.sql"),
                "select code, name from partners where code in /* keys */(1)\n");
        Files.writeString(orders.resolve("get.yml"), """
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
                      datasource: crm
                    cache:
                      maxAge: 2m
                      tables: [orders, customers]
                  totals:
                    sql:
                      file: orders.sql
                    cache:
                      maxAge: 30s
                      tables: regions
                  plain:
                    sql:
                      file: orders.sql
                    enrich:
                      partner:
                        on: { id: code }
                        sql:
                          file: partners.sql
                        merge: [name]
                        cache:
                          maxAge: 1m
                          tables: [partners]
                response:
                  json:
                    body:
                      rows: main.rows
                """);
        return new ManifestLoader().load(dir);
    }

    /** docs/caching.md decision 9: a reference's hold, per arm. */
    @Test
    void aReferencesHoldCompilesPerArm() {
        Map<String, String> on = Map.of("partner_code", "code");
        ResultCacheSpec cache = new ResultCacheSpec("2m", List.of("partners"));
        EnrichSpec sql = new EnrichSpec(on, Binding.SqlArm.of("partners.sql"), null, null,
                null, null, List.of("name"), null, null, cache);
        HoldSpec spec = HeldSources.enrichSpec("orders.list", "main", "partner", sql, "crm");
        assertThat(spec.owner()).isEqualTo("orders.list");
        assertThat(spec.source()).isEqualTo("main.enrich.partner");
        assertThat(spec.datasource()).isEqualTo("crm");
        assertThat(spec.maxAgeMillis()).isEqualTo(120_000L);
        assertThat(spec.tables()).containsExactly("partners");

        io.tesseraql.yaml.model.HttpCallSpec call = new io.tesseraql.yaml.model.HttpCallSpec(
                "GET", "http://p/{key.code}", null, null, null, null, null, null, null, null);
        EnrichSpec http = new EnrichSpec(on, null, new io.tesseraql.yaml.model.HttpSourceSpec(
                call, null, null, null, null), null, null, null, List.of("name"), null, null,
                new ResultCacheSpec("30s", List.of()));
        HoldSpec held = HeldSources.enrichSpec("orders.list", "main", "partner", http, "main");
        assertThat(held.datasource()).as("nothing stamps a partner system").isEqualTo("http");
        assertThat(held.tables()).isEmpty();

        EnrichSpec sibling = new EnrichSpec(on, null, null, "partners", null, "lines", null,
                null, null, cache);
        assertThat(HeldSources.enrichSpec("orders.list", "main", "partner", sibling, "main"))
                .isNull();
        assertThat(HeldSources.enrichSpec("orders.list", "main", "partner",
                new EnrichSpec(on, Binding.SqlArm.of("partners.sql"), null, null, null, null,
                        List.of("name"), null, null),
                "main")).isNull();
    }

    @Test
    void theHeldTablesAreEveryHeldSourcesInDeclarationOrder(@TempDir Path dir)
            throws Exception {
        AppManifest manifest = app(dir);
        assertThat(HeldSources.tables(manifest)).containsExactly("orders", "customers",
                "regions", "partners");
        assertThat(HeldSources.any(manifest)).isTrue();
        assertThat(HeldSources.tables(manifest.routes().get(0).definition()))
                .containsExactly("orders", "customers", "regions", "partners");
    }

    @Test
    void aJudgedDeclarationCompilesToItsSpecAndAPlainSourceToNone(@TempDir Path dir)
            throws Exception {
        AppManifest manifest = app(dir);
        Binding main = manifest.routes().get(0).definition().sources().get("main");
        HoldSpec spec = HeldSources.spec("orders.list", "main", main, main.datasource());
        assertThat(spec.owner()).isEqualTo("orders.list");
        assertThat(spec.source()).isEqualTo("main");
        assertThat(spec.datasource()).isEqualTo("crm");
        assertThat(spec.maxAgeMillis()).isEqualTo(120_000L);
        assertThat(spec.tables()).containsExactly("orders", "customers");
        assertThat(spec.label()).isEqualTo("orders.list/main");

        Binding totals = manifest.routes().get(0).definition().sources().get("totals");
        assertThat(HeldSources.spec("orders.list", "totals", totals, "main").tables())
                .as("a single-value tables: is a list of one").containsExactly("regions");

        Binding plain = manifest.routes().get(0).definition().sources().get("plain");
        assertThat(HeldSources.spec("orders.list", "plain", plain, "main")).isNull();
        assertThat(HeldSources.violations("t", manifest.routes().get(0).definition(),
                RecipeShape.Surface.ROUTE)).isEmpty();
    }
}
