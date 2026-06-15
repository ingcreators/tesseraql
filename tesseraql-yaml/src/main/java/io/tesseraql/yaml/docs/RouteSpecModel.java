package io.tesseraql.yaml.docs;

import java.util.List;

/**
 * The deterministic spec-layer documentation model derived from an {@code AppManifest}
 * (documentation portal v1): every route/page projected to a {@link RouteSpec}, plus the listing of
 * the application's Flyway migrations. This is the {@code tesseraql-yaml} half of the portal model;
 * {@code tesseraql-report}'s {@code AppDocGenerator} wraps it to attach test cross-references and
 * serialize the full {@code spec.json}.
 *
 * @param routes     the route/page specs, ordered by path then method
 * @param migrations the migration listing, in {@code MigrationFile} natural order
 */
public record RouteSpecModel(List<RouteSpec> routes, List<Migration> migrations) {

    public RouteSpecModel {
        routes = List.copyOf(routes);
        migrations = List.copyOf(migrations);
    }

    /**
     * A listed migration (spec layer; no DDL parsing), with its path made app-home relative so the
     * model stays byte-stable across machines.
     *
     * @param datasource  the datasource the migration runs against ({@code main} for {@code db/migration})
     * @param vendor      the vendor overlay this file belongs to, or {@code null} for the common set
     * @param version     the Flyway version, or {@code null} for a repeatable migration
     * @param description the migration description
     * @param path        the app-home-relative source path, using {@code /} separators
     */
    public record Migration(String datasource, String vendor, String version, String description,
            String path) {
    }
}
