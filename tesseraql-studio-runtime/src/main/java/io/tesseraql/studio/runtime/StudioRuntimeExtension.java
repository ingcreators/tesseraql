package io.tesseraql.studio.runtime;

import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.compiler.ext.ExtensionContext;
import io.tesseraql.compiler.ext.RuntimeExtension;
import io.tesseraql.core.expr.ExpressionFunctions;
import io.tesseraql.core.service.ServiceProviders;
import io.tesseraql.identity.RealmConfig;
import io.tesseraql.runtime.RouteReloader;
import io.tesseraql.runtime.RuntimeSeams;
import io.tesseraql.security.policy.PolicyEngine;
import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.manifest.AppManifest;
import java.nio.file.Path;
import org.apache.camel.CamelContext;

/**
 * Installs the workshop — Studio's runtime-side machinery — into a starting runtime
 * (docs/studio-shell.md structural decision 3). The wiring below is the block
 * {@code TesseraqlRuntime.start(...)} inlined before the extraction, verbatim: the services,
 * the JSON API and Copilot transports, the {@code studio.*}/{@code docs.*} providers, and the
 * reload listeners that keep the explorer and the doc cache on the reload epoch. The runtime
 * module no longer names a Studio type or the declarative test engine; this jar on the
 * classpath is the whole install.
 */
public final class StudioRuntimeExtension implements RuntimeExtension {

    @Override
    public String name() {
        return "studio";
    }

    @Override
    public boolean enabled(AppConfig config) {
        return config.getString("tesseraql.studio.enabled")
                .map(Boolean::parseBoolean).orElse(true);
    }

