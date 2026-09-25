package io.tesseraql.cli;

import io.tesseraql.yaml.SimpleYamlParser;
import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.i18n.MessageCatalog;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestLoader;
import io.tesseraql.yaml.manifest.RouteFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.stream.Stream;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code tesseraql symbols --app <dir>}: prints what the framework declares — security policies,
 * default-locale message keys, shared field domains, validation rules, decision tables, routes
 * (each with the named sources it declares and the views it binds), and workflows (with their
 * transition and dispatch ids), each with its source and line — as one JSON object on stdout.
 * The editor language layer (docs/vscode-extension.md, Phase 56) consumes it for completion and
 * go-to-definition; like every editor contract, the document is sorted and deterministic.
 *
 * <p>A document that does not parse is <em>skipped and reported</em>, not fatal: the load is the
 * tolerant one the hot reloader uses, and each shared-definition file is parsed inside its own
 * try. Editor intelligence exists to help while an app is mid-edit, so the one moment a document
 * is broken must not be the moment every completion in the app goes quiet. The skipped files are
 * listed in {@code broken[]} (and on stderr) so the silence is explained rather than mysterious.
 */
@Command(name = "symbols", description = "Print the app's declared symbols (policies, message keys, domains, rules, decisions, routes, workflows) as JSON.")
final class SymbolsCommand implements Callable<Integer> {

    @Option(names = {"--app"}, required = true, description = "Path to the external app home.")
    Path app;

    @Mixin
    ConfigOptions configOptions;

    /** One document that could not be parsed, so its symbols are missing from this run. */
    private record Broken(String source, String error) {
    }

