package io.tesseraql.docs;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The error-code index (docs/docs-site.md): every {@code TQL-<DOMAIN>-<n>} the framework
 * can raise, scanned from the modules' {@code src/main/java} trees — both the literal
 * string form and the {@code TqlDomain.<D>, <n>} constructor form, unioned — grouped by
 * domain with the raising files as provenance and links to the cookbook pages that
 * mention each code. Honest about coverage: an undocumented code still appears; that is
 * the point of an index.
 */
final class ErrorIndex {

    private static final Pattern LITERAL = Pattern.compile("TQL-([A-Z]+)-(\\d+)");
    private static final Pattern CONSTRUCTED = Pattern.compile("TqlDomain\\.([A-Z]+),\\s*(\\d+)");
    private static final String BLOB = "https://github.com/ingcreators/tesseraql/blob/main/";

    /** One error code with everywhere it appears; the sets keep themselves sorted. */
    record Code(TreeSet<String> sources, TreeSet<String> docs) {
    }

    private ErrorIndex() {
    }

    /** Scans the repository and renders the whole page. */
    static String render(Path repoRoot) throws IOException {
        Map<String, Map<Integer, Code>> byDomain = scan(repoRoot);
        mentionDocs(repoRoot.resolve("docs"), byDomain);
        int total = byDomain.values().stream().mapToInt(Map::size).sum();

        StringBuilder md = new StringBuilder();
        md.append("# Error code reference\n\n").append("All ").append(total)
                .append(" `TQL-*` codes, scanned from the framework sources on every "
                        + "refresh and grouped by domain, with the raising files as "
                        + "provenance. Where a cookbook page discusses a code, it is "
                        + "linked; an undocumented code still appears — that is the point "
                        + "of an index.\n\n");
        List<String> toc = new ArrayList<>();
        for (String domain : byDomain.keySet()) {
            toc.add("[" + domain + "](#" + ReferenceGenerator.slug(domain) + ")");
        }
        md.append(String.join(" · ", toc)).append('\n');
        for (Map.Entry<String, Map<Integer, Code>> domain : byDomain.entrySet()) {
            md.append("\n## ").append(domain.getKey()).append('\n')
                    .append("\n| Code | Raised in | Documented in |\n| --- | --- | --- |\n");
            for (Map.Entry<Integer, Code> code : domain.getValue().entrySet()) {
                md.append("| `TQL-").append(domain.getKey()).append('-')
                        .append(code.getKey()).append("` | ")
                        .append(sourceLinks(code.getValue().sources())).append(" | ")
                        .append(docLinks(code.getValue().docs())).append(" |\n");
            }
        }
        return md.toString();
    }

    /** Both scan patterns over every module's {@code src/main/java}, unioned. */
    static Map<String, Map<Integer, Code>> scan(Path repoRoot) throws IOException {
        Map<String, Map<Integer, Code>> byDomain = new TreeMap<>();
        for (Path tree : sourceTrees(repoRoot)) {
            try (Stream<Path> files = Files.walk(tree)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String source = Files.readString(file);
                    String rel = repoRoot.relativize(file).toString().replace('\\', '/');
                    collect(byDomain, LITERAL.matcher(source), rel);
                    collect(byDomain, CONSTRUCTED.matcher(source), rel);
                }
            }
        }
        return byDomain;
    }

    /**
     * The modules' main source trees only — walking from the repo root would drown in
     * {@code target/}, {@code node_modules/}, and the pnpm store.
     */
    private static List<Path> sourceTrees(Path repoRoot) throws IOException {
        List<Path> trees = new ArrayList<>();
        try (DirectoryStream<Path> modules = Files.newDirectoryStream(repoRoot)) {
            for (Path module : modules) {
                Path tree = module.resolve("src/main/java");
                if (Files.isDirectory(tree)) {
                    trees.add(tree);
                }
            }
        }
        trees.sort(null);
        return trees;
    }

    private static void collect(Map<String, Map<Integer, Code>> byDomain, Matcher matcher,
            String rel) {
        while (matcher.find()) {
            byDomain.computeIfAbsent(matcher.group(1), domain -> new TreeMap<>())
                    .computeIfAbsent(Integer.parseInt(matcher.group(2)),
                            number -> new Code(new TreeSet<>(), new TreeSet<>()))
                    .sources().add(rel);
        }
    }

    /**
     * Marks each code with the cookbook pages whose markdown mentions it — every
     * {@code docs/*.md} except the generated reference pages themselves, which would
     * otherwise "document" every code they index.
     */
    private static void mentionDocs(Path docsDir, Map<String, Map<Integer, Code>> byDomain)
            throws IOException {
        List<Path> pages;
        try (Stream<Path> docs = Files.list(docsDir)) {
            pages = docs
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .filter(p -> !p.getFileName().toString().startsWith("reference-"))
                    .sorted()
                    .toList();
        }
        for (Path page : pages) {
            String name = page.getFileName().toString();
            String base = name.substring(0, name.length() - ".md".length());
            Matcher matcher = LITERAL.matcher(Files.readString(page));
            while (matcher.find()) {
                Map<Integer, Code> domain = byDomain.get(matcher.group(1));
                Code code = domain == null
                        ? null
                        : domain.get(Integer.parseInt(matcher.group(2)));
                if (code != null) {
                    code.docs().add("[" + base + "](" + name + ")");
                }
            }
        }
    }

    /** File basenames linked to the GitHub blob, capped at three with an honest remainder. */
    private static String sourceLinks(TreeSet<String> sources) {
        List<String> links = new ArrayList<>();
        for (String rel : sources.stream().limit(3).toList()) {
            links.add("[" + rel.substring(rel.lastIndexOf('/') + 1) + "](" + BLOB + rel + ")");
        }
        if (sources.size() > 3) {
            links.add("+" + (sources.size() - 3) + " more");
        }
        return String.join(", ", links);
    }

    private static String docLinks(TreeSet<String> docs) {
        return docs.isEmpty() ? "—" : String.join(", ", docs);
    }
}
