package io.tesseraql.runtime;

import io.tesseraql.operations.batch.JobExecution;
import io.tesseraql.operations.batch.JobRepository;
import io.tesseraql.operations.outbox.JdbcOutboxStore;
import io.tesseraql.pipeline.RuntimeContext;
import io.tesseraql.pipeline.TesseraqlProperties;
import io.tesseraql.yaml.manifest.JobFile;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code ops.*} and {@code account.*} service providers behind the bundled ops-console and
 * account surfaces, with the session, TOTP, delegation and password providers beside them
 * (design ch. 26.11; docs/session-visibility.md; docs/access-governance.md). Extracted from
 * the runtime boot (docs/boot-phases.md slice 2); the captured boot state rides the
 * {@link Deps} record as the boot handed it over, and the beans that bind after this chain
 * builds - identity, realm, TOTP, delegation - resolve at call time through the accessors
 * below.
 */
final class OpsAccountProviders {

    private final Deps deps;

    /** The boot-time state the provider lambdas capture, held as handed over. */
    record Deps(OpsActions opsActions, io.tesseraql.opsui.OpsDashboard opsDashboard,
            io.tesseraql.operations.audit.JdbcRouteAuditStore routeAuditStore,
            io.tesseraql.yaml.manifest.AppManifest manifest,
            io.tesseraql.core.account.PreferenceStore preferences,
            io.tesseraql.core.account.ShortcutStore shortcuts,
            List<String> optOutChannels, List<String> accountLocales,
            io.tesseraql.core.inbox.InboxStore inboxStore,
            io.tesseraql.security.session.SessionStore sessionStore,
            io.tesseraql.core.credential.CredentialTokenStore credentialTokens,
            java.time.Duration inviteTtl, boolean inviteEnabled, boolean passwordLoginEnabled,
            RuntimeContext context, Path appHome, String appName, String inviteUrl,
            String inviteChannel, Map<String, JobFile> jobs, Map<String, String> jobOwners,
            JobRepository jobRepository, JdbcOutboxStore outboxStore,
            io.tesseraql.operations.files.JdbcFileTransferService fileTransfers,
            CalendarDecisions calendarDecisions,
            io.tesseraql.opsui.PollSourceStatus pollSourceStatus) {
    }

    private OpsAccountProviders(Deps deps) {
        this.deps = deps;
        this.workflowDetailPaths = workflowDetailPaths(deps.manifest());
    }

    /**
     * The host app's workflow document types mapped to the detail page that renders them —
     * derived from the detail views declaring {@code workflow:} (docs/workflow-surface.md
     * decision 6), so the queue links straight into the acting surface. Two views for one
     * workflow keep the first route's path; a workflow no view declares renders unlinked.
     */
    private final Map<String, String> workflowDetailPaths;

    private static Map<String, String> workflowDetailPaths(
            io.tesseraql.yaml.manifest.AppManifest manifest) {
        Map<String, String> byDocType = new LinkedHashMap<>();
        if (manifest == null) {
            return byDocType;
        }
        Map<String, String> workflowDocTypes = new LinkedHashMap<>();
        for (io.tesseraql.yaml.manifest.WorkflowFile workflow : manifest.workflows()) {
            if (workflow.definition().document() != null) {
                workflowDocTypes.put(workflow.definition().id(),
                        workflow.definition().document().type());
            }
        }
        for (io.tesseraql.yaml.manifest.RouteFile route : manifest.routes()) {
            var response = route.definition().response();
            if (response == null || response.html() == null || response.html().view() == null) {
                continue;
            }
            var view = manifest.viewById(response.html().view());
            if (view == null) {
                continue;
            }
            try {
                String workflowId = io.tesseraql.yaml.view.ViewSpec.parse(view.source())
                        .workflow();
                String docType = workflowId == null ? null : workflowDocTypes.get(workflowId);
                if (docType != null) {
                    byDocType.putIfAbsent(docType, route.urlPath());
                }
            } catch (RuntimeException unparseable) {
                // The compiler already refused a broken view; the queue map just skips it.
            }
        }
        return byDocType;
    }

    /** Builds the runtime's provider registry with every ops/account provider registered. */
    static io.tesseraql.core.service.ServiceProviders register(Deps deps) {
        OpsAccountProviders providers = new OpsAccountProviders(deps);
        io.tesseraql.core.service.ServiceProviders serviceProviders = new io.tesseraql.core.service.ServiceProviders();
        providers.ops(serviceProviders);
        providers.account(serviceProviders);
        providers.sessionsCredentialsDelegation(serviceProviders);
        return serviceProviders;
    }

