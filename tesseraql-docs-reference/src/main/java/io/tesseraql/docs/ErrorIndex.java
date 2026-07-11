package io.tesseraql.docs;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The error-code index (docs/docs-site.md): every {@code TQL-<DOMAIN>-<n>} the framework
 * can raise, scanned from the modules' {@code src/main/java} trees — both the literal
 * string form and the {@code TqlDomain.<D>, <n>} constructor form, unioned — grouped by
 * domain with a best-effort meaning extracted from each raise site (the message's
 * string-literal fragments, dynamic parts shown as {@code …}, or the javadoc above an
 * error-code constant), the raising files as provenance, and links to the cookbook pages
 * that mention each code. Honest about coverage: an undocumented code still appears;
 * that is the point of an index.
 */
final class ErrorIndex {

    // The trailing guard keeps wildcard mentions like "TQL-ADM-47xx" out of the index.
    private static final Pattern LITERAL = Pattern.compile("TQL-([A-Z]+)-(\\d+)(?![0-9A-Za-z])");
    private static final Pattern CONSTRUCTED = Pattern.compile("TqlDomain\\.([A-Z]+),\\s*(\\d+)");
    private static final String BLOB = "https://github.com/ingcreators/tesseraql/blob/main/";

    /** One error code with everywhere it appears; the sets keep themselves sorted. */
    record Code(TreeSet<String> sources, TreeSet<String> docs, TreeSet<String> messages) {
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
                        + "refresh and grouped by domain. The meaning is extracted from "
                        + "the raising site (its message text, with dynamic parts shown "
                        + "as `…`), the raising files are the provenance, and where a "
                        + "cookbook page discusses a code, it is linked. An undocumented "
                        + "code still appears — that is the point of an index.\n\n");
        List<String> toc = new ArrayList<>();
        for (String domain : byDomain.keySet()) {
            toc.add("[" + domain + "](#" + ReferenceGenerator.slug(domain) + ")");
        }
        md.append(String.join(" · ", toc)).append('\n');
        for (Map.Entry<String, Map<Integer, Code>> domain : byDomain.entrySet()) {
            md.append("\n## ").append(domain.getKey()).append('\n')
                    .append("\n| Code | Meaning | Documented in | Raised in |\n"
                            + "| --- | --- | --- | --- |\n");
            for (Map.Entry<Integer, Code> code : domain.getValue().entrySet()) {
                md.append("| `TQL-").append(domain.getKey()).append('-')
                        .append(code.getKey()).append("` | ")
                        .append(meaningCell(code.getValue().messages())).append(" | ")
                        .append(docLinks(code.getValue().docs())).append(" | ")
                        .append(sourceLinks(code.getValue().sources())).append(" |\n");
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
                    Lexed lexed = lex(source);
                    collect(byDomain, LITERAL.matcher(source), rel, source, lexed);
                    collect(byDomain, CONSTRUCTED.matcher(source), rel, source, lexed);
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
            String rel, String source, Lexed lexed) {
        while (matcher.find()) {
            Code code = byDomain.computeIfAbsent(matcher.group(1), domain -> new TreeMap<>())
                    .computeIfAbsent(Integer.parseInt(matcher.group(2)),
                            number -> new Code(new TreeSet<>(), new TreeSet<>(), new TreeSet<>()));
            code.sources().add(rel);
            meaningAt(source, lexed, matcher.start(), matcher.end())
                    .ifPresent(code.messages()::add);
        }
    }

    /**
     * Best-effort meaning of one raise site: the message's string-literal fragments from
     * the surrounding statement (dynamic parts shown as {@code …}), falling back to the
     * javadoc directly above an error-code constant declaration. Empty when the site
     * offers neither — the index shows those honestly as unexplained.
     */
    private static java.util.Optional<String> meaningAt(String source, Lexed lexed, int start,
            int end) {
        if (quotedInProse(source, lexed, start, end) || lexed.insideComment(start, end)) {
            // A code embedded in some longer string (a neighbor's message or a rendered
            // template naming it) or mentioned in passing in a comment: extracting the
            // surrounding text would bleed a neighbor's message onto this code. Its own
            // raise site — where the literal IS the code — explains it; curated constant
            // javadoc is found there too.
            return java.util.Optional.empty();
        }
        java.util.Optional<String> meaning = messageFromStatement(source, lexed, start, end);
        if (meaning.isEmpty()) {
            meaning = javadocAbove(source, start);
        }
        if (meaning.isEmpty()) {
            meaning = constantMeaning(source, lexed, start, end);
        }
        // A meaning that opens by naming its own code just repeats the Code column; a
        // markup or JSON template is not prose.
        return meaning.map(text -> text.replaceFirst("^TQL-[A-Z]+-\\d+:?\\s*", "").trim())
                .filter(text -> !text.isEmpty())
                .filter(text -> !text.startsWith("{") && !text.startsWith("<"));
    }

    /**
     * True when the match sits inside a string literal whose content is more than the
     * code itself — i.e. the code is quoted in someone else's prose or template, not
     * being raised. A literal that IS the bare code (the usual lint-finding argument)
     * stays a raise site.
     */
    private static boolean quotedInProse(String source, Lexed lexed, int start, int end) {
        for (int[] span : lexed.literals()) {
            if (span[0] <= start && end <= span[1]) {
                return !source.substring(span[0], span[1]).trim()
                        .equals(source.substring(start, end));
            }
        }
        return false;
    }

    private static final Pattern CONSTANT = Pattern
            .compile("TqlErrorCode\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=");

    /**
     * For an undocumented {@code TqlErrorCode NAME = new TqlErrorCode(…)} constant: the
     * message literal at one of the constant's use sites in the same file, else the
     * constant's own name read as prose ({@code UNKNOWN_FORMAT} → "unknown format").
     */
    private static java.util.Optional<String> constantMeaning(String source, Lexed lexed,
            int matchStart, int matchEnd) {
        int declFrom = Math.max(0, matchStart - 200);
        Matcher decl = CONSTANT.matcher(source.substring(declFrom, matchStart));
        String name = null;
        while (decl.find()) {
            name = decl.group(1);
        }
        if (name == null) {
            return java.util.Optional.empty();
        }
        for (int i = source.indexOf(name); i >= 0; i = source.indexOf(name, i + 1)) {
            if (i >= declFrom && i <= matchEnd) {
                continue;
            }
            char before = i == 0 ? ' ' : source.charAt(i - 1);
            int afterAt = i + name.length();
            char after = afterAt >= source.length() ? ' ' : source.charAt(afterAt);
            if (Character.isJavaIdentifierPart(before)
                    || Character.isJavaIdentifierPart(after)) {
                continue;
            }
            java.util.Optional<String> atUse = messageFromStatement(source, lexed, i, afterAt);
            if (atUse.isPresent()) {
                return atUse;
            }
        }
        return name.length() < 4 || !name.contains("_")
                ? java.util.Optional.empty()
                : java.util.Optional
                        .of(name.toLowerCase(java.util.Locale.ROOT).replace('_', ' '));
    }

    /**
     * Lexical facts about one source file, computed once and reused for every match in
     * it: string-literal content spans, and the positions of {@code ; { }} outside
     * literals and comments. Starting from the top of the file keeps the quote parity
     * honest — a fixed-size window can open mid-literal and read code as text.
     */
    record Lexed(List<int[]> literals, List<Integer> boundaries, List<int[]> comments) {

        /** Whether the span [start, end) lies inside a string-literal content span. */
        boolean insideLiteral(int start, int end) {
            return covers(literals, start, end);
        }

        /** Whether the span [start, end) lies inside a comment. */
        boolean insideComment(int start, int end) {
            return covers(comments, start, end);
        }

        private static boolean covers(List<int[]> spans, int start, int end) {
            for (int[] span : spans) {
                if (span[0] <= start && end <= span[1]) {
                    return true;
                }
            }
            return false;
        }
    }

    static Lexed lex(String source) {
        List<int[]> literals = new ArrayList<>();
        List<Integer> boundaries = new ArrayList<>();
        List<int[]> comments = new ArrayList<>();
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                int eol = source.indexOf('\n', i);
                comments.add(new int[]{i, eol < 0 ? n : eol});
                i = eol < 0 ? n : eol + 1;
            } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                int close = source.indexOf("*/", i + 2);
                comments.add(new int[]{i, close < 0 ? n : close + 2});
                i = close < 0 ? n : close + 2;
            } else if (source.startsWith("\"\"\"", i)) {
                int close = source.indexOf("\"\"\"", i + 3);
                literals.add(new int[]{i + 3, close < 0 ? n : close});
                i = close < 0 ? n : close + 3;
            } else if (c == '"') {
                int content = ++i;
                while (i < n && source.charAt(i) != '"') {
                    i += source.charAt(i) == '\\' ? 2 : 1;
                }
                literals.add(new int[]{content, Math.min(i, n)});
                i++;
            } else if (c == '\'') {
                i++;
                while (i < n && source.charAt(i) != '\'') {
                    i += source.charAt(i) == '\\' ? 2 : 1;
                }
                i++;
            } else {
                if (c == ';' || c == '{' || c == '}') {
                    boundaries.add(i);
                }
                i++;
            }
        }
        return new Lexed(literals, boundaries, comments);
    }

    /**
     * Extracts the message from the statement around a match: bounds the statement at
     * the nearest {@code ; { }} outside literals and comments, merges
     * {@code +}-concatenated literal runs with {@code …} standing in for the dynamic
     * expressions, and picks the longest prose-looking candidate.
     */
    private static java.util.Optional<String> messageFromStatement(String source, Lexed lexed,
            int start, int end) {
        int stmtFrom = 0;
        int stmtTo = source.length();
        for (int b : lexed.boundaries()) {
            if (b < start) {
                stmtFrom = b + 1;
            } else if (b >= end) {
                stmtTo = b;
                break;
            }
        }
        List<String> candidates = new ArrayList<>();
        StringBuilder current = null;
        int chainFrom = -1;
        int previousEnd = -1;
        for (int[] lit : lexed.literals()) {
            if (lit[0] < stmtFrom || lit[1] > stmtTo) {
                continue;
            }
            String text = source.substring(lit[0], lit[1]).replace("\\\"", "\"")
                    .replace("\\n", " ").replace("\\t", " ");
            boolean concatenated = current != null && previousEnd + 1 <= lit[0] - 1
                    && isConcatenationGap(source.substring(previousEnd + 1, lit[0] - 1));
            if (concatenated) {
                current.append('…').append(text);
            } else {
                if (current != null) {
                    candidates.add(openEnded(source, current, chainFrom, previousEnd));
                }
                current = new StringBuilder(text);
                chainFrom = lit[0];
            }
            previousEnd = lit[1];
        }
        if (current != null) {
            candidates.add(openEnded(source, current, chainFrom, previousEnd));
        }
        return candidates.stream()
                .map(text -> text.replaceAll("\\s+", " ").trim())
                .filter(text -> text.length() >= 12 && text.contains(" "))
                .filter(text -> !text.startsWith("TQL-"))
                .max(java.util.Comparator.comparingInt(String::length));
    }

    /**
     * A chain whose concatenation continues beyond its literals ("Failed to parse: " +
     * cause) gets the dynamic tail (or head) shown as {@code …} too.
     */
    private static String openEnded(String source, StringBuilder chain, int chainFrom,
            int chainEnd) {
        if (nextMeaningfulChar(source, chainEnd + 1) == '+') {
            chain.append('…');
        }
        if (previousMeaningfulChar(source, chainFrom - 2) == '+') {
            chain.insert(0, '…');
        }
        return chain.toString();
    }

    private static char nextMeaningfulChar(String source, int from) {
        for (int i = from; i < Math.min(source.length(), from + 40); i++) {
            if (!Character.isWhitespace(source.charAt(i))) {
                return source.charAt(i);
            }
        }
        return ' ';
    }

    private static char previousMeaningfulChar(String source, int from) {
        for (int i = from; i >= Math.max(0, from - 40); i--) {
            if (!Character.isWhitespace(source.charAt(i))) {
                return source.charAt(i);
            }
        }
        return ' ';
    }

    /** A gap between two literals joins them only when it is a bare {@code +} chain. */
    private static boolean isConcatenationGap(String gap) {
        if (!gap.contains("+")) {
            return false;
        }
        int depth = 0;
        for (int i = 0; i < gap.length(); i++) {
            char c = gap.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth <= 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * The javadoc directly above a declaration (only whitespace and declaration tokens —
     * no statement boundary — between {@code *&#47;} and the match), flattened to one
     * line. Covers the {@code TqlErrorCode} constant idiom, where the meaning lives in
     * the constant's doc rather than a message argument.
     */
    private static java.util.Optional<String> javadocAbove(String source, int matchStart) {
        int close = source.lastIndexOf("*/", matchStart);
        if (close < 0) {
            return java.util.Optional.empty();
        }
        String between = source.substring(close + 2, matchStart);
        if (between.contains(";") || between.contains("{") || between.contains("}")) {
            return java.util.Optional.empty();
        }
        int open = source.lastIndexOf("/**", close);
        if (open < 0) {
            return java.util.Optional.empty();
        }
        String doc = source.substring(open + 3, close)
                .replaceAll("(?m)^\\s*\\*", " ")
                .replaceAll("\\{@code ([^}]*)\\}", "`$1`")
                .replaceAll("\\{@link ([^}]*)\\}", "$1")
                .replaceAll("\\s+", " ")
                .trim();
        return doc.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(doc);
    }

    /**
     * The internal planning documents (the docs-site nav.mjs EXCLUDED set): not user
     * documentation, so the index never cites them as a code's cookbook page.
     */
    private static final Set<String> INTERNAL_DOCS = Set.of("roadmap.md", "docs-site.md",
            "studio-backlog.md", "hc-briefs.md", "release.md", "build.md",
            "development-environment.md", "app-developer-distribution.md");

    /**
     * Marks each code with the cookbook pages whose markdown mentions it — every
     * {@code docs/*.md} except the generated reference pages themselves, which would
     * otherwise "document" every code they index, and the {@link #INTERNAL_DOCS}.
     */
    private static void mentionDocs(Path docsDir, Map<String, Map<Integer, Code>> byDomain)
            throws IOException {
        List<Path> pages;
        try (Stream<Path> docs = Files.list(docsDir)) {
            pages = docs
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .filter(p -> !p.getFileName().toString().startsWith("reference-"))
                    .filter(p -> !INTERNAL_DOCS.contains(p.getFileName().toString()))
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

    /**
     * The meaning column: distinct extracted messages (a code raised for two reasons
     * shows both), markdown-table-safe, capped at two entries and 220 characters with an
     * honest ellipsis; {@code —} when no raise site offered one.
     */
    private static String meaningCell(TreeSet<String> messages) {
        if (messages.isEmpty()) {
            return "—";
        }
        String joined = String.join(" · ", messages.stream().limit(2).toList());
        if (joined.length() > 220) {
            int cut = joined.lastIndexOf(' ', 219);
            joined = joined.substring(0, cut > 120 ? cut : 219) + " …";
        } else if (messages.size() > 2) {
            joined = joined + " …";
        }
        return joined.replace("|", "\\|").replace("<", "&lt;").replace(">", "&gt;");
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
