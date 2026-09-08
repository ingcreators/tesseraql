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

    /**
     * {@code config.getString("tesseraql.x.y")}, the typed variants beside it,
     * {@code requireString("...")}, and {@code navigate("...")}.
     *
     * <p>Without {@code navigate}, a key whose value is a block rather than a scalar — a list, or a
     * map of named entries — was invisible to this page. That is how
     * {@code tesseraql.security.jwt.audience} came to be a key an application <em>must</em> declare
     * and could not look up here, and the same gap hid the mTLS client registry and the policy map.
     * Without {@code requireString}, the <em>mandatory</em> keys — the ones an operator most needs
     * this page for — were invisible the same way: six of them, from the copilot endpoint to the
     * SAML IdP key, were absent from the committed page when the gap was found.
     */
    private static final Pattern READ = Pattern.compile(
            "(?:get(?:String|Boolean|Int|Integer|Long|List|Map|Duration|Double)|requireString|navigate)\\(\\s*\""
                    + "((?:tesseraql|server|db)\\.[A-Za-z0-9_.<>-]*)\"");

    /**
     * A key handed to a helper instead of to the config object: {@code flag("tesseraql.scim.enabled",
     * false)}, {@code readSql(config, "tesseraql.scim.users.list")}, {@code duration(config,
     * "tesseraql.security.jwt.clockSkew", …)}.
     *
     * <p>Seven same-file private helpers wrap a config accessor to add a default, a refusal or a
     * type conversion, and every key reaching config through one of them was invisible to
     * {@link #READ} — the whole SCIM enable-and-contract surface, the JWT and JWKS clock tuning,
     * the mTLS skew. An operator following a troubleshooting page to this index could not find
     * them, on a page whose header promised every key.
     *
     * <p>This pattern alone would be too loose, so {@link #readsConfig} gates it. See there.
     *
     * <p>The callee is captured with {@code \w+} rather than a spelled-out identifier class, so
     * this file stays outside {@code IdentifierContractLedgerTest}'s ledger. That guard exists
     * because a re-inlined copy of the identifier contract is how the write-scope lint went blind,
     * and a scan over Java method names has no business being ledgered beside the SQL grammar just
     * to be allowed to spell one out.
     */
    private static final Pattern HELPER_READ = Pattern.compile(
            "\\b(\\w+)\\(\\s*[^\"(),]*(?:\\([^()\"]*\\))?[^\"(),]*,\\s*"
                    + "\"((?:tesseraql|server|db)\\.[A-Za-z0-9_.<>-]*)\"");

    /** A config accessor, for deciding whether a helper's own body actually reads one. */
    private static final Pattern ACCESSOR = Pattern.compile(
            "\\.(?:get(?:String|Boolean|Int|Integer|Long|List|Map|Duration|Double)"
                    + "|requireString|navigate)\\(");

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
                .append("All ").append(total).append(" configuration keys the framework reads "
                        + "whose name appears as a literal in a scanned source, grouped by "
                        + "namespace. The reading files are the provenance, and where a page "
                        + "discusses a key, it is linked. A key no page discusses still appears "
                        + "— that is the point of an index.\n\n")
                .append("Deliberately not here, because none of them is a key an application "
                        + "declares: JVM system properties, the Maven plugin's own goal "
                        + "parameters, metric and span names, keys a lookup composes from a "
                        + "prefix at request time, and the stack-level keys read from "
                        + "`tesseraql-stack.yml`.\n\n")
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
                    String text = Files.readString(file);
                    Matcher matcher = READ.matcher(text);
                    while (matcher.find()) {
                        collect(byNamespace, matcher.group(1), relative);
                    }
                    Map<String, Boolean> helpers = new java.util.HashMap<>();
                    Matcher helper = HELPER_READ.matcher(text);
                    while (helper.find()) {
                        if (readsConfig(text, helper.group(1), helpers)) {
                            collect(byNamespace, helper.group(2), relative);
                        }
                    }
                }
            }
        }
        return byNamespace;
    }

    /**
     * Whether {@code callee} is declared in this same file and its own body reads configuration.
     *
     * <p>Both halves fail closed, and each closes a real false positive.
     *
     * <p><b>Not declared here.</b> {@code StudioProviders} calls a statically imported
     * {@code putIfPresent(values, "tesseraql.saml.idp.metadata", ...)} seven times - that helper
     * <em>writes</em> the key into the Studio wizard's overlay map. There is no declaration to
     * inspect, and admitting it would put seven rows on this page citing a writer as a reader.
     *
     * <p><b>Declared, but reads nothing.</b> {@code OidcRuntimeExtension.require(value,
     * "tesseraql.oidc.clientId")} uses the key only inside a refusal message; the read itself is
     * in {@code OidcConfig}. The test has to be on the helper's own <em>body</em> rather than on
     * the file, because that file does call {@code config.getString} elsewhere - a file-level
     * check fails open on exactly this case.
     *
     * <p>An allow-list of the seven known helper names would be the hand-kept roster this campaign
     * exists to remove, and would silently miss the eighth.
     */
    private static boolean readsConfig(String fileText, String callee, Map<String, Boolean> memo) {
        Boolean known = memo.get(callee);
        if (known != null) {
            return known;
        }
        boolean reads = false;
        Matcher declaration = Pattern.compile("\\b" + Pattern.quote(callee) + "\\s*\\(")
                .matcher(fileText);
        while (declaration.find() && !reads) {
            int open = fileText.indexOf('(', declaration.start());
            int close = matching(fileText, open, '(', ')');
            if (close < 0) {
                continue;
            }
            int brace = close + 1;
            while (brace < fileText.length() && Character.isWhitespace(fileText.charAt(brace))) {
                brace++;
            }
            // A declaration is followed by its body; a call is followed by anything else. An
            // abstract or interface method ends in ';' and has no body to inspect, so it is not
            // a declaration this can vouch for either.
            if (brace >= fileText.length() || fileText.charAt(brace) != '{') {
                continue;
            }
            int end = matching(fileText, brace, '{', '}');
            if (end > brace) {
                reads = ACCESSOR.matcher(fileText.substring(brace, end)).find();
            }
        }
        memo.put(callee, reads);
        return reads;
    }

    /** The index of the delimiter closing the one at {@code from}, or -1. */
    private static int matching(String text, int from, char open, char close) {
        int depth = 0;
        for (int at = from; at < text.length(); at++) {
            char ch = text.charAt(at);
            if (ch == open) {
                depth++;
            } else if (ch == close) {
                depth--;
                if (depth == 0) {
                    return at;
                }
            }
        }
        return -1;
    }

    /**
     * The files this page reports as reading {@code key}, or an empty set.
     *
     * <p>For a ledger that pins a key to one reader. It goes through {@link #scan} rather than
     * carrying its own regex on purpose: a narrower pattern would let a reader the published page
     * already names hide from the guard, which is the one failure a read ledger cannot have.
     */
    static java.util.SortedSet<String> readersOf(Path repoRoot, String key) throws IOException {
        for (Map<String, Key> namespace : scan(repoRoot).values()) {
            Key found = namespace.get(key);
            if (found != null) {
                return found.sources();
            }
        }
        return new TreeSet<>();
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