    /**
     * Identity, realm, TOTP and delegation resolve from the registry at call time: they bind
     * after the boot's provider chain builds, and an SSO-only deployment answers its honest
     * degraded model instead of failing to register - the idiom IamAdminProviders uses.
     */
    private io.tesseraql.identity.IdentityService identity() {
        return deps.context().lookup(TesseraqlProperties.IDENTITY_SERVICE_BEAN,
                io.tesseraql.identity.IdentityService.class);
    }

    private io.tesseraql.identity.RealmConfig realm() {
        return deps.context().lookup(TesseraqlProperties.IDENTITY_REALM_BEAN,
                io.tesseraql.identity.RealmConfig.class);
    }

    private io.tesseraql.core.credential.TotpStore totpStore() {
        return deps.context().lookup(TesseraqlProperties.TOTP_STORE_BEAN,
                io.tesseraql.core.credential.TotpStore.class);
    }

    private io.tesseraql.core.workflow.DelegationStore delegationStore() {
        return deps.context().lookup(TesseraqlProperties.DELEGATION_STORE_BEAN,
                io.tesseraql.core.workflow.DelegationStore.class);
    }

    /** A session-row cell renders "" for an absent fact - never the string "null". */
    private static String orEmpty(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /** Batch visibility, traces, transfers, outbox and events for the ops console. */
    private void ops(io.tesseraql.core.service.ServiceProviders serviceProviders) {
        serviceProviders
                // Batch visibility narrows to the caller's tql.ops.view.<name> grants,
                // bound by the console routes as principal.permissions (ch. 26.11).
                .register("ops.overview",
                        params -> io.tesseraql.opsui.OpsViews.overview(
                                deps.opsDashboard().overview(20,
                                        deps.opsActions().viewScope(params.get("permissions"))),
                                deps.opsDashboard().health(),
                                io.tesseraql.core.TesseraqlVersion.current()))
                // The audit page is always mounted; the provider owns the honest
                // empty state when the flag-gated store is off
                // (docs/ops-console-coverage.md).
                .register("ops.audit",
                        params -> io.tesseraql.opsui.OpsViews.audit(
                                io.tesseraql.opsui.OpsViews.filterAudit(
                                        deps.routeAuditStore() == null
                                                ? null
                                                : deps.routeAuditStore().recent(200,
                                                        deps.opsActions()
                                                                .viewScope(
                                                                        params.get("permissions"))),
                                        params.get("route"), params.get("actor"),
                                        params.get("status")),
                                deps.routeAuditStore() != null))
                .register("ops.traces",
                        params -> io.tesseraql.opsui.OpsViews.traces(deps.opsDashboard().traceTree(
                                deps.opsActions().viewScope(params.get("permissions")))))
                .register("ops.transfers", params -> {
                    java.util.function.Predicate<String> scope = deps.opsActions()
                            .viewScope(params.get("permissions"));
                    return io.tesseraql.opsui.OpsViews.transfers(
                            deps.fileTransfers().recent(50).stream()
                                    .filter(transfer -> scope.test(transfer.appName()))
                                    .toList());
                })
                .register("ops.outbox",
                        params -> io.tesseraql.opsui.OpsViews.outbox(deps.opsActions().recentOutbox(
                                deps.opsActions().viewScope(params.get("permissions")))))
                // Out of scope reads exactly like unknown - the shared core's stance
                // (docs/ops-console-actions.md); 4040 renders as a plain 404.
                .register("ops.outboxRedeliver",
                        params -> deps.opsActions().redeliverOutbox(
                                String.valueOf(params.get("id")),
                                deps.opsActions().runScope(params.get("permissions"))))
                // The queue events log and its redelivery: the messaging mirror of the
                // ops.outbox pair (docs/silent-tolerance.md O1).
                .register("ops.events",
                        params -> io.tesseraql.opsui.OpsViews.events(deps.opsActions().recentEvents(
                                deps.opsActions().viewScope(params.get("permissions")))))
                .register("ops.eventsRedeliver",
                        params -> deps.opsActions().redeliverEvent(
                                String.valueOf(params.get("id")),
                                deps.opsActions().runScope(params.get("permissions"))))
                .register("ops.jobs", params -> {
                    java.util.function.Predicate<String> scope = deps.opsActions()
                            .viewScope(params.get("permissions"));
                    List<io.tesseraql.opsui.OpsViews.JobCatalogEntry> entries = new java.util.ArrayList<>();
                    deps.jobs().forEach((id, jobFile) -> {
                        String owner = deps.jobOwners().getOrDefault(id, deps.appName());
                        if (scope.test(owner)) {
                            entries.add(new io.tesseraql.opsui.OpsViews.JobCatalogEntry(
                                    id, owner, jobFile.definition(),
                                    deps.jobRepository().latestExecution(id).orElse(null),
                                    deps.pollSourceStatus().forJob(id).orElse(null),
                                    deps.calendarDecisions().nextCounting(jobFile,
                                            java.time.LocalDate.now())));
                        }
                    });
                    return io.tesseraql.opsui.OpsViews.jobs(entries);
                })
                .register("ops.jobRun", params -> {
                    String id = String.valueOf(params.get("id"));
                    // The posted body rides whole; everything under the param. prefix
                    // is a declared job parameter, and bindJobParams inside the runner
                    // stays the single validation point (docs/ops-console-coverage.md).
                    // Out of scope reads exactly like unknown - the shared core's stance.
                    JobExecution execution = deps.opsActions().runJob(id, () -> {
                        java.util.Map<String, Object> jobParams = new java.util.LinkedHashMap<>();
                        if (params.get("values") instanceof java.util.Map<?, ?> posted) {
                            posted.forEach((key, value) -> {
                                String name = String.valueOf(key);
                                if (name.startsWith("param.")) {
                                    jobParams.put(name.substring("param.".length()),
                                            value);
                                }
                            });
                        }
                        return jobParams;
                    }, params.get("actor") == null
                            ? null
                            : String.valueOf(params.get("actor")),
                            deps.opsActions().runScope(params.get("permissions")));
                    return java.util.Map.of("executionId", execution.id(),
                            "status", execution.status().name());
                })
                .register("ops.execution", params -> {
                    String id = params.get("id") == null
                            ? ""
                            : String.valueOf(params.get("id"));
                    // An execution outside the caller's scope renders as not found.
                    JobExecution execution = deps.opsActions().findExecution(id,
                            deps.opsActions().viewScope(params.get("permissions")));
                    return io.tesseraql.opsui.OpsViews.execution(id, execution,
                            execution == null ? List.of() : deps.jobRepository().findSteps(id));
                });
    }

    /**
     * The bundled account surface (roadmap Phase 48), plus the login page's own read: profile,
     * settings, inbox, invites. The routes map the session principal's facts into the params,
     * so the providers can only ever describe — or write for — the caller. Settings write
     * through the cached preference store; it is null only when the surface is off, in which
     * case the account routes are not mounted either.
     */
    private void account(io.tesseraql.core.service.ServiceProviders serviceProviders) {
        serviceProviders
                // The bundled login page reads which sign-in methods are available (password
                // always; OIDC/SAML when their extension is enabled) plus the first-login hint.
                .register("auth.loginMethods", params -> LoginMethods.of(deps.manifest().config()))
                .register("account.profile.view", AccountViews::profile)
                .register("account.settings.view",
                        params -> AccountViews.settings(params, deps.preferences(),
                                deps.accountLocales(), deps.optOutChannels(), deps.sessionStore(),
                                deps.passwordLoginEnabled(),
                                io.tesseraql.yaml.account.PreferencesSpec.live(deps.appHome()),
                                totpStore(),
                                deps.appName(),
                                delegationStore(),
                                deps.shortcuts()))
                // What the caller may elevate into (docs/access-governance.md
                // structural decision 3). Identity and realm resolve at call time,
                // like account.invite below: they bind after this chain builds.
                .register("account.eligibility",
                        params -> io.tesseraql.identity.Elevation.eligibilityModel(
                                identity(),
                                realm(),
                                String.valueOf(params.get("subject"))))
                // The requester's own side of access requests
                // (docs/access-governance.md structural decision 6): what they may ask
                // for, and what they have asked for. Never anybody else's.
                .register("account.requests",
                        params -> io.tesseraql.identity.AccessRequests.myRequestsModel(
                                identity(),
                                realm(),
                                String.valueOf(params.get("subject"))))
                .register("account.requestRole",
                        params -> io.tesseraql.identity.AccessRequests.request(
                                identity(),
                                realm(),
                                String.valueOf(params.get("subject")),
                                String.valueOf(params.get("roleCode")),
                                String.valueOf(params.get("reason")),
                                String.valueOf(params.get("minutes"))))
                .register("account.language.save",
                        params -> AccountViews.saveLanguage(params, deps.preferences(),
                                deps.accountLocales()))
                .register("account.theme.save",
                        params -> AccountViews.saveTheme(params, deps.preferences()))
                .register("account.notify.save",
                        params -> AccountViews.saveNotifyOptOut(params, deps.preferences(),
                                deps.optOutChannels()))
                .register("account.app.save",
                        params -> AccountViews.saveAppPreference(params, deps.preferences(),
                                io.tesseraql.yaml.account.PreferencesSpec.live(deps.appHome())))
                // Identity and realm resolve from the registry at call time: they are
                // bound after this chain builds, and an SSO-only deployment answers with
                // the honest 4803 instead of failing to register.
                // The in-app inbox surface (roadmap Phase 49 slice 2): list, mark one
                // read, mark all read - the subject always the session principal's.
                .register("account.inbox.view",
                        params -> AccountViews.inbox(params, deps.inboxStore()))
                // The task queue page (docs/workflow-surface.md decision 6): the store and
                // datasource resolve at call time (the identity() idiom — a runtime without
                // workflows answers the honest empty state); the docType → detail-page map
                // derives once from the host manifest's workflow-declaring detail views.
                .register("account.tasks.view",
                        params -> AccountViews.workflowTasks(params,
                                deps.context().lookup(
                                        TesseraqlProperties.WORKFLOW_TASK_STORE_BEAN,
                                        io.tesseraql.core.workflow.WorkflowTaskStore.class),
                                deps.context().lookup("main", javax.sql.DataSource.class),
                                workflowDetailPaths))
                .register("account.inbox.read",
                        params -> AccountViews.markInboxRead(params, deps.inboxStore()))
                .register("account.inbox.readAll",
                        params -> AccountViews.markAllInboxRead(params, deps.inboxStore()))
                // The iam-admin invite (roadmap Phase 50 slice 2): identity and realm
                // resolve from the registry at call time (they bind later); the token
                // store and channel settings are the hoisted finals above.
                .register("identity.invite",
                        params -> IdentityInvites.invite(params, deps.credentialTokens(),
                                deps.outboxStore(),
                                identity(),
                                realm(),
                                deps.inviteChannel(), deps.inviteUrl(), deps.inviteTtl(),
                                deps.appName(),
                                deps.inviteEnabled()));
    }

    /** Session visibility and administration, TOTP, delegation and the password change. */
    private void sessionsCredentialsDelegation(
            io.tesseraql.core.service.ServiceProviders serviceProviders) {
        serviceProviders
                // Session administration (docs/session-administration.md): the admin's
                // view of a subject's sessions renders only timestamps - session ids
                // never reach a template - and revocation ends every session of the
                // subject (the "" keep-id is the changePassword precedent).
                .register("iam.userSessions", params -> {
                    String userId = String.valueOf(params.get("userId"));
                    java.util.List<Map<String, Object>> rows = new java.util.ArrayList<>();
                    for (io.tesseraql.security.session.SessionStore.ActiveSession session : deps
                            .sessionStore()
                            .sessionsFor(userId)) {
                        // Ordered like the page's columns; Map.of here left the column
                        // order to the JVM's hashing.
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("createdAt", orEmpty(session.createdAt()));
                        row.put("expiresAt", orEmpty(session.expiresAt()));
                        row.put("lastSeenAt", orEmpty(session.lastSeenAt()));
                        row.put("userAgent", orEmpty(session.userAgent()));
                        row.put("remoteAddr", orEmpty(session.remoteAddr()));
                        row.put("handle", orEmpty(session.handle()));
                        rows.add(row);
                    }
                    return Map.of("rows", rows, "count", rows.size());
                })
                .register("iam.revokeSessions", params -> {
                    deps.sessionStore().invalidateOthersFor(
                            String.valueOf(params.get("userId")), "");
                    return Map.of("revoked", true);
                })
                // One device, by its subject-scoped handle (docs/session-visibility.md).
                .register("iam.revokeSession", params -> {
                    deps.sessionStore().invalidateByHandle(
                            String.valueOf(params.get("userId")),
                            String.valueOf(params.get("handle")));
                    return Map.of("revoked", true);
                })
                // The cross-subject sessions page (docs/session-visibility.md): live
                // store state, newest first, optionally narrowed by subject prefix.
                .register("iam.sessions", params -> {
                    String q = params.get("q") == null
                            ? ""
                            : String.valueOf(params.get("q")).trim();
                    java.util.List<Map<String, Object>> rows = new java.util.ArrayList<>();
                    for (io.tesseraql.security.session.SessionStore.ActiveSession s : deps
                            .sessionStore()
                            .activeSessions(200)) {
                        if (!q.isEmpty()
                                && (s.subject() == null || !s.subject().startsWith(q))) {
                            continue;
                        }
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("subject", s.subject() == null ? "-" : s.subject());
                        row.put("createdAt", orEmpty(s.createdAt()));
                        row.put("lastSeenAt", orEmpty(s.lastSeenAt()));
                        row.put("userAgent", orEmpty(s.userAgent()));
                        row.put("remoteAddr", orEmpty(s.remoteAddr()));
                        row.put("handle", orEmpty(s.handle()));
                        rows.add(row);
                    }
                    Map<String, Object> model = new LinkedHashMap<>();
                    model.put("rows", rows);
                    model.put("hasRows", !rows.isEmpty());
                    model.put("q", q);
                    return model;
                })
                // Disabled means disabled: the status flips AND every session of the
                // subject ends now, not at cookie expiry. Identity and realm resolve
                // lazily like identity.invite (they bind later).
                .register("iam.disableUser", params -> {
                    String userId = String.valueOf(params.get("userId"));
                    identity()
                            .executeUpdate(realm(),
                                    io.tesseraql.identity.IdentityContracts.DISABLE_USER,
                                    Map.of("userId", userId));
                    deps.sessionStore().invalidateOthersFor(userId, "");
                    return Map.of("disabled", true);
                })
                // TOTP self-service (roadmap Phase 50 slice 3): begin/confirm/disable.
                // The store binds in the identity block, so resolve lazily like
                // identity/realm; disable re-verifies the password.
                .register("account.totp.begin",
                        params -> AccountViews.totpBegin(params,
                                totpStore()))
                .register("account.totp.confirm",
                        params -> AccountViews.totpConfirm(params,
                                totpStore()))
                .register("account.totp.disable",
                        params -> AccountViews.totpDisable(params,
                                totpStore(),
                                identity(),
                                realm()))
                // The operator's delegation visibility (roadmap Phase 52 slice 2):
                // read-only rows for the IAM admin panel, tenant-scoped to the caller.
                .register("identity.delegations", params -> {
                    io.tesseraql.core.workflow.DelegationStore store = delegationStore();
                    java.util.List<Map<String, Object>> rows = new java.util.ArrayList<>();
                    if (store != null) {
                        java.time.Instant now = java.time.Instant.now();
                        for (io.tesseraql.core.workflow.DelegationStore.Entry entry : store
                                .unexpired(params.get("tenantId") == null
                                        ? null
                                        : String.valueOf(params.get("tenantId")),
                                        now, 200)) {
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("subject", entry.subject());
                            row.put("delegate", entry.delegateSubject());
                            row.put("startsAt", entry.startsAt().toString());
                            row.put("endsAt", entry.endsAt().toString());
                            row.put("active", !now.isBefore(entry.startsAt()));
                            rows.add(row);
                        }
                    }
                    return rows;
                })
                // Pins and recents (roadmap Phase 51): toggle the current page, remove
                // from the account card - the caller's own deps.shortcuts() only.
                .register("account.pins.toggle",
                        params -> AccountViews.togglePin(params, deps.shortcuts()))
                .register("account.shortcuts.remove",
                        params -> AccountViews.removeShortcut(params, deps.shortcuts()))
                // Out-of-office self-service (roadmap Phase 52); store binds with the
                // task inbox, identity/realm resolve lazily like the neighbours.
                .register("account.delegation.save",
                        params -> AccountViews.saveDelegation(params,
                                delegationStore(),
                                identity(),
                                realm()))
                .register("account.delegation.clear",
                        params -> AccountViews.clearDelegation(params,
                                delegationStore()))
                .register("account.password.change",
                        params -> AccountViews.changePassword(params,
                                identity(),
                                realm(),
                                deps.passwordLoginEnabled(),
                                deps.sessionStore()));
    }
}
