package io.tesseraql.runtime;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.service.ServiceProviders;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The ops shell's delegating providers (docs/stack-shells.md structural decision 2): the
 * console's routes render the stack, one member at a time, and every page's data comes from the
 * selected member's own runtime.
 *
 * <p>On the stack surface runtime the targets are the members, reached by <b>real HTTP over
 * loopback</b> (stack-architecture Decision 15) at their live internal ports — resolved per call
 * through the host's slots, exactly as the relay resolves per request, so a delegation stays
 * correct across replaces. Every delegated call forwards the caller's own session cookie (and
 * CSRF token on actions); the session store is shared, so the member authenticates the same
 * principal and re-runs its own grant checks — <b>authorization stays at the member, and the
 * shell adds reach, not authority</b>. On the unhosted boot (tests, embedding — no host, no
 * origin) the console mounts locally as a fallback and the one target is this runtime itself, a
 * stack of one, answered in process.
 *
 * <p>The switcher is the grant applied to the member list: the caller's
 * {@code tql.ops.view.<name>} atoms filter membership, deny by default — no atoms, empty
 * switcher. A staged canary shows as a second entry ({@code orders (canary)}), because
 * runtime-local data (traces, lanes, slow SQL) is exactly what an operator watches a ramp for,
 * and neither a weighted roll nor a stable pin can show the canary's ring on purpose.
 */
final class OpsShellProviders {

    /**
     * TQL-BATCH-5030: a member's runtime did not answer a delegated call — a replace in
     * progress, a crashed runtime (HTTP 503). The overview degrades per member instead of
     * surfacing this; a member page surfaces it, because half a page would claim more than the
     * shell knows.
     */
    private static final TqlErrorCode MEMBER_UNREACHABLE = new TqlErrorCode(TqlDomain.BATCH, 5030);

    /** How long the fan-out overview waits per member card before it degrades. */
    private static final java.time.Duration CARD_TIMEOUT = java.time.Duration.ofSeconds(3);

    /** How long a member page waits for its one delegated call. */
    private static final java.time.Duration PAGE_TIMEOUT = java.time.Duration.ofSeconds(15);

    private OpsShellProviders() {
    }

    /** One delegated operation: the member-side face and the in-process provider it mirrors. */
    private enum Op {
        OVERVIEW("ops.overview", "GET", "overview"), JOBS("ops.jobs", "GET", "jobs"), TRACES(
                "ops.traces", "GET",
                "traces"), TRANSFERS("ops.transfers", "GET", "transfers"), OUTBOX("ops.outbox",
                        "GET", "outbox"), EVENTS("ops.events", "GET", "events"), AUDIT("ops.audit",
                                "GET", "audit"), EXECUTION("ops.execution", "GET", null), JOB_RUN(
                                        "ops.jobRun", "POST",
                                        "jobs/run"), OUTBOX_REDELIVER("ops.outboxRedeliver", "POST",
                                                null), EVENTS_REDELIVER("ops.eventsRedeliver",
                                                        "POST", null);

        final String provider;
        final String method;
        final String path;

        Op(String provider, String method, String path) {
            this.provider = provider;
            this.method = method;
            this.path = path;
        }

        /** The member-side data path, with the id-bearing shapes spelled out. */
        String dataPath(Map<String, Object> params) {
            return switch (this) {
                case EXECUTION -> "executions/" + encode(str(params, "id"));
                case OUTBOX_REDELIVER -> "outbox/" + encode(str(params, "id")) + "/redeliver";
                case EVENTS_REDELIVER -> "events/" + encode(str(params, "id")) + "/redeliver";
                default -> path;
            };
        }
    }

    /**
     * Where delegated calls go: the member list and, per member and slot, the call itself.
     * Two shapes, one contract — the stack's live HTTP targets and the unhosted boot's
     * in-process self.
     */
    interface Targets {

        List<String> memberNames();

        /** The members' installed versions by name — the deploy page's table. */
        default Map<String, String> memberVersions() {
            return Map.of();
        }

        /**
         * Whether this runtime carries the stack's deploy endpoint — the surface runtime's
         * pen. The unhosted boot has no pen, so the deploy page neither lists nor answers
         * there (docs/stack-shells.md, the deploy page).
         */
        default boolean deploySurface() {
            return false;
        }

