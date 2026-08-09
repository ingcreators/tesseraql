package io.tesseraql.docs;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The configuration index (docs/documentation-ia.md): every configuration key the framework
 * reads, scanned from the modules' main sources on every refresh and grouped by its leading
 * namespace.
 *
 * <p>Configuration keys were scattered across the prose of six pages with no page listing
 * them. This follows the {@link ErrorIndex} stance rather than a curated list: a key appears
 * because the code reads it, the reading file is the provenance, and a key no page discusses
 * still appears — an index that quietly omitted the undocumented ones would misreport how much
 * is documented.
 */
final class ConfigReference {

    /** {@code config.getString("tesseraql.x.y")} and the typed variants beside it. */
    private static final Pattern READ = Pattern.compile(
            "get(?:String|Boolean|Int|Integer|Long|List|Map|Duration)\\(\\s*\""
                    + "((?:tesseraql|server|db)\\.[A-Za-z0-9_.<>-]*)\"");

    private static final String BLOB = "https://github.com/ingcreators/tesseraql/blob/main/";

    /** One key with every file that reads it, and the pages that discuss it. */
    private record Key(TreeSet<String> sources, TreeSet<String> docs) {
    }

    private ConfigReference() {
    }

    /** Scans the repository and renders the whole page. */
    static String render(Path repoRoot) throws IOException {
        Map<String, Map<String, Key>> byNamespace = scan(repoRoot);
        mentionDocs(repoRoot.resolve("docs"), byNamespace);
        int total = byNamespace.values().stream().mapToInt(Map::size).sum();

        StringBuilder md = new StringBuilder();
        md.append("# Configuration reference\n\n")
                .append("All ").append(total).append(" configuration keys the framework reads, "
                        + "scanned from the module sources on every refresh and grouped by "
                        + "namespace. The reading files are the provenance, and where a page "
                        + "discusses a key, it is linked. A key no page discusses still appears "
                        + "— that is the point of an index.\n\n")
                .append("Keys are declared in `config/application.yml` and `config/tesseraql.yml`, "
                        + "overridden per environment by `config/env/<profile>.yml`, and readable "
                        + "in Studio's Config screen. Nesting in YAML and the dotted form here are "
                        + "the same thing: `tesseraql.studio.readOnly` is `tesseraql:` then "
                        + "`studio:` then `readOnly:`.\n");

        List<String> toc = new ArrayList<>();
        for (String namespace : byNamespace.keySet()) {
            toc.add("[`" + namespace + "`](#" + ReferenceGenerator.slug(namespace) + ")");
        }
        md.append('\n').append(String.join(" · ", toc)).append('\n');

        for (Map.Entry<String, Map<String, Key>> namespace : byNamespace.entrySet()) {
            md.append("\n## ").append(namespace.getKey()).append("\n\n")
                    .append("| Key | Read by | Documented in |\n| --- | --- | --- |\n");
            for (Map.Entry<String, Key> key : namespace.getValue().entrySet()) {
                md.append("| `").append(key.getKey()).append("` | ")
                        .append(links(key.getValue().sources())).append(" | ")
                        .append(key.getValue().docs().isEmpty()
                                ? "—"
                                : docLinks(key.getValue().docs()))
                        .append(" |\n");
            }
        }
        return md.toString();
    }

    /** Every module's {@code src/main/java}, the same trees the error index walks. */
    private static Map<String, Map<String, Key>> scan(Path repoRoot) throws IOException {
        Map<String, Map<String, Key>> byNamespace = new TreeMap<>();
        for (Path tree : sourceTrees(repoRoot)) {
            try (Stream<Path> files = Files.walk(tree)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String relative = repoRoot.relativize(file).toString().replace('\\', '/');
                    Matcher matcher = READ.matcher(Files.readString(file));
                    while (matcher.find()) {
                        collect(byNamespace, matcher.group(1), relative);
                    }
                }
            }
        }
        return byNamespace;
    }

    private static void collect(Map<String, Map<String, Key>> byNamespace, String key,
            String source) {
        // A prefix a lookup builds on (`tesseraql.apps.` + name) is not a key a reader sets.
        if (key.endsWith(".") || key.contains("<")) {
            return;
        }
        int firstDot = key.indexOf('.');
        int secondDot = key.indexOf('.', firstDot + 1);
        String namespace = secondDot < 0 ? key : key.substring(0, secondDot);
        byNamespace.computeIfAbsent(namespace, ignored -> new TreeMap<>())
                .computeIfAbsent(key, ignored -> new Key(new TreeSet<>(), new TreeSet<>()))
                .sources().add(source);
    }

    private static List<Path> sourceTrees(Path repoRoot) throws IOException {
        List<Path> trees = new ArrayList<>();
        try (DirectoryStream<Path> modules = Files.newDirectoryStream(repoRoot)) {
            for (Path module : modules) {
                // This module generates the index; the keys in its own comments and patterns
                // are examples, not keys the framework reads.
                if ("tesseraql-docs-reference".equals(module.getFileName().toString())) {
                    continue;
                }
                Path tree = module.resolve("src/main/java");
                if (Files.isDirectory(tree)) {
                    trees.add(tree);
                }
            }
        }
        trees.sort(null);
        return trees;
    }

    /** Which published page mentions each key, so the index can cite one. */
    private static void mentionDocs(Path docsDir, Map<String, Map<String, Key>> byNamespace)
            throws IOException {
        if (!Files.isDirectory(docsDir)) {
            return;
        }
        Map<String, Key> flat = new LinkedHashMap<>();
        byNamespace.values().forEach(flat::putAll);
        try (Stream<Path> pages = Files.list(docsDir)) {
            for (Path page : pages
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .filter(p -> !p.getFileName().toString().startsWith("reference-"))
                    .filter(p -> !ErrorIndex.isInternalDoc(p.getFileName().toString()))
                    .toList()) {
                String text = Files.readString(page);
                String name = page.getFileName().toString();
                for (Map.Entry<String, Key> key : flat.entrySet()) {
                    if (text.contains(key.getKey())) {
                        key.getValue().docs().add(name);
                    }
                }
            }
        }
    }

    private static String links(TreeSet<String> sources) {
        List<String> rendered = new ArrayList<>();
        for (String source : sources.stream().limit(3).toList()) {
            rendered.add("[" + source.substring(source.lastIndexOf('/') + 1) + "]("
                    + BLOB + source + ")");
        }
        if (sources.size() > 3) {
            rendered.add("+" + (sources.size() - 3) + " more");
        }
        return String.join(", ", rendered);
    }

    private static String docLinks(TreeSet<String> docs) {
        List<String> rendered = new ArrayList<>();
        for (String doc : docs.stream().limit(3).toList()) {
            rendered.add("[" + doc.replace(".md", "") + "](" + doc + ")");
        }
        if (docs.size() > 3) {
            rendered.add("+" + (docs.size() - 3) + " more");
        }
        return String.join(", ", rendered);
    }
}
