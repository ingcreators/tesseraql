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
 * (design ch. 26.11; docs/session-visibility.md; docs/access-governance.md). Extracted verbatim
 * from the runtime boot (docs/boot-phases.md slice 2) - registration order and lambda bodies
 * are exactly what {@code TesseraqlRuntime.start(...)} inlined; the captured boot state moved
 * from method locals to the fields below so every lambda body stayed as written.
 */
final class OpsAccountProviders {

    private final OpsActions opsActions;
    private final io.tesseraql.opsui.OpsDashboard dashboardRef;
    private final io.tesseraql.operations.audit.JdbcRouteAuditStore auditStoreRef;
    private final io.tesseraql.yaml.manifest.AppManifest manifest;
    private final io.tesseraql.core.account.PreferenceStore preferences;
    private final io.tesseraql.core.account.ShortcutStore shortcuts;
    private final List<String> optOutChannels;
    private final List<String> accountLocales;
    private final io.tesseraql.core.inbox.InboxStore inboxStore;
    private final io.tesseraql.security.session.SessionStore sessionStore;
    private final io.tesseraql.core.credential.CredentialTokenStore credentialTokens;
    private final java.time.Duration inviteTtl;
    private final boolean inviteEnabled;
    private final boolean passwordLoginEnabled;
    private final RuntimeContext context;
    private final Path appHome;
    private final String appName;
    private final String inviteUrl;
    private final String inviteChannel;
    private final Map<String, JobFile> jobs;
    private final Map<String, String> jobOwners;
    private final JobRepository jobRepository;
    private final JdbcOutboxStore outboxStore;
    private final io.tesseraql.operations.files.JdbcFileTransferService fileTransfers;
    private final CalendarDecisions calendarDecisions;
    private final io.tesseraql.opsui.PollSourceStatus pollSourceStatus;

    /** The boot-time state the provider lambdas capture, by the names the lambdas use. */
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
        this.opsActions = deps.opsActions();
        this.dashboardRef = deps.opsDashboard();
        this.auditStoreRef = deps.routeAuditStore();
        this.manifest = deps.manifest();
        this.preferences = deps.preferences();
        this.shortcuts = deps.shortcuts();
        this.optOutChannels = deps.optOutChannels();
        this.accountLocales = deps.accountLocales();
        this.inboxStore = deps.inboxStore();
        this.sessionStore = deps.sessionStore();
        this.credentialTokens = deps.credentialTokens();
        this.inviteTtl = deps.inviteTtl();
        this.inviteEnabled = deps.inviteEnabled();
        this.passwordLoginEnabled = deps.passwordLoginEnabled();
        this.context = deps.context();
        this.appHome = deps.appHome();
        this.appName = deps.appName();
        this.inviteUrl = deps.inviteUrl();
        this.inviteChannel = deps.inviteChannel();
        this.jobs = deps.jobs();
        this.jobOwners = deps.jobOwners();
        this.jobRepository = deps.jobRepository();
        this.outboxStore = deps.outboxStore();
        this.fileTransfers = deps.fileTransfers();
        this.calendarDecisions = deps.calendarDecisions();
        this.pollSourceStatus = deps.pollSourceStatus();
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

