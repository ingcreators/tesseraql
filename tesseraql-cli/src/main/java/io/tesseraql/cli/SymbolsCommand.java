package io.tesseraql.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tesseraql.yaml.i18n.MessageCatalog;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestLoader;
import io.tesseraql.yaml.manifest.RouteFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code tesseraql symbols --app <dir>}: prints what the framework declares — security policies,
 * default-locale message keys, and routes, each with its source and line — as one JSON object on
 * stdout. The editor language layer (docs/vscode-extension.md, Phase 56) consumes it for
 * completion and go-to-definition; like every editor contract, the document is sorted and
 * deterministic.
 */
@Command(name = "symbols", description = "Print the app's declared symbols (policies, message keys, routes) as JSON.")
final class SymbolsCommand implements Callable<Integer> {

    @Option(names = {"--app"}, required = true, description = "Path to the external app home.")
    Path app;

    @Override
    public Integer call() throws Exception {
        Path home = app.toAbsolutePath().normalize();
        AppManifest manifest = new ManifestLoader().load(home);
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode document = mapper.createObjectNode();
        policies(document.putArray("policies"), manifest, home);
        messages(document.putArray("messages"), manifest, home);
        routes(document.putArray("routes"), manifest, home);
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