    @Override
    public void install(ExtensionContext extension) throws Exception {
        CamelContext context = extension.camel();
        AppManifest manifest = extension.manifest();
        Path appHome = manifest.appHome();
        RuntimeSeams seams = extension.bean(TesseraqlProperties.RUNTIME_SEAMS_BEAN,
                RuntimeSeams.class);
        ServiceProviders serviceProviders = extension.bean(
                TesseraqlProperties.SERVICE_PROVIDERS_BEAN, ServiceProviders.class);
        ExpressionFunctions functions = extension.bean(TesseraqlProperties.FUNCTIONS_BEAN,
                ExpressionFunctions.class);
        RealmConfig realm = extension.bean(TesseraqlProperties.IDENTITY_REALM_BEAN,
                RealmConfig.class);
        RouteReloader reloader = seams.reloader();
        // Confirm-diff-before-every-apply (Studio backlog D5 follow-up): an opt-in gate that
        // makes the editor acknowledge the diff before each apply, not only on a conflict.
        boolean confirmApply = manifest.config()
                .getString("tesseraql.studio.confirmApply")
                .map(Boolean::parseBoolean).orElse(false);
        // Who may edit is the tql.studio.edit.<name> atom, per application — the retired
        // editRoles allow-list was the model's last framework surface reading role names.
        StudioEdit studioEdit = new StudioEdit(seams.appName(), confirmApply);

        // One extension, three faces by topology (docs/studio-shell.md structural decision 3).
        if (seams.hosted() && !seams.workshop()) {
            // A production host: nothing Studio-shaped exists, and no configuration reaches
            // here to change that (structural decision 1).
            return;
        }
        if (seams.stackMembers() != null) {
            // The surface runtime: the shell — the switcher and the delegating providers; the
            // studio app itself mounted through the host's topology graft. The workshop
            // machinery stays at the members, where its inputs live.
            StudioShellProviders.register(serviceProviders,
                    WorkshopTargets.of(seams.stackMembers(), seams.memberOrigins()));
            new CopilotProxyRouteBuilder(seams.memberOrigins()).install(context);
            return;
        }

        // Writable by construction (docs/studio-shell.md structural decision 4): the retired
        // tesseraql.studio.readOnly master switch's two jobs moved to better owners —
        // per-caller write authority is the tql.studio.edit.<name> atom (deny-by-default),
        // per-deployment safety is topology (a host mounts no Studio at all, slice 3).
        io.tesseraql.studio.StudioService studio = new io.tesseraql.studio.StudioService(
                manifest, false, functions);
        // Studio's memoized schema/decision lookups (the data browser's column contracts,
        // the SQL-builder table list); each hot reload — and the Studio schema refresh —
        // starts a fresh epoch, so the memo is never staler than the served routes.
        StudioDocCache studioDocCache = new StudioDocCache(manifest);
        reloader.onReload(() -> {
            studioDocCache.invalidate();
            studio.reload();
        });
        // The Studio test runner (backlog A2): run a route's read-only sql cases against the
        // dev datasource. An explicit opt-in, sandboxed per run (read-only connection,
        // statement timeout, row cap, rollback on close) — a capability of this workshop,
        // not an authority; who may act is the atom's question.
        boolean testRunnerEnabled = manifest.config()
                .getString("tesseraql.studio.testRunner.enabled")
                .map(Boolean::parseBoolean).orElse(false);
        int testTimeout = manifest.config()
                .getString("tesseraql.studio.testRunner.queryTimeoutSeconds")
                .map(Integer::parseInt).orElse(5);
        int testMaxRows = manifest.config()
                .getString("tesseraql.studio.testRunner.maxRows")
                .map(Integer::parseInt).orElse(1000);
        StudioTestService studioTests = new StudioTestService(
                name -> context.getRegistry().lookupByNameAndType(name,
                        javax.sql.DataSource.class),
                appHome, realm, seams.mainDatasourceDialect(),
                testRunnerEnabled, testTimeout, testMaxRows, functions);
        // The Studio scaffold generator (backlog B3): introspect a table from the dev
        // datasource and generate its CRUD slice, reusing the CLI's introspection + generator
        // so the output is byte-identical. An explicit opt-in, like the test runner.
        boolean scaffoldEnabled = manifest.config()
                .getString("tesseraql.studio.scaffold.enabled")
                .map(Boolean::parseBoolean).orElse(false);
        StudioScaffoldService studioScaffold = new StudioScaffoldService(
                name -> context.getRegistry().lookupByNameAndType(name,
                        javax.sql.DataSource.class),
                "main", studio, scaffoldEnabled);
        // The Studio data browser: read-only, paginated row access over the dev datasource.
        // Opt-in (exposes data); read-only connection + statement timeout + a scan cap.
        boolean dataBrowserEnabled = manifest.config()
                .getString("tesseraql.studio.dataBrowser.enabled")
                .map(Boolean::parseBoolean).orElse(false);
        // Row editing is its own opt-in on top of the browser (roadmap Phase 43, Track
        // J4): it writes business data, so browsing alone never implies it.
        boolean dataEditEnabled = manifest.config()
                .getString("tesseraql.studio.dataBrowser.edit.enabled")
                .map(Boolean::parseBoolean).orElse(false);
        // Copilot (roadmap Phase 44): entirely absent unless the operator opts in
        // and names an endpoint + model; the api key stays a lazy config read so a
        // ${secret.*} reference resolves at call time, never at startup. The endpoint
        // must pass the same deny-by-default egress allow-list an httpCall step
        // obeys — an off-allow-list host fails the boot (SEC 4085).
        final io.tesseraql.studio.CopilotService copilotService = manifest.config()
                .getString("tesseraql.copilot.enabled")
                .map(Boolean::parseBoolean).orElse(false)
                        ? new io.tesseraql.studio.CopilotService(studio, manifest,
                                StudioSupport.copilotEndpoint(manifest.config(),
                                        seams.httpOutbound()),
                                manifest.config()
                                        .requireString("tesseraql.copilot.model"),
                                () -> manifest.config()
                                        .getString("tesseraql.copilot.apiKey")
                                        .orElse(null),
                                manifest.config()
                                        .getString("tesseraql.copilot.maxTurns")
                                        .map(Integer::parseInt).orElse(6))
                        : null;
        StudioDataService studioData = new StudioDataService(
                name -> context.getRegistry().lookupByNameAndType(name,
                        javax.sql.DataSource.class),
                java.util.List.copyOf(seams.dataSources().keySet()),
                dataBrowserEnabled, dataEditEnabled, testTimeout, testMaxRows);
        // Output-field masking in the JSON render preview (Studio backlog A1 follow-up): the
        // runtime supplies the mask over the canonical FieldPolicyApplier (so Studio stays
        // free of the security/compiler stack), evaluated for the sample principal the
        // developer puts under `principal` in the render sample.
        PolicyEngine studioPolicyEngine = extension.bean(
                TesseraqlProperties.POLICY_ENGINE_BEAN, PolicyEngine.class);
        io.tesseraql.studio.StudioService.FieldMask studioMask = (fields, body,
                ctx) -> new io.tesseraql.compiler.binding.FieldPolicyApplier(fields,
                        studioPolicyEngine, StudioSupport.samplePrincipal(ctx)).apply(body);
        // PDF preview for query-export pdf routes (Studio backlog A1 follow-up): the runtime
        // renders through the canonical PDF codec when the optional tesseraql-pdf module is on
        // the classpath, returning null (a graceful "module absent" message) otherwise — so
        // Studio stays free of the heavy openhtmltopdf/pdfbox stack.
        io.tesseraql.studio.StudioService.PdfRender studioPdf = (export, routeDir,
                rows) -> StudioSupport.renderExportPdf(export, routeDir, appHome, rows,
                        seams.modulesLoader());
        new StudioRouteBuilder(studio, reloader, studioTests,
                studioScaffold, studioEdit, studioMask, studioPdf).install(context);
        // The member's workshop API: what the studio shell delegates to
        // (docs/studio-shell.md structural decision 2).
        new WorkshopRouteBuilder(studioEdit).install(context);
        // The wizards render their .yml.tpl artifacts from the studio app's extracted tree;
        // the member mounts no studio app anymore, so the tree is materialized here — files
        // the wizard providers read, never routes.
        new io.tesseraql.yaml.apps.ClasspathAppSource("studio", "tesseraql/apps/studio",
                StudioRuntimeExtension.class.getClassLoader())
                .materialize(io.tesseraql.yaml.config.WorkHome
                        .resolve(appHome, manifest.config()).resolve("apps"));
        // The copilot's send + stream transports (docs/copilot.md): below the YAML
        // surface because of streaming and HX-Request negotiation. Send is a Camel
        // route; the stream is an SseRoutes endpoint registered after start. Both live
        // at the member-shaped address the shell's page emits, so the unhosted boot and
        // the proxied hosted call land on one path.
        new CopilotRouteBuilder(copilotService, studioEdit,
                seams.appName()).install(context);
        seams.postStart().accept(() -> CopilotRouteBuilder.registerStream(context,
                seams.port(), copilotService, studioEdit, seams.appName()));
        // Providers backing the bundled studio app (design ch. 16, 47).
        StudioProviders.register(serviceProviders, new StudioProviders.Deps(studio,
                studioEdit, studioTests, studioScaffold, studioData, copilotService,
                studioMask, studioPdf, scaffoldEnabled, testRunnerEnabled, reloader,
                manifest, appHome, seams.appName(), seams.port(), context,
                seams.dataSources().get("main"), seams.dataSources(),
                seams.tenantDataSources(), seams.calendarDecisions(),
                seams.notificationChannels(), studioDocCache));
        DocsProviders.register(serviceProviders,
                new DocsProviders.Deps(manifest, appHome, studioEdit,
                        seams.modulesLoader(), seams.appName()));
        if (!seams.hosted()) {
            // The unhosted boot (integration tests, library embedding) is a stack of one:
            // the same shell chrome over an in-process target, so the one studio app tree
            // serves both faces (the ops shell's Targets.self precedent).
            StudioShellProviders.register(serviceProviders,
                    WorkshopTargets.self(seams.appName(), () -> serviceProviders));
        }
    }
}