    @Override
    public Integer call() throws Exception {
        configOptions.apply();
        Path home = app.toAbsolutePath().normalize();
        List<Broken> broken = new ArrayList<>();
        // The tolerant load the hot reloader and the linter use: a route, job, consumer,
        // workflow or MCP document that does not parse costs its own symbols, not the run, and
        // is named here. A failure in what every document resolves through (the configuration,
        // a shared definition) still aborts the whole load, so it degrades one step further —
        // config alone still yields policies and message keys, and the walks below are this
        // command's own. Editor intelligence must survive the app being mid-edit.
        List<ManifestLoader.BrokenDocument> brokenDocuments = new ArrayList<>();
        AppManifest manifest;
        AppConfig config;
        try {
            manifest = new ManifestLoader().load(home, brokenDocuments);
            config = manifest.config();
        } catch (RuntimeException ex) {
            manifest = null;
            config = ManifestLoader.configOnly(home);
            broken.add(new Broken("(app manifest)", rootMessage(ex)));
        }
        for (ManifestLoader.BrokenDocument document : brokenDocuments) {
            broken.add(new Broken(relative(home, document.source()), document.error()));
        }
        ObjectMapper mapper = io.tesseraql.yaml.JsonMappers.constrained();
        ObjectNode document = mapper.createObjectNode();
        SimpleYamlParser parser = new SimpleYamlParser();
        policies(document.putArray("policies"), config, home);
        messages(document.putArray("messages"), config, home);
        sharedDefinitions(document.putArray("domains"), home, "domains",
                file -> parser.parseDomains(file).domains().keySet(), broken);
        sharedDefinitions(document.putArray("rules"), home, "rules",
                file -> parser.parseRuleSets(file).rules().keySet(), broken);
        sharedDefinitions(document.putArray("decisions"), home, "decisions",
                file -> parser.parseDecisions(file).decisions().keySet(), broken);
        sharedDefinitions(document.putArray("calendars"), home, "calendars",
                file -> parser.parseCalendars(file).calendars().keySet(), broken);
        sharedDefinitions(document.putArray("catalogs"), home, "catalogs",
                file -> parser.parseCatalogs(file).keySet(), broken);
        ArrayNode routes = document.putArray("routes");
        ArrayNode workflows = document.putArray("workflows");
        ArrayNode jobs = document.putArray("jobs");
        // The manifest-derived arrays stay present but empty when the load could not complete —
        // the contract's shape never depends on the app's health.
        if (manifest != null) {
            routes(routes, manifest, home);
            workflows(workflows, manifest, home);
            jobs(jobs, manifest, home);
        }
        broken(document.putArray("broken"), broken);
        System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(document));
        return 0;
    }

    /**
     * The skipped documents, sorted by source so the contract stays deterministic, and echoed to
     * stderr — stdout carries the JSON contract alone, but a human running the command still has
     * to be told which files were left out of it.
     */
    private static void broken(ArrayNode into, List<Broken> broken) {
        broken.sort(Comparator.comparing(Broken::source));
        for (Broken document : broken) {
            ObjectNode entry = into.addObject();
            entry.put("source", document.source());
            entry.put("error", document.error());
            System.err.println("symbols: skipped " + document.source() + ": " + document.error());
        }
    }

    private static String relative(Path home, Path file) {
        return home.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    /** The innermost cause's message — what the parser actually objected to. */
    private static String rootMessage(Throwable ex) {
        Throwable root = ex;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.toString() : root.getMessage();
    }

    private static void policies(ArrayNode into, AppConfig config, Path home)
            throws IOException {
        if (!(config
                .navigate("tesseraql.security.policies") instanceof Map<?, ?> declared)) {
            return;
        }
        String source = "config/tesseraql.yml";
        List<String> lines = readLines(home.resolve(source));
        List<String> names = new ArrayList<>();
        declared.keySet().forEach(name -> names.add(String.valueOf(name)));
        names.sort(Comparator.naturalOrder());
        for (String name : names) {
            ObjectNode policy = into.addObject();
            policy.put("name", name);
            policy.put("source", source);
            policy.put("line", firstKeyLine(lines, name));
        }
    }

    private static void messages(ArrayNode into, AppConfig config, Path home)
            throws IOException {
        String tag = config.getString("i18n.defaultLocale").orElse("en");
        MessageCatalog catalog = MessageCatalog.load(home.resolve("messages"));
        Map<String, String> entries = new TreeMap<>(catalog.entries(tag));
        if (entries.isEmpty()) {
            return;
        }
        String source = "messages/" + tag + ".yml";
        Map<String, Integer> lines = dottedKeyLines(readLines(home.resolve(source)));
        for (String key : entries.keySet()) {
            ObjectNode message = into.addObject();
            message.put("key", key);
            message.put("source", source);
            message.put("line", lines.getOrDefault(key, 0) == 0 ? null : lines.get(key));
        }
    }

    /**
     * The shared-definition namespaces — field domains and validation rules — declare names under
     * a top-level key matching their directory ({@code domains/*.yml} → {@code domains:},
     * {@code rules/*.yml} → {@code rules:}, docs/field-domains.md and
     * docs/validation-rule-sets.md), so one walk covers both: every declared name with the file
     * that declares it and its 1-based line.
     */
    private static void sharedDefinitions(ArrayNode into, Path home, String kind,
            Function<Path, Collection<String>> namesOf, List<Broken> broken) throws IOException {
        Path dir = home.resolve(kind);
        if (!Files.isDirectory(dir)) {
            return;
        }
        record Declared(String name, String source, Integer line) {
        }
        List<Declared> declared = new ArrayList<>();
        List<Path> files;
        try (Stream<Path> listed = Files.list(dir)) {
            files = listed.filter(file -> file.getFileName().toString().endsWith(".yml"))
                    .sorted()
                    .toList();
        }
        for (Path file : files) {
            String source = kind + "/" + file.getFileName();
            Map<String, Integer> lines = dottedKeyLines(readLines(file));
            Collection<String> names;
            try {
                names = namesOf.apply(file);
            } catch (RuntimeException ex) {
                // One unparseable domains/rules/decisions/calendars document must cost only its
                // own names, not every symbol in the app.
                broken.add(new Broken(source, rootMessage(ex)));
                continue;
            }
            for (String name : names) {
                Integer line = lines.get(kind + "." + name);
                declared.add(new Declared(name, source, line));
            }
        }
        declared.sort(Comparator.comparing(Declared::name));
        for (Declared definition : declared) {
            ObjectNode entry = into.addObject();
            entry.put("name", definition.name());
            entry.put("source", definition.source());
            entry.put("line", definition.line());
        }
    }

    /**
     * The mounted routes, each with the named sources it declares (docs/unified-sources.md) in
     * authored order and the view documents it binds — so a view's {@code source:} can be
     * navigated to the {@code sources.<name>:} line of the route that binds the view
     * (docs/editor-named-sources.md). The bound documents' embedded views ({@code type: view}
     * panels, {@code view:} children — docs/view-composition.md) ride along as {@code embeds}:
     * an embedded view reads the <em>host</em> route's sources, and the manifest is what knows
     * which routes host it — an editor that guessed from a {@code view:} scalar would take a
     * route's own binding for an embedding. A source's line comes from the same indentation
     * walk that positions message keys; a name that does not start its own line (a one-line
     * flow map, a single-quoted key) yields none.
     */
    private static void routes(ArrayNode into, AppManifest manifest, Path home)
            throws IOException {
        List<RouteFile> routes = new ArrayList<>(manifest.routes());
        routes.sort(Comparator.comparing(route -> String.valueOf(route.definition().id())));
        for (RouteFile route : routes) {
            io.tesseraql.yaml.model.RouteDefinition definition = route.definition();
            ObjectNode entry = into.addObject();
            entry.put("id", definition.id());
            entry.put("source", home.relativize(route.source()).toString().replace('\\', '/'));
            entry.put("method", route.httpMethod());
            entry.put("path", route.urlPath());
            entry.put("recipe", definition.recipe());
            ArrayNode sources = entry.putArray("sources");
            Map<String, Integer> lines = definition.sources().isEmpty()
                    ? Map.of()
                    : dottedKeyLines(readLines(route.source()));
            for (Map.Entry<String, io.tesseraql.yaml.model.Binding> source : definition.sources()
                    .entrySet()) {
                ObjectNode declared = sources.addObject();
                declared.put("name", source.getKey());
                Integer line = lines.get("sources." + source.getKey());
                declared.put("line", line == null || line == 0 ? null : line);
                declared.put("arm", arm(source.getValue()));
                declared.put("file", source.getValue().isSql() ? source.getValue().file() : null);
            }
            io.tesseraql.yaml.model.ResponseSpec.HtmlResponse html = definition.response() == null
                    ? null
                    : definition.response().html();
            entry.put("view", html == null ? null : html.view());
            ArrayNode views = entry.putArray("views");
            ArrayNode embeds = entry.putArray("embeds");
            if (html != null) {
                for (String bound : html.views()) {
                    views.add(bound);
                }
                for (String embedded : embeddedViews(manifest, html)) {
                    embeds.add(embedded);
                }
            }
        }
    }

    /**
     * The ids the route's bound documents embed — the {@code view:} document's and each
     * {@code views:} part's {@code type: view} panels and {@code view:} children — in authored
     * order, once each. A bound id the registry does not hold is the lint's finding and embeds
     * nothing here.
     */
    private static List<String> embeddedViews(AppManifest manifest,
            io.tesseraql.yaml.model.ResponseSpec.HtmlResponse html) {
        List<String> bound = new ArrayList<>();
        if (html.view() != null) {
            bound.add(html.view());
        }
        bound.addAll(html.views());
        java.util.LinkedHashSet<String> embedded = new java.util.LinkedHashSet<>();
        for (String id : bound) {
            io.tesseraql.yaml.manifest.ViewFile view = manifest.viewById(id);
            if (view == null) {
                continue;
            }
            for (io.tesseraql.yaml.view.ViewSpec.Child child : view.spec().children()) {
                if (child.view() != null) {
                    embedded.add(child.view());
                }
            }
            for (io.tesseraql.yaml.view.ViewSpec.Panel panel : view.spec().panels()) {
                if (panel.view() != null) {
                    embedded.add(panel.view());
                }
            }
        }
        return List.copyOf(embedded);
    }

    /** The one mechanism a source declares, by the arm it carries. */
    private static String arm(io.tesseraql.yaml.model.Binding binding) {
        if (binding.isSql()) {
            return "sql";
        }
        if (binding.isContract()) {
            return "contract";
        }
        if (binding.isService()) {
            return "service";
        }
        if (binding.declaresHttp()) {
            return "http";
        }
        if (binding.isSequence()) {
            return "sequence";
        }
        return binding.isSpool() ? "spool" : null;
    }

    /**
     * The declared workflows (docs/approval-workflow.md), each with its transition and dispatch
     * ids — the suite targets ({@code transition:}/{@code dispatch:}) name them, so the editor
     * completes {@code workflow:} values and navigates to the declaring file.
     */
    private static void workflows(ArrayNode into, AppManifest manifest, Path home)
            throws IOException {
        List<io.tesseraql.yaml.manifest.WorkflowFile> workflows = new ArrayList<>(
                manifest.workflows());
        workflows.sort(Comparator.comparing(workflow -> String.valueOf(
                workflow.definition().id())));
        for (io.tesseraql.yaml.manifest.WorkflowFile workflow : workflows) {
            io.tesseraql.yaml.model.WorkflowDefinition def = workflow.definition();
            ObjectNode entry = into.addObject();
            entry.put("id", def.id());
            entry.put("source",
                    home.relativize(workflow.source()).toString().replace('\\', '/'));
            int line = firstKeyLine(readLines(workflow.source()), "id");
            entry.put("line", line == 0 ? null : line);
            ArrayNode transitions = entry.putArray("transitions");
            for (io.tesseraql.yaml.model.TransitionSpec transition : def.transitions()) {
                if (transition.id() != null) {
                    transitions.add(transition.id());
                }
            }
            ArrayNode dispatches = entry.putArray("dispatches");
            for (io.tesseraql.yaml.model.DispatchSpec dispatch : def.dispatch()) {
                if (dispatch.id() != null) {
                    dispatches.add(dispatch.id());
                }
            }
        }
    }

    /**
     * The declared batch jobs (docs/jobs.md), each with a one-line trigger story — the editor
     * completes {@code after:} values, navigates to the declaring file, and the explorer's
     * Jobs section can say how each job starts without opening it.
     */
    private static void jobs(ArrayNode into, AppManifest manifest, Path home)
            throws IOException {
        List<io.tesseraql.yaml.manifest.JobFile> jobs = new ArrayList<>(manifest.jobs());
        jobs.sort(Comparator.comparing(job -> String.valueOf(job.definition().id())));
        for (io.tesseraql.yaml.manifest.JobFile job : jobs) {
            ObjectNode entry = into.addObject();
            entry.put("id", job.definition().id());
            entry.put("source", home.relativize(job.source()).toString().replace('\\', '/'));
            int line = firstKeyLine(readLines(job.source()), "id");
            entry.put("line", line == 0 ? null : line);
            entry.put("trigger",
                    io.tesseraql.yaml.model.TriggerSpec.describe(job.definition().trigger()));
        }
    }

    private static List<String> readLines(Path file) throws IOException {
        return Files.isRegularFile(file) ? Files.readAllLines(file) : List.of();
    }

    /** The 1-based first line whose trimmed text declares {@code name:}, or 0. */
    private static int firstKeyLine(List<String> lines, String name) {
        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).strip();
            if (trimmed.startsWith(name + ":")) {
                return i + 1;
            }
        }
        return 0;
    }

    /**
     * Maps every flattened dotted key of a nested-map YAML document to its 1-based leaf line, by
     * indentation: a key line pops shallower-or-equal ancestors, so the stack always spells the
     * current dotted path — the same flattening {@link MessageCatalog} performs on the values.
     */
    private static Map<String, Integer> dottedKeyLines(List<String> lines) {
        Map<String, Integer> byKey = new TreeMap<>();
        Deque<int[]> indents = new ArrayDeque<>();
        Deque<String> names = new ArrayDeque<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains(":")) {
                continue;
            }
            int indent = line.length() - line.stripLeading().length();
            String name = trimmed.substring(0, trimmed.indexOf(':')).strip();
            if (name.length() >= 2 && name.startsWith("\"") && name.endsWith("\"")) {
                name = name.substring(1, name.length() - 1);
            }
            while (!indents.isEmpty() && indents.peek()[0] >= indent) {
                indents.pop();
                names.removeLast();
            }
            indents.push(new int[]{indent});
            names.addLast(name);
            byKey.put(String.join(".", names), i + 1);
        }
        return byKey;
    }
}
