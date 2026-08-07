package io.tesseraql.studio;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The mail composer's document model (docs/html-email.md D4): a mail template composed
 * from the bundled {@code tql/email/*} fragment library is a layout wrapper handing its
 * own {@code content} fragment to {@code hcLayout}, whose children are single-fragment
 * blocks. This class owns both directions of the strict round-trip rule — {@link #parse}
 * accepts exactly the grammar {@link #write} produces (plus whitespace), so the composer
 * never opens a template it could corrupt: anything else stays with the source editor.
 *
 * <p>Block arguments are raw Thymeleaf expressions ({@code 'Text'},
 * {@code |Hello ${payload.name}|}, {@code ${payload.url}}) — the composer's inspector
 * edits them verbatim; this class never evaluates or escapes them.
 */
public final class MailComposer {

    /** One canvas block: a fragment invocation with its raw expression arguments. */
    public record Block(String fragment, List<String> args) {
    }

    /** The composed document: the layout's title/preheader expressions plus the blocks. */
    public record Composition(String title, String preheader, List<Block> blocks) {
    }

    private static final String LIBRARY = "tql/email/hc-email";
    private static final String LAYOUT = "tql/email/hc-email-layout";
    private static final String LIBRARY_RESOURCE = "tesseraql/templates/tql/email/hc-email.html";

    private static final Pattern FRAGMENT_SIGNATURE = Pattern
            .compile("th:fragment=\"(\\w+)(?:\\(([^)]*)\\))?\"");
    private static final Pattern BLOCK = Pattern
            .compile("<div th:replace=\"~\\{" + LIBRARY + " :: (.+?)\\}\"></div>", Pattern.DOTALL);

    private MailComposer() {
    }

    /**
     * The palette — fragment name to parameter names, parsed from the bundled library on
     * the classpath (the app may shadow the bundled file with a re-themed eject, but the
     * fragment contract is the drift-guarded one, so the palette reads the framework copy).
     */
    public static Map<String, List<String>> palette() {
        Map<String, List<String>> palette = new LinkedHashMap<>();
        Matcher matcher = FRAGMENT_SIGNATURE.matcher(libraryHtml());
        while (matcher.find()) {
            List<String> params = matcher.group(2) == null || matcher.group(2).isBlank()
                    ? List.of()
                    : List.of(matcher.group(2).split(",\\s*"));
            palette.put(matcher.group(1), params);
        }
        return palette;
    }

    /**
     * Parses a template into the composer model, or empty when the file is anything but
     * the composer grammar — hand-written Thymeleaf, a {@code .txt} body, extra markup
     * around the wrapper. Empty means "open read-only, offer the source editor".
     */
    public static Optional<Composition> parse(String template) {
        if (template == null) {
            return Optional.empty();
        }
        String text = template.replaceAll("(?s)<!--.*?-->", "").trim();
        String wrapperOpen = "<div th:replace=\"~{" + LAYOUT + " :: hcLayout(";
        if (!text.startsWith(wrapperOpen) || !text.endsWith("</div>")) {
            return Optional.empty();
        }
        int argsStart = wrapperOpen.length();
        int argsEnd = closingParen(text, argsStart);
        if (argsEnd < 0 || !text.startsWith(")}\">", argsEnd)) {
            return Optional.empty();
        }
        List<String> layoutArgs = splitTopLevel(text.substring(argsStart, argsEnd));
        if (layoutArgs.size() != 3 || !"~{:: content}".equals(layoutArgs.get(2))) {
            return Optional.empty();
        }
        String inner = text.substring(argsEnd + ")}\">".length(),
                text.length() - "</div>".length()).trim();
        if (!inner.startsWith("<div th:fragment=\"content\">") || !inner.endsWith("</div>")) {
            return Optional.empty();
        }
        String body = inner.substring("<div th:fragment=\"content\">".length(),
                inner.length() - "</div>".length()).trim();

        List<Block> blocks = new ArrayList<>();
        Matcher matcher = BLOCK.matcher(body);
        int consumed = 0;
        while (matcher.find()) {
            if (!body.substring(consumed, matcher.start()).isBlank()) {
                return Optional.empty();
            }
            Block block = block(matcher.group(1));
            if (block == null) {
                return Optional.empty();
            }
            blocks.add(block);
            consumed = matcher.end();
        }
        if (!body.substring(consumed).isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new Composition(layoutArgs.get(0), layoutArgs.get(1), blocks));
    }

    /** The canonical template text — the same shape the composer's exporter emits. */
    public static String write(Composition composition) {
        StringBuilder out = new StringBuilder();
        out.append("<div th:replace=\"~{").append(LAYOUT).append(" :: hcLayout(")
                .append(composition.title()).append(",\n    ")
                .append(composition.preheader()).append(", ~{:: content})}\">\n");
        out.append("  <div th:fragment=\"content\">\n");
        for (Block block : composition.blocks()) {
            out.append("    <div th:replace=\"~{").append(LIBRARY).append(" :: ")
                    .append(block.fragment());
            if (!block.args().isEmpty()) {
                out.append('(').append(String.join(", ", block.args())).append(')');
            }
            out.append("}\"></div>\n");
        }
        out.append("  </div>\n</div>\n");
        return out.toString();
    }

    /** A starter document for a channel whose template file does not exist yet. */
    public static Composition starter() {
        return new Composition("'Notification'", "'A notification'",
                List.of(new Block("hcHeading", List.of("'Notification'")),
                        new Block("hcText", List.of("'Body text.'")),
                        new Block("hcFooter", List.of("|Sent by ${event.app}|"))));
    }

    /** The canvas rows: each block as fragment name, JSON-encoded args, and a chip label. */
    public static List<Map<String, Object>> blockRows(Composition composition) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Block block : composition.blocks()) {
            rows.add(Map.of("fragment", block.fragment(), "argsJson", json(block.args()),
                    "summary", summary(block)));
        }
        return rows;
    }

    /** The palette rows: each fragment with its parameter names, plain and JSON-encoded. */
    public static List<Map<String, Object>> paletteRows() {
        List<Map<String, Object>> rows = new ArrayList<>();
        palette().forEach((fragment, params) -> {
            // hcPanel takes a fragment expression as content — not a linear block; the
            // source editor remains its authoring surface.
            if ("hcPanel".equals(fragment)) {
                return;
            }
            rows.add(Map.of("fragment", fragment,
                    "params", String.join(", ", params), "paramsJson", json(params),
                    "argsJson", json(defaults(fragment, params))));
        });
        return rows;
    }

    /** The chip text drawn on a canvas block: the first argument, de-quoted and clipped. */
    public static String summary(Block block) {
        if (block.args().isEmpty()) {
            return "";
        }
        String first = block.args().get(0).replaceAll("^['|]|['|]$", "");
        return first.length() > 60 ? first.substring(0, 57) + "…" : first;
    }

    private static List<String> defaults(String fragment, List<String> params) {
        List<String> defaults = new ArrayList<>();
        for (String param : params) {
            defaults.add(switch (param) {
                case "href" -> "${payload.url}";
                case "rows" -> "${payload.rows}";
                case "content" -> "~{:: content}";
                default -> "'" + fragment.replaceAll("^hc", "") + " " + param + "'";
            });
        }
        return defaults;
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    private static Block block(String invocation) {
        int paren = invocation.indexOf('(');
        if (paren < 0) {
            return invocation.matches("\\w+") ? new Block(invocation, List.of()) : null;
        }
        String name = invocation.substring(0, paren);
        if (!name.matches("\\w+") || !invocation.endsWith(")")) {
            return null;
        }
        return new Block(name,
                splitTopLevel(invocation.substring(paren + 1, invocation.length() - 1)));
    }

    /**
     * Splits comma-separated expression arguments at the top level only: commas inside
     * {@code '…'}/{@code "…"} literals, {@code |…|} literal substitutions, and any
     * {@code (…)}/{@code {…}} nesting belong to their argument.
     */
    private static List<String> splitTopLevel(String args) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < args.length(); i++) {
            char c = args.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '\'' || c == '"' || c == '|') {
                quote = c;
            } else if (c == '(' || c == '{') {
                depth++;
            } else if (c == ')' || c == '}') {
                depth--;
            } else if (c == ',' && depth == 0) {
                parts.add(current.toString().trim());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        parts.add(current.toString().trim());
        return parts;
    }

    /** The index just past the wrapper's argument list, honoring quotes and nesting. */
    private static int closingParen(String text, int from) {
        int depth = 1;
        char quote = 0;
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '\'' || c == '"' || c == '|') {
                quote = c;
            } else if (c == '(' || c == '{') {
                depth++;
            } else if (c == ')' || c == '}') {
                depth--;
                if (depth == 0 && c == ')') {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String libraryHtml() {
        try (InputStream in = MailComposer.class.getClassLoader()
                .getResourceAsStream(LIBRARY_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(LIBRARY_RESOURCE + " is not on the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