    /** Batch visibility, traces, transfers, outbox and events for the ops console. */
    private void ops(io.tesseraql.core.service.ServiceProviders serviceProviders) {
        serviceProviders
                // Batch visibility narrows to the caller's tql.ops.view.<name> grants,
                // bound by the console routes as principal.permissions (ch. 26.11).
                .register("ops.overview",
                        params -> io.tesseraql.opsui.OpsViews.overview(dashboardRef.overview(20,
                                opsActions.viewScope(params.get("permissions"))),
                                dashboardRef.health(),
                                io.tesseraql.core.TesseraqlVersion.current()))
                // The audit page is always mounted; the provider owns the honest
                // empty state when the flag-gated store is off
                // (docs/ops-console-coverage.md).
                .register("ops.audit",
                        params -> io.tesseraql.opsui.OpsViews.audit(
                                io.tesseraql.opsui.OpsViews.filterAudit(
                                        auditStoreRef == null
                                                ? null
                                                : auditStoreRef.recent(200, opsActions
                                                        .viewScope(params.get("permissions"))),
                                        params.get("route"), params.get("actor"),
                                        params.get("status")),
                                auditStoreRef != null))
                .register("ops.traces",
                        params -> io.tesseraql.opsui.OpsViews.traces(dashboardRef.traceTree(
                                opsActions.viewScope(params.get("permissions")))))
                .register("ops.transfers", params -> {
                    java.util.function.Predicate<String> scope = opsActions
                            .viewScope(params.get("permissions"));
                    return io.tesseraql.opsui.OpsViews.transfers(
                            fileTransfers.recent(50).stream()
                                    .filter(transfer -> scope.test(transfer.appName()))
                                    .toList());
                })
                .register("ops.outbox",
                        params -> io.tesseraql.opsui.OpsViews.outbox(opsActions.recentOutbox(
                                opsActions.viewScope(params.get("permissions")))))
                // Out of scope reads exactly like unknown - the shared core's stance
                // (docs/ops-console-actions.md); 4040 renders as a plain 404.
                .register("ops.outboxRedeliver",
                        params -> opsActions.redeliverOutbox(
                                String.valueOf(params.get("id")),
                                opsActions.runScope(params.get("permissions"))))
                // The queue events log and its redelivery: the messaging mirror of the
                // ops.outbox pair (docs/silent-tolerance.md O1).
                .register("ops.events",
                        params -> io.tesseraql.opsui.OpsViews.events(opsActions.recentEvents(
                                opsActions.viewScope(params.get("permissions")))))
                .register("ops.eventsRedeliver",
                        params -> opsActions.redeliverEvent(
                                String.valueOf(params.get("id")),
                                opsActions.runScope(params.get("permissions"))))
                .register("ops.jobs", params -> {
                    java.util.function.Predicate<String> scope = opsActions
                            .viewScope(params.get("permissions"));
                    List<io.tesseraql.opsui.OpsViews.JobCatalogEntry> entries = new java.util.ArrayList<>();
                    jobs.forEach((id, jobFile) -> {
                        String owner = jobOwners.getOrDefault(id, appName);
                        if (scope.test(owner)) {
                            entries.add(new io.tesseraql.opsui.OpsViews.JobCatalogEntry(
                                    id, owner, jobFile.definition(),
                                    jobRepository.latestExecution(id).orElse(null),
                                    pollSourceStatus.forJob(id).orElse(null),
                                    calendarDecisions.nextCounting(jobFile,
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
                    JobExecution execution = opsActions.runJob(id, () -> {
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
                            opsActions.runScope(params.get("permissions")));
                    return java.util.Map.of("executionId", execution.id(),
                            "status", execution.status().name());
                })
                .register("ops.execution", params -> {
                    String id = params.get("id") == null
                            ? ""
                            : String.valueOf(params.get("id"));
                    // An execution outside the caller's scope renders as not found.
                    JobExecution execution = opsActions.findExecution(id,
                            opsActions.viewScope(params.get("permissions")));
                    return io.tesseraql.opsui.OpsViews.execution(id, execution,
                            execution == null ? List.of() : jobRepository.findSteps(id));
                })
                // The bundled login page reads which sign-in methods are available (password
                // always; OIDC/SAML when their extension is enabled) plus the first-login hint.
                .register("auth.loginMethods", params -> LoginMethods.of(manifest.config()))
        // The bundled account surface (roadmap Phase 48): the routes map the
        // session principal's facts into the params, so the providers can only
        // ever describe — or write for — the caller. Settings write through the
        // cached preference store bound above; it is null only when the surface
        // is off, in which case the account routes are not mounted either.
        ;
    }

    /** The account surface: profile, settings, inbox, invites. */
    private void account(io.tesseraql.core.service.ServiceProviders serviceProviders) {
        serviceProviders
                .register("account.profile.view", AccountViews::profile)
                .register("account.settings.view",
                        params -> AccountViews.settings(params, preferences,
                                accountLocales, optOutChannels, sessionStore,
                                passwordLoginEnabled,
                                io.tesseraql.yaml.account.PreferencesSpec.live(appHome),
                                context.lookup(
                                        TesseraqlProperties.TOTP_STORE_BEAN,
                                        io.tesseraql.core.credential.TotpStore.class),
                                appName,
                                context.lookup(
                                        TesseraqlProperties.DELEGATION_STORE_BEAN,
                                        io.tesseraql.core.workflow.DelegationStore.class),
                                shortcuts))
                // What the caller may elevate into (docs/access-governance.md
                // structural decision 3). Identity and realm resolve at call time,
                // like account.invite below: they bind after this chain builds.
                .register("account.eligibility",
                        params -> io.tesseraql.identity.Elevation.eligibilityModel(
                                context.lookup(
                                        TesseraqlProperties.IDENTITY_SERVICE_BEAN,
                                        io.tesseraql.identity.IdentityService.class),
                                context.lookup(
                                        TesseraqlProperties.IDENTITY_REALM_BEAN,
                                        io.tesseraql.identity.RealmConfig.class),
                                String.valueOf(params.get("subject"))))
                // The requester's own side of access requests
                // (docs/access-governance.md structural decision 6): what they may ask
                // for, and what they have asked for. Never anybody else's.
                .register("account.requests",
                        params -> io.tesseraql.identity.AccessRequests.myRequestsModel(
                                context.lookup(
                                        TesseraqlProperties.IDENTITY_SERVICE_BEAN,
                                        io.tesseraql.identity.IdentityService.class),
                                context.lookup(
                                        TesseraqlProperties.IDENTITY_REALM_BEAN,
                                        io.tesseraql.identity.RealmConfig.class),
                                String.valueOf(params.get("subject"))))
                .register("account.requestRole",
                        params -> io.tesseraql.identity.AccessRequests.request(
                                context.lookup(
                                        TesseraqlProperties.IDENTITY_SERVICE_BEAN,
                                        io.tesseraql.identity.IdentityService.class),
                                context.lookup(
                                        TesseraqlProperties.IDENTITY_REALM_BEAN,
                                        io.tesseraql.identity.RealmConfig.class),
                                String.valueOf(params.get("subject")),
                                String.valueOf(params.get("roleCode")),
                                String.valueOf(params.get("reason")),
                                String.valueOf(params.get("minutes"))))
                .register("account.language.save",
                        params -> AccountViews.saveLanguage(params, preferences,
                                accountLocales))
                .register("account.theme.save",
                        params -> AccountViews.saveTheme(params, preferences))
                .register("account.notify.save",
                        params -> AccountViews.saveNotifyOptOut(params, preferences,
                                optOutChannels))
                .register("account.app.save",
                        params -> AccountViews.saveAppPreference(params, preferences,
                                io.tesseraql.yaml.account.PreferencesSpec.live(appHome)))
                // Identity and realm resolve from the registry at call time: they are
                // bound after this chain builds, and an SSO-only deployment answers with
                // the honest 4803 instead of failing to register.
                // The in-app inbox surface (roadmap Phase 49 slice 2): list, mark one
                // read, mark all read - the subject always the session principal's.
                .register("account.inbox.view",
                        params -> AccountViews.inbox(params, inboxStore))
                .register("account.inbox.read",
                        params -> AccountViews.markInboxRead(params, inboxStore))
                .register("account.inbox.readAll",
                        params -> AccountViews.markAllInboxRead(params, inboxStore))
                // The iam-admin invite (roadmap Phase 50 slice 2): identity and realm
                // resolve from the registry at call time (they bind later); the token
                // store and channel settings are the hoisted finals above.
                .register("identity.invite",
                        params -> IdentityInvites.invite(params, credentialTokens,
                                outboxStore,
                                context.lookup(
                                        TesseraqlProperties.IDENTITY_SERVICE_BEAN,
                                        io.tesseraql.identity.IdentityService.class),
                                context.lookup(
                                        TesseraqlProperties.IDENTITY_REALM_BEAN,
                                        io.tesseraql.identity.RealmConfig.class),
                                inviteChannel, inviteUrl, inviteTtl, appName,
                                inviteEnabled))
                // Session administration (docs/session-administration.md): the admin's
                // view of a subject's sessions renders only timestamps - session ids
                // never reach a template - and revocation ends every session of the
                // subject (the "" keep-id is the changePassword precedent).
                .register("iam.userSessions", params -> {
                    String userId = String.valueOf(params.get("userId"));
                    java.util.List<Map<String, Object>> rows = new java.util.ArrayList<>();
                    for (io.tesseraql.security.session.SessionStore.ActiveSession session : sessionStore
                            .sessionsFor(userId)) {
                        rows.add(Map.of(
                                "createdAt", session.createdAt() == null
                                        ? ""
                                        : session.createdAt().toString(),
                                "expiresAt", session.expiresAt() == null
                                        ? ""
                                        : session.expiresAt().toString(),
                                "lastSeenAt", session.lastSeenAt() == null
                                        ? ""
                                        : session.lastSeenAt().toString(),
                                "userAgent", session.userAgent() == null
                                        ? ""
                                        : session.userAgent(),
                                "remoteAddr", session.remoteAddr() == null
                                        ? ""
                                        : session.remoteAddr(),
                                "handle", session.handle() == null
                                        ? ""
                                        : session.handle()));
                    }
                    return Map.of("rows", rows, "count", rows.size());
                })
                .register("iam.revokeSessions", params -> {
                    sessionStore.invalidateOthersFor(
                            String.valueOf(params.get("userId")), "");
                    return Map.of("revoked", true);
                })
                // One device, by its subject-scoped handle (docs/session-visibility.md).
                .register("iam.revokeSession", params -> {
                    sessionStore.invalidateByHandle(
                            String.valueOf(params.get("userId")),
                            String.valueOf(params.get("handle")));
                    return Map.of("revoked", true);
                })
        // The cross-subject sessions page (docs/session-visibility.md): live
        // store state, newest first, optionally narrowed by subject prefix.
        ;
    }

    /** Sessions, TOTP, delegation and the password change. */
    private void sessionsCredentialsDelegation(
            io.tesseraql.core.service.ServiceProviders serviceProviders) {
        serviceProviders
                .register("iam.sessions", params -> {
                    String q = params.get("q") == null
                            ? ""
                            : String.valueOf(params.get("q")).trim();
                    java.util.List<Map<String, Object>> rows = new java.util.ArrayList<>();
                    for (io.tesseraql.security.session.SessionStore.ActiveSession s : sessionStore
                            .activeSessions(200)) {
                        if (!q.isEmpty()
                                && (s.subject() == null || !s.subject().startsWith(q))) {
                            continue;
                        }
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("subject", s.subject() == null ? "-" : s.subject());
                        row.put("createdAt",
                                s.createdAt() == null ? "" : s.createdAt().toString());
                        row.put("lastSeenAt",
                                s.lastSeenAt() == null ? "" : s.lastSeenAt().toString());
                        row.put("userAgent", s.userAgent() == null ? "" : s.userAgent());
                        row.put("remoteAddr",
                                s.remoteAddr() == null ? "" : s.remoteAddr());
                        row.put("handle", s.handle() == null ? "" : s.handle());
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
                    context.lookup(
                            TesseraqlProperties.IDENTITY_SERVICE_BEAN,
                            io.tesseraql.identity.IdentityService.class)
                            .executeUpdate(context.lookup(
                                    TesseraqlProperties.IDENTITY_REALM_BEAN,
                                    io.tesseraql.identity.RealmConfig.class),
                                    io.tesseraql.identity.IdentityContracts.DISABLE_USER,
                                    Map.of("userId", userId));
                    sessionStore.invalidateOthersFor(userId, "");
                    return Map.of("disabled", true);
                })
                // TOTP self-service (roadmap Phase 50 slice 3): begin/confirm/disable.
                // The store binds in the identity block, so resolve lazily like
                // identity/realm; disable re-verifies the password.
                .register("account.totp.begin",
                        params -> AccountViews.totpBegin(params,
                                context.lookup(
                                        TesseraqlProperties.TOTP_STORE_BEAN,
                                        io.tesseraql.core.credential.TotpStore.class)))
                .register("account.totp.confirm",
                        params -> AccountViews.totpConfirm(params,
                                context.lookup(
                                        TesseraqlProperties.TOTP_STORE_BEAN,
                                        io.tesseraql.core.credential.TotpStore.class)))
                .register("account.totp.disable",
                        params -> AccountViews.totpDisable(params,
                                context.lookup(
                                        TesseraqlProperties.TOTP_STORE_BEAN,
                                        io.tesseraql.core.credential.TotpStore.class),
                                context.lookup(
                                        TesseraqlProperties.IDENTITY_SERVICE_BEAN,
                                        io.tesseraql.identity.IdentityService.class),
                                context.lookup(
                                        TesseraqlProperties.IDENTITY_REALM_BEAN,
                                        io.tesseraql.identity.RealmConfig.class)))
                // The operator's delegation visibility (roadmap Phase 52 slice 2):
                // read-only rows for the IAM admin panel, tenant-scoped to the caller.
                .register("identity.delegations", params -> {
                    io.tesseraql.core.workflow.DelegationStore store = context.lookup(
                            TesseraqlProperties.DELEGATION_STORE_BEAN,
                            io.tesseraql.core.workflow.DelegationStore.class);
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
                // from the account card - the caller's own shortcuts only.
                .register("account.pins.toggle",
                        params -> AccountViews.togglePin(params, shortcuts))
                .register("account.shortcuts.remove",
                        params -> AccountViews.removeShortcut(params, shortcuts))
                // Out-of-office self-service (roadmap Phase 52); store binds with the
                // task inbox, identity/realm resolve lazily like the neighbours.
                .register("account.delegation.save",
                        params -> AccountViews.saveDelegation(params,
                                context.lookup(
                                        TesseraqlProperties.DELEGATION_STORE_BEAN,
                                        io.tesseraql.core.workflow.DelegationStore.class),
                                context.lookup(
                                        TesseraqlProperties.IDENTITY_SERVICE_BEAN,
                                        io.tesseraql.identity.IdentityService.class),
                                context.lookup(
                                        TesseraqlProperties.IDENTITY_REALM_BEAN,
                                        io.tesseraql.identity.RealmConfig.class)))
                .register("account.delegation.clear",
                        params -> AccountViews.clearDelegation(params,
                                context.lookup(
                                        TesseraqlProperties.DELEGATION_STORE_BEAN,
                                        io.tesseraql.core.workflow.DelegationStore.class)))
                .register("account.password.change",
                        params -> AccountViews.changePassword(params,
                                context.lookup(
                                        TesseraqlProperties.IDENTITY_SERVICE_BEAN,
                                        io.tesseraql.identity.IdentityService.class),
                                context.lookup(
                                        TesseraqlProperties.IDENTITY_REALM_BEAN,
                                        io.tesseraql.identity.RealmConfig.class),
                                passwordLoginEnabled,
                                context.lookup(
                                        TesseraqlProperties.SESSION_STORE_BEAN,
                                        io.tesseraql.security.session.SessionStore.class)));
    }
}
