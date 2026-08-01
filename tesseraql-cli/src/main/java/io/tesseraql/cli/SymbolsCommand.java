package io.tesseraql.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tesseraql.yaml.SimpleYamlParser;
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
import picocli.CommandLine.Option;

/**
 * {@code tesseraql symbols --app <dir>}: prints what the framework declares — security policies,
 * default-locale message keys, shared field domains, validation rules, decision tables, routes,
 * and workflows (with their transition and dispatch ids), each with its source and line — as one
 * JSON object on stdout. The editor language layer
 * (docs/vscode-extension.md, Phase 56) consumes it for completion and go-to-definition; like
 * every editor contract, the document is sorted and deterministic.
 */
@Command(name = "symbols", description = "Print the app's declared symbols (policies, message keys, domains, rules, decisions, routes, workflows) as JSON.")
final class SymbolsCommand implements Callable<Integer> {

    @Option(names = {"--app"}, required = true, description = "Path to the external app home.")
    Path app;

    @Override
    public Integer call() throws Exception {
        Path home = app.toAbsolutePath().normalize();
        AppManifest manifest = new ManifestLoader().load(home);
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode document = mapper.createObjectNode();
        SimpleYamlParser parser = new SimpleYamlParser();
        policies(document.putArray("policies"), manifest, home);
        messages(document.putArray("messages"), manifest, home);
        sharedDefinitions(document.putArray("domains"), home, "domains",
                file -> parser.parseDomains(file).domains().keySet());
        sharedDefinitions(document.putArray("rules"), home, "rules",
                file -> parser.parseRuleSets(file).rules().keySet());
        sharedDefinitions(document.putArray("decisions"), home, "decisions",
                file -> parser.parseDecisions(file).decisions().keySet());
        sharedDefinitions(document.putArray("calendars"), home, "calendars",
                file -> parser.parseCalendars(file).calendars().keySet());
        routes(document.putArray("routes"), manifest, home);
        workflows(document.putArray("workflows"), manifest, home);
        jobs(document.putArray("jobs"), manifest, home);
        System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(document));
        return 0;
    }

    private static void policies(ArrayNode into, AppManifest manifest, Path home)
            throws IOException {
        if (!(manifest.config()
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

    private static void messages(ArrayNode into, AppManifest manifest, Path home)
            throws IOException {
        String tag = manifest.config().getString("i18n.defaultLocale").orElse("en");
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
            Function<Path, Collection<String>> namesOf) throws IOException {
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
            for (String name : namesOf.apply(file)) {
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

    private static void routes(ArrayNode into, AppManifest manifest, Path home) {
        List<RouteFile> routes = new ArrayList<>(manifest.routes());
        routes.sort(Comparator.comparing(route -> String.valueOf(route.definition().id())));
        for (RouteFile route : routes) {
            ObjectNode entry = into.addObject();
            entry.put("id", route.definition().id());
            entry.put("source", home.relativize(route.source()).toString().replace('\\', '/'));
            entry.put("method", route.httpMethod());
            entry.put("path", route.urlPath());
            entry.put("recipe", route.definition().recipe());
        }
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
            entry.put("trigger", triggerStory(job.definition().trigger()));
        }
    }

    /** The one-line trigger story: how a job starts, calendar qualifiers included. */
    private static String triggerStory(io.tesseraql.yaml.model.TriggerSpec trigger) {
        if (trigger == null) {
            return "on demand";
        }
        if (trigger.after() != null && !trigger.after().isBlank()) {
            return "after " + trigger.after();
        }
        if (trigger.poll() != null) {
            return "poll " + trigger.poll().effectiveSource();
        }
        io.tesseraql.yaml.model.TriggerSpec.Schedule schedule = trigger.schedule();
        if (schedule == null) {
            return "on demand";
        }
        StringBuilder story = new StringBuilder();
        if (schedule.cron() != null && !schedule.cron().isBlank()) {
            story.append("cron ").append(schedule.cron());
        } else if (schedule.fixedDelay() != null && !schedule.fixedDelay().isBlank()) {
            story.append("every ").append(schedule.fixedDelay());
        } else {
            story.append("on demand");
        }
        if (schedule.calendar() != null && !schedule.calendar().isBlank()) {
            story.append(", calendar ").append(schedule.calendar());
            if (schedule.dayOfMonth() != null) {
                story.append(" (day ").append(schedule.dayOfMonth()).append(")");
            } else if (schedule.runOn() != null) {
                story.append(" (").append(schedule.runOn()).append(")");
            }
        }
        return story.toString();
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