        boolean hasCanary(String member);

        Map<String, Object> invoke(String member, boolean canary, Op op,
                Map<String, Object> params, String cookie, String csrf,
                java.time.Duration timeout);

        /**
         * The member-side URL a transfer download proxies from, or {@code null} when the target
         * is this runtime itself and the local handler answers directly.
         */
        String downloadUrl(String member, boolean canary, String id);

        /** The stack's members at their live internal ports (the surface runtime's shape). */
        static Targets of(List<io.tesseraql.operations.app.InstalledApp> members,
                HostContext.MemberOrigins origins) {
            Map<String, String> basePaths = new LinkedHashMap<>();
            members.forEach(member -> basePaths.put(member.name(), member.basePath()));
            List<String> names = List.copyOf(basePaths.keySet());
            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
            return new Targets() {
                @Override
                public List<String> memberNames() {
                    return names;
                }

                @Override
                public Map<String, String> memberVersions() {
                    // Read live per call, like ports: the boot-time member list goes stale
                    // the moment a deploy replaces a runtime.
                    Map<String, String> versions = new LinkedHashMap<>();
                    names.forEach(name -> versions.put(name, origins.version(name)));
                    return versions;
                }

                @Override
                public boolean deploySurface() {
                    return true;
                }

                @Override
                public boolean hasCanary(String member) {
                    return origins.hasCanary(member);
                }

                @Override
                public Map<String, Object> invoke(String member, boolean canary, Op op,
                        Map<String, Object> params, String cookie, String csrf,
                        java.time.Duration timeout) {
                    int port;
                    try {
                        port = origins.port(member, canary);
                    } catch (TqlException ex) {
                        throw OpsActions.notFound("Application '" + member + "'");
                    }
                    String url = "http://localhost:" + port + basePaths.getOrDefault(member, "")
                            + "/_tesseraql/ops/data/" + op.dataPath(params);
                    if ("GET".equals(op.method)) {
                        String query = queryString(op, params);
                        if (!query.isEmpty()) {
                            url += "?" + query;
                        }
                    }
                    java.net.http.HttpRequest.Builder request = java.net.http.HttpRequest
                            .newBuilder(java.net.URI.create(url))
                            .timeout(timeout);
                    if (cookie != null) {
                        request.header("Cookie", cookie);
                    }
                    if ("POST".equals(op.method)) {
                        if (csrf != null) {
                            request.header("X-CSRF-Token", csrf);
                        }
                        request.header("Content-Type", "application/x-www-form-urlencoded");
                        request.method("POST", java.net.http.HttpRequest.BodyPublishers
                                .ofString(formBody(op, params)));
                    }
                    java.net.http.HttpResponse<String> response;
                    try {
                        response = client.send(request.build(),
                                java.net.http.HttpResponse.BodyHandlers.ofString());
                    } catch (java.io.IOException ex) {
                        throw unreachable(member, ex.getMessage());
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw unreachable(member, "interrupted");
                    }
                    if (response.statusCode() == 404) {
                        // The member said no — out of the caller's scope or genuinely
                        // unknown, which read the same on purpose (TQL-BATCH-4040).
                        throw OpsActions.notFound("Application '" + member + "'");
                    }
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        throw unreachable(member, "HTTP " + response.statusCode());
                    }
                    try {
                        return json.readValue(response.body(),
                                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                                });
                    } catch (com.fasterxml.jackson.core.JacksonException ex) {
                        throw unreachable(member, "unparseable answer");
                    }
                }

