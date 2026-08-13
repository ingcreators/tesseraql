package io.tesseraql.runtime;

import static io.tesseraql.runtime.TesseraqlRuntime.renderRoutesPdf;

import io.tesseraql.yaml.manifest.AppManifest;
import java.nio.file.Path;
import java.util.Map;

/**
 * The service providers backing the bundled documentation portal (documentation portal
 * v1/v2/v3), extracted verbatim from the runtime boot. Registration order and lambda bodies are
 * exactly what {@code TesseraqlRuntime.start(...)} inlined before the extraction — this class
 * only relocates them.
 */
final class DocsProviders {

    private DocsProviders() {
    }

    /**
     * The boot-time state the docs provider lambdas capture: each component is the
     * effectively-final local {@code TesseraqlRuntime.start(...)} built, captured by value
     * exactly as the inline lambdas did.
     */
    record Deps(AppManifest manifest, Path appHome, StudioAccess studioAccess) {
    }

    /** Registers every {@code docs.*} provider on {@code serviceProviders}, in boot order. */
    static void register(io.tesseraql.core.service.ServiceProviders serviceProviders, Deps deps) {
        AppManifest manifest = deps.manifest();
        Path appHome = deps.appHome();
        StudioAccess studioAccess = deps.studioAccess();
        // Providers backing the bundled documentation portal (documentation portal v1/v2/v3):
        // they read the packaged spec.json, falling back to a live model from the manifest,
        // and overlay the optional run report.json (test results + coverage) and schema.json
        // (introspected table definitions) when present.
        io.tesseraql.studio.DocService doc = new io.tesseraql.studio.DocService(manifest);
        // Opt-in signed share links (F8, slice 3): off unless a signing secret is set.
        ShareLinks shareLinks = ShareLinks.from(manifest.config());
        serviceProviders
                .register("docs.index", params -> io.tesseraql.studio.DocViews.index(
                        doc.appName(), doc.spec(), doc.report(),
                        params.get("sort") == null
                                ? null
                                : String.valueOf(params.get("sort")),
                        params.get("dir") == null
                                ? null
                                : String.valueOf(params.get("dir"))))
                .register("docs.route", params -> {
                    String id = String.valueOf(params.get("id"));
                    io.tesseraql.studio.DocService.RouteEntry entry = doc.route(id);
                    if (entry == null) {
                        return Map.of("notFound", true, "id", id);
                    }
                    io.tesseraql.studio.ReportOverlay overlay = doc.report();
                    Map<String, Object> model = io.tesseraql.studio.DocViews.route(entry,
                            overlay == null ? null : overlay.routeReport(id),
                            doc.tableLinks());
                    // Offer a signed, expiring share link when sharing is configured.
                    if (shareLinks.enabled()) {
                        model.put("shareUrl", shareLinks.mintRoute(id));
                    }
                    return model;
                })
                .register("docs.search", params -> {
                    Object query = params.get("q");
                    String q = query == null ? "" : String.valueOf(query);
                    return io.tesseraql.studio.DocViews.searchResults(q, doc.search(q));
                })
                .register("docs.coverage", params -> {
                    io.tesseraql.studio.ReportOverlay overlay = doc.report();
                    Map<String, Object> model = io.tesseraql.studio.DocViews
                            .coverage(doc.appName(), overlay, doc.history());
                    // A corrupt overlay or history reads as "nothing recorded yet"; the
                    // page names the unreadable file instead (silent-tolerance T6).
                    model.put("reportCorrupt", doc.reportCorrupt());
                    model.put("historyCorrupt", doc.historyCorrupt());
                    // Offer a signed share link for the dashboard when sharing is configured
                    // and there is a run report to share (F8 slice 3, extended).
                    if (shareLinks.enabled() && overlay != null) {
                        model.put("shareUrl", shareLinks.mintCoverage());
                    }
                    return model;
                })
                .register("docs.schema", params -> {
                    Map<String, Object> model = io.tesseraql.studio.DocViews.schema(
                            doc.appName(), doc.schema(),
                            params.get("sort") == null
                                    ? null
                                    : String.valueOf(params.get("sort")),
                            params.get("dir") == null
                                    ? null
                                    : String.valueOf(params.get("dir")));
                    // The refresh action's gate and the honest empty state
                    // (docs/studio-schema-lifecycle.md).
                    model.put("editable", studioAccess.canEdit(params.get("roles")));
                    model.put("schemaCorrupt", doc.schemaCorrupt());
                    return model;
                })
                .register("docs.table", params -> {
                    String ds = String.valueOf(params.get("ds"));
                    String name = String.valueOf(params.get("name"));
                    io.tesseraql.yaml.scaffold.CatalogSchema.Table table = doc.table(ds,
                            name);
                    if (table == null) {
                        return Map.of("notFound", true, "name", name, "datasource", ds);
                    }
                    Map<String, Object> model = io.tesseraql.studio.DocViews.table(ds,
                            table, doc.routesForTable(name), doc.domainsForTable(name),
                            doc.decisionsForTable(name));
                    if (shareLinks.enabled()) {
                        model.put("shareUrl", shareLinks.mintTable(ds, name));
                    }
                    return model;
                })
                // Field domains reference (docs/field-domains.md): every declared
                // domain, its constraint chips, and the routes referencing it.
                .register("docs.domains", params -> io.tesseraql.studio.DocViews.domains(
                        doc.appName(), doc.domains(), doc.constraintCatalog()))
                // Shared validation rules (docs/validation-rule-sets.md): the same page
                // shape applied to rules/, so "which routes share this rule" — the reason
                // to declare one once — is answerable from the portal.
                .register("docs.rules", params -> io.tesseraql.studio.DocViews.rules(
                        doc.appName(), doc.rules()))
                // Shared decision tables (docs/decision-tables.md): the same page shape
                // applied to decisions/, so "which operations consult this decision" is
                // answerable from the portal — workflow transitions included.
                .register("docs.decisions",
                        params -> io.tesseraql.studio.DocViews.decisions(doc.appName(),
                                doc.decisions()))
                // Export/share (documentation portal F8): the OpenAPI document and the htmx
                // contract, generated live from the manifest by the canonical generators and
                // streamed as downloadable JSON (the download routes' response.file emits the
                // provider's raw JSON string verbatim).
                .register("docs.export", params -> io.tesseraql.studio.DocViews
                        .export(doc.appName(), doc.apiChangelog(),
                                doc.apiChangelogCorrupt()))
                // The release-diff page (roadmap Phase 46): one view consolidating
                // what a promotion changes from the captured baselines — the API
                // changelog (openapi.baseline.json), the schema DDL delta
                // (schema.baseline.json), and the app's full migration list. The
                // two-tree diff (routes/policies) is the CLI/Maven report; this page
                // shows what the running app's baselines can prove.
                .register("docs.releaseDiff", params -> {
                    Map<String, Object> model = new java.util.LinkedHashMap<>();
                    model.put("appName", doc.appName());
                    // The capture action's gate (docs/studio-schema-lifecycle.md).
                    model.put("editable", studioAccess.canEdit(params.get("roles")));
                    model.put("hasApiBaseline", doc.hasApiBaseline());
                    io.tesseraql.studio.DocViews.applyApiChangelog(model,
                            doc.apiChangelog());
                    // The same split the migration page got: an unreadable baseline is
                    // not an absent one, and not "no changes" (silent-tolerance T6).
                    model.put("apiBaselineCorrupt", doc.apiChangelogCorrupt());
                    model.put("schemaBaselineCorrupt", doc.schemaBaselineCorrupt());
                    model.put("hasSchemaBaseline", doc.hasSchemaBaseline());
                    String ddl = doc.hasSchemaBaseline() && !doc.schemaBaselineCorrupt()
                            ? doc.schemaDiffDdl()
                            : null;
                    model.put("schemaDiff",
                            ddl == null || ddl.isBlank() ? null : ddl);
                    // Line rows for the hc-code diff renderer (studio-ux-refresh
                    // slice 4): additive DDL = added, -- review comments = removed.
                    model.put("schemaDiffLines",
                            io.tesseraql.studio.DocViews.schemaDiffLines(ddl));
                    java.util.List<Map<String, Object>> migrations = new java.util.ArrayList<>();
                    for (io.tesseraql.yaml.manifest.MigrationFile migration : manifest
                            .migrations()) {
                        Map<String, Object> row = new java.util.LinkedHashMap<>();
                        row.put("datasource", migration.datasource());
                        row.put("vendor", migration.vendor());
                        row.put("file", String.valueOf(
                                migration.path().getFileName()));
                        migrations.add(row);
                    }
                    model.put("migrations", migrations);
                    return model;
                })
                .register("docs.openapi", params -> doc.openApiJson())
                .register("docs.htmx", params -> doc.htmxContractJson())
                // Printable route catalog (F8, slice 2): render the route rows to a PDF
                // table through the canonical PDF codec, shown as a data: URL (degrades to a
                // note when the optional tesseraql-pdf module is absent, like the editor).
                .register("docs.routesPdf", params -> {
                    byte[] pdf = renderRoutesPdf(doc.routeCatalog(), appHome);
                    return io.tesseraql.studio.DocViews.routesPdf(doc.appName(),
                            pdf == null
                                    ? null
                                    : "data:application/pdf;base64,"
                                            + java.util.Base64.getEncoder()
                                                    .encodeToString(pdf));
                })
                // Public share view (F8, slice 3): the unauthenticated route this provider
                // backs verifies the signed, expiring token before rendering a reduced
                // read-only contract (no SQL / tests / coverage). An invalid, tampered, or
                // expired token — or sharing disabled — renders the invalid-link notice, so
                // nothing leaks. The route overlay is deliberately not consulted here.
                .register("docs.share", params -> {
                    String id = params.get("id") == null
                            ? null
                            : String.valueOf(params.get("id"));
                    String exp = params.get("exp") == null
                            ? null
                            : String.valueOf(params.get("exp"));
                    String sig = params.get("sig") == null
                            ? null
                            : String.valueOf(params.get("sig"));
                    io.tesseraql.studio.DocService.RouteEntry entry = shareLinks
                            .verifyRoute(id, exp, sig) ? doc.route(id) : null;
                    if (entry == null) {
                        return Map.of("shared", true, "shareInvalid", true);
                    }
                    return io.tesseraql.studio.DocViews.share(entry);
                })
                // Public shared schema table (F8 slice 3, extended): verify the signed token,
                // then render the table's read-only reference; invalid/expired -> notice.
                .register("docs.shareTable", params -> {
                    String ds = params.get("ds") == null
                            ? null
                            : String.valueOf(params.get("ds"));
                    String name = params.get("name") == null
                            ? null
                            : String.valueOf(params.get("name"));
                    String exp = params.get("exp") == null
                            ? null
                            : String.valueOf(params.get("exp"));
                    String sig = params.get("sig") == null
                            ? null
                            : String.valueOf(params.get("sig"));
                    io.tesseraql.yaml.scaffold.CatalogSchema.Table table = shareLinks
                            .verifyTable(ds, name, exp, sig) ? doc.table(ds, name) : null;
                    if (table == null) {
                        return Map.of("shared", true, "shareInvalid", true);
                    }
                    return io.tesseraql.studio.DocViews.shareTable(ds, table);
                })
                // Public shared coverage dashboard (F8 slice 3, extended): same verification;
                // the public view withholds the per-test failure detail.
                .register("docs.shareCoverage", params -> {
                    String exp = params.get("exp") == null
                            ? null
                            : String.valueOf(params.get("exp"));
                    String sig = params.get("sig") == null
                            ? null
                            : String.valueOf(params.get("sig"));
                    if (!shareLinks.verifyCoverage(exp, sig)) {
                        return Map.of("shared", true, "shareInvalid", true);
                    }
                    return io.tesseraql.studio.DocViews.shareCoverage(doc.appName(),
                            doc.report(), doc.history());
                });
    }
}