                @Override
                public String downloadUrl(String member, boolean canary, String id) {
                    int port;
                    try {
                        port = origins.port(member, canary);
                    } catch (TqlException ex) {
                        throw OpsActions.notFound("Application '" + member + "'");
                    }
                    return "http://localhost:" + port + basePaths.getOrDefault(member, "")
                            + "/_tesseraql/ops/console/transfers/" + encode(id) + "/file";
                }
            };
        }

        /**
         * The unhosted boot's one target: this runtime itself, answered in process — the
         * member and the shell are the same runtime here, so there is no boundary for
         * Decision 15's real-HTTP rule to hold at (and an ephemeral test port could not
         * carry it anyway).
         */
        static Targets self(String appName,
                java.util.function.Supplier<ServiceProviders> providers) {
            return new Targets() {
                @Override
                public List<String> memberNames() {
                    return List.of(appName);
                }

                @Override
                public boolean hasCanary(String member) {
                    return false;
                }

                @Override
                public Map<String, Object> invoke(String member, boolean canary, Op op,
                        Map<String, Object> params, String cookie, String csrf,
                        java.time.Duration timeout) {
                    Object answer = providers.get().require(op.provider).invoke(params);
                    if (answer instanceof Map<?, ?> map) {
                        Map<String, Object> result = new LinkedHashMap<>();
                        map.forEach((key, value) -> result.put(String.valueOf(key), value));
                        return result;
                    }
                    return new LinkedHashMap<>();
                }

                @Override
                public String downloadUrl(String member, boolean canary, String id) {
                    return null;
                }
            };
        }
    }

    /** Registers the shell's providers; the console app's routes are their only callers. */
    static void register(ServiceProviders providers, Targets targets) {
        providers
                .register("ops.shell.nav",
                        params -> navModel(params.get("permissions"), targets))
                .register("ops.shell.home", params -> home(params, targets))
                .register("ops.shell.deploy", params -> deployPage(params, targets))
                .register("ops.shell.overview",
                        params -> page(Op.OVERVIEW, params, targets, Map.of()))
                .register("ops.shell.jobs", params -> page(Op.JOBS, params, targets, Map.of()))
                .register("ops.shell.traces",
                        params -> page(Op.TRACES, params, targets, Map.of()))
                .register("ops.shell.transfers",
                        params -> page(Op.TRANSFERS, params, targets, Map.of()))
                .register("ops.shell.outbox",
                        params -> page(Op.OUTBOX, params, targets, Map.of()))
                .register("ops.shell.events",
                        params -> page(Op.EVENTS, params, targets, Map.of()))
                .register("ops.shell.audit", params -> page(Op.AUDIT, params, targets,
                        pass(params, "route", "actor", "status")))
                .register("ops.shell.execution", params -> page(Op.EXECUTION, params, targets,
                        pass(params, "id")))
                .register("ops.shell.jobRun", params -> act(Op.JOB_RUN, params, targets))
                .register("ops.shell.outboxRedeliver",
                        params -> act(Op.OUTBOX_REDELIVER, params, targets))
                .register("ops.shell.eventsRedeliver",
                        params -> act(Op.EVENTS_REDELIVER, params, targets));
    }

    /**
     * The switcher: the grant applied to the member list, deny by default. A staged canary is a
     * second entry, addressed by {@code ?slot=canary} beside the member's own page addresses.
     */
    private static List<Map<String, Object>> entries(Object permissions, Targets targets) {
        Predicate<String> view = io.tesseraql.opsui.OpsScope.view(permissions,
                Set.copyOf(targets.memberNames()));
        List<Map<String, Object>> entries = new ArrayList<>();
        for (String name : targets.memberNames()) {
            if (!view.test(name)) {
                continue;
            }
            entries.add(entry(name, false));
            if (targets.hasCanary(name)) {
                entries.add(entry(name, true));
            }
        }
        return entries;
    }

    /** The shell nav's model: the switcher entries plus the deploy entry's display gate. */
    private static Map<String, Object> navModel(Object permissions, Targets targets) {
        Map<String, Object> nav = new LinkedHashMap<>();
        nav.put("entries", entries(permissions, targets));
        nav.put("deploy", deployNav(permissions, targets));
        return nav;
    }

    /**
     * The deploy entry's display gate (docs/stack-shells.md, the deploy page): any
     * {@code tql.app.deploy} atom, and the deploy surface existing at all. Reach, not
     * authority — the endpoint re-checks the atom against the package's declared name.
     */
    private static boolean deployNav(Object permissions, Targets targets) {
        return targets.deploySurface()
                && io.tesseraql.opsui.OpsScope.holdsAnyDeploy(permissions);
    }

    /**
     * The deploy page's model: refused 404-shaped for a non-holder — unlisted and unreachable
     * in one move, like every out-of-scope ops resource — and the member table is the caller's
     * deploy scope applied to the member list, each with its installed version and canary
     * state. The success banner's fields ride the route's own query parameters, not this model.
     */
    private static Map<String, Object> deployPage(Map<String, Object> params, Targets targets) {
        Object permissions = params.get("permissions");
        if (!deployNav(permissions, targets)) {
            throw OpsActions.notFound("The deploy page", "tql.app.deploy");
        }
        Predicate<String> scope = io.tesseraql.opsui.OpsScope.deploy(permissions,
                Set.copyOf(targets.memberNames()));
        Map<String, String> versions = targets.memberVersions();
        List<Map<String, Object>> apps = new ArrayList<>();
        for (String name : targets.memberNames()) {
            if (!scope.test(name)) {
                continue;
            }
            Map<String, Object> app = new LinkedHashMap<>();
            app.put("name", name);
            app.put("version", versions.get(name) == null ? "-" : versions.get(name));
            app.put("canary", targets.hasCanary(name));
            apps.add(app);
        }
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("shell", navModel(permissions, targets));
        model.put("apps", apps);
        model.put("hasApps", !apps.isEmpty());
        return model;
    }

    private static Map<String, Object> entry(String name, boolean canary) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", name);
        entry.put("label", canary ? name + " (canary)" : name);
        entry.put("canary", canary);
        entry.put("base", "/_tesseraql/ops/console/" + name);
        entry.put("slotQuery", canary ? "?slot=canary" : "");
        entry.put("href", "/_tesseraql/ops/console/" + name + (canary ? "?slot=canary" : ""));
        return entry;
    }

    /**
     * The fan-out overview: one card per visible entry, each filled by a delegated call under a
     * short per-member timeout, and an unreachable member's card says so while the page renders
     * — a shell that 500s because one member is mid-replace would contradict Decision 29's
     * requirement from the observing side.
     */
    private static Map<String, Object> home(Map<String, Object> params, Targets targets) {
        List<Map<String, Object>> entries = entries(params.get("permissions"), targets);
        String cookie = str(params, "cookie");
        List<java.util.concurrent.CompletableFuture<Map<String, Object>>> futures = new ArrayList<>();
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            for (Map<String, Object> entry : entries) {
                futures.add(java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    Map<String, Object> card = new LinkedHashMap<>(entry);
                    try {
                        card.put("data", targets.invoke(str(entry, "name"),
                                Boolean.TRUE.equals(entry.get("canary")), Op.OVERVIEW,
                                providerParams(params, Map.of()), cookie, null, CARD_TIMEOUT));
                        card.put("unreachable", false);
                    } catch (RuntimeException ex) {
                        card.put("unreachable", true);
                    }
                    return card;
                }, executor));
            }
        }
        List<Map<String, Object>> cards = new ArrayList<>();
        futures.forEach(future -> cards.add(future.join()));
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("shell", homeShell(entries, params.get("permissions"), targets));
        model.put("cards", cards);
        return model;
    }

    /** One member page: the shell's reach check, the delegated call, the merged shell context. */
    private static Map<String, Object> page(Op op, Map<String, Object> params, Targets targets,
            Map<String, Object> pageParams) {
        Selected selected = select(params, targets, io.tesseraql.opsui.OpsScope.VIEW_PREFIX);
        Map<String, Object> model = new LinkedHashMap<>(targets.invoke(selected.member(),
                selected.canary(), op, providerParams(params, pageParams),
                str(params, "cookie"), null, PAGE_TIMEOUT));
        model.put("shell", selected.shell(params, targets));
        return model;
    }

    /**
     * One member action: the reach check — by the <em>run</em> verb, because acting is what a
     * POST asks for and {@code tql.ops.run.<name>} may be held without the view verb — then the
     * forwarded POST, CSRF token included. The member re-runs the same run-scope check.
     */
    private static Map<String, Object> act(Op op, Map<String, Object> params, Targets targets) {
        Selected selected = select(params, targets, io.tesseraql.opsui.OpsScope.RUN_PREFIX);
        Map<String, Object> pageParams = new LinkedHashMap<>(pass(params, "id", "actor"));
        if (params.get("values") instanceof Map<?, ?> values) {
            pageParams.put("values", values);
        }
        return targets.invoke(selected.member(), selected.canary(), op,
                providerParams(params, pageParams), str(params, "cookie"),
                str(params, "csrf"), PAGE_TIMEOUT);
    }

    record Selected(String member, boolean canary) {

        Map<String, Object> shell(Map<String, Object> params, Targets targets) {
            Map<String, Object> shell = new LinkedHashMap<>(entry(member, canary));
            shell.put("entries", entries(params.get("permissions"), targets));
            shell.put("deploy", deployNav(params.get("permissions"), targets));
            return shell;
        }
    }

    /**
     * The shell's own reach check: the addressed member must be in the caller's scope for the
     * asked verb (view for pages and downloads, run for actions), and a canary slot must exist.
     * Refused with the 404-shaped TQL-BATCH-4040 — unknown and out-of-scope read the same. The
     * member re-runs its own checks on the delegated call; this one only keeps the shell from
     * delegating on a caller's bare say-so.
     */
    static Selected select(Map<String, Object> params, Targets targets, String verbPrefix) {
        String member = str(params, "member");
        boolean canary = "canary".equals(str(params, "slot"));
        Set<String> members = Set.copyOf(targets.memberNames());
        Predicate<String> scope = io.tesseraql.opsui.OpsScope.RUN_PREFIX.equals(verbPrefix)
                ? io.tesseraql.opsui.OpsScope.run(params.get("permissions"), members)
                : io.tesseraql.opsui.OpsScope.view(params.get("permissions"), members);
        if (member == null || !scope.test(member)) {
            throw OpsActions.notFound("Application '" + member + "'");
        }
        if (canary && !targets.hasCanary(member)) {
            throw OpsActions.notFound("Canary of '" + member + "'");
        }
        return new Selected(member, canary);
    }

    private static Map<String, Object> homeShell(List<Map<String, Object>> entries,
            Object permissions, Targets targets) {
        Map<String, Object> shell = new LinkedHashMap<>();
        shell.put("entries", entries);
        shell.put("deploy", deployNav(permissions, targets));
        return shell;
    }

    /** What the in-process flavor hands the local provider: the page params + permissions. */
    private static Map<String, Object> providerParams(Map<String, Object> params,
            Map<String, Object> pageParams) {
        Map<String, Object> provided = new LinkedHashMap<>(pageParams);
        provided.put("permissions", params.get("permissions"));
        return provided;
    }

    private static Map<String, Object> pass(Map<String, Object> params, String... names) {
        Map<String, Object> passed = new LinkedHashMap<>();
        for (String name : names) {
            if (params.get(name) != null) {
                passed.put(name, params.get(name));
            }
        }
        return passed;
    }

    private static TqlException unreachable(String member, String why) {
        return TqlException.builder(MEMBER_UNREACHABLE)
                .message("Member '" + member + "' did not answer the delegated call (" + why
                        + ") — a replace in progress, or a runtime that is down")
                .build();
    }

    private static String queryString(Op op, Map<String, Object> params) {
        StringBuilder query = new StringBuilder();
        for (String name : switch (op) {
            case AUDIT -> List.of("route", "actor", "status");
            default -> List.<String>of();
        }) {
            Object value = params.get(name);
            if (value != null) {
                query.append(query.isEmpty() ? "" : "&").append(name).append('=')
                        .append(encode(String.valueOf(value)));
            }
        }
        return query.toString();
    }

    /** The forwarded form body: the job-run values ride whole, everything else is the id. */
    private static String formBody(Op op, Map<String, Object> params) {
        if (op != Op.JOB_RUN) {
            return "";
        }
        StringBuilder body = new StringBuilder();
        if (params.get("id") != null) {
            body.append("id=").append(encode(str(params, "id")));
        }
        if (params.get("values") instanceof Map<?, ?> values) {
            values.forEach((key, value) -> {
                String name = String.valueOf(key);
                if ("id".equals(name)) {
                    return;
                }
                body.append(body.isEmpty() ? "" : "&").append(encode(name)).append('=')
                        .append(encode(value == null ? "" : String.valueOf(value)));
            });
        }
        return body.toString();
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value,
                java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String str(Map<String, Object> params, String name) {
        Object value = params.get(name);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }
}
