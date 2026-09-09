package io.tesseraql.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/**
 * A command line printed in the documentation names a verb and flags the CLI has.
 *
 * <p>The repository front page's quick start did not. It ran {@code serve}, a verb deleted in
 * 0.15.0, and then — after #1211 corrected the flag — {@code --app-name user-admin-app} against an
 * application whose {@code config/tesseraql.yml} declares {@code user-admin}, so the first command
 * a newcomer runs exited {@code TQL-APP-4040}. #1211 shipped that while fixing this very class of
 * defect, because its own check was one hand-written string in one file.
 *
 * <p>The vocabulary is <strong>derived from picocli's model</strong>, never listed here: a verb
 * renamed or a flag removed is caught the day it lands rather than when someone remembers to
 * extend a list.
 *
 * <p><strong>What this does not claim.</strong> It checks that a verb, its flags and a named
 * application exist — not that the command succeeds. A line with a placeholder argument is skipped
 * rather than guessed at, and the count of what it actually checked is asserted so that skipping
 * everything is a failure rather than a pass.
 */
class DocumentedCommandLineTest {

    private static final Path REPO = Path.of("..");

    /**
     * Whether a fenced line <em>invokes</em> the CLI rather than merely mentioning it.
     *
     * <p>The first token has to be the binary, however it is spelled — bare, or a path ending in
     * it. Matching a line that merely contains "tesseraql " read an instruction ("install the
     * tesseraql CLI") and an ASCII deployment diagram ("cloudflared -> kamal-proxy -> tesseraql
     * runtime") as commands, and reported their next word as a missing verb.
     */
    private static boolean invokesCli(String line) {
        String text = line.strip();
        if (text.startsWith("$ ")) {
            text = text.substring(2).strip();
        }
        String first = text.split("\\s+", 2)[0];
        return first.equals("tesseraql") || first.endsWith("/tesseraql");
    }

    @Test
    void everyDocumentedInvocationNamesWhatTheCliHas() throws IOException {
        CommandLine cli = new CommandLine(new TesseraqlCli());
        List<String> problems = new ArrayList<>();
        int checked = 0;

        for (Path page : pages()) {
            for (String command : invocations(Files.readString(page))) {
                List<String> words = List.of(command.split("\\s+"));
                CommandLine.Model.CommandSpec spec = cli.getCommandSpec();
                // The binary token, not the literal "tesseraql": the front page's own quick start
                // invokes it as tesseraql-cli/target/tesseraql-*/bin/tesseraql, so looking for the
                // bare word skipped the very line this guard exists for — and passed.
                int at = binaryAt(words) + 1;
                if (at == 0 || at >= words.size()) {
                    continue;
                }
                String verb = words.get(at);
                if (placeholder(verb)) {
                    continue;
                }
                CommandLine sub = cli.getSubcommands().get(verb);
                if (sub == null) {
                    problems.add(page + ": no such command '" + verb + "' in: " + command);
                    continue;
                }
                // A subcommand of a subcommand (modules add) carries its own options.
                if (at + 1 < words.size() && sub.getSubcommands().containsKey(words.get(at + 1))) {
                    sub = sub.getSubcommands().get(words.get(at + 1));
                }
                spec = sub.getCommandSpec();
                checked++;

                for (int i = at + 1; i < words.size(); i++) {
                    String word = words.get(i);
                    if (!word.startsWith("--") || placeholder(word)) {
                        continue;
                    }
                    String flag = word.contains("=") ? word.substring(0, word.indexOf('=')) : word;
                    if (spec.findOption(flag) == null) {
                        problems.add(page + ": '" + verb + "' has no option " + flag + " in: "
                                + command);
                    }
                }
                problems.addAll(namedApplication(page, spec, words, command));
            }
        }

        // Non-vacuity: a docs tree that moved, a fence marker that changed, or a stricter
        // placeholder rule would each satisfy the assertion below by checking nothing at all.
        assertThat(checked).as("documented invocations were actually parsed and checked")
                .isGreaterThan(15);
        assertThat(problems)
                .as("a documented command line names something the CLI does not have."
                        + " (Naming what exists is not a claim that the command succeeds.)")
                .isEmpty();
    }

    /**
     * A {@code --stack <dir> --app-name <name>} pair must name an application that is really
     * there. This is the half that catches what #1211 shipped: the verb and both flags existed.
     */
    private static List<String> namedApplication(Path page,
            CommandLine.Model.CommandSpec spec, List<String> words, String command) {
        if (spec.findOption("--app-name") == null || spec.findOption("--stack") == null) {
            return List.of();
        }
        String stack = valueAfter(words, "--stack");
        String name = valueAfter(words, "--app-name");
        if (stack == null || name == null || placeholder(stack) || placeholder(name)) {
            return List.of();
        }
        Path root = REPO.resolve(stack);
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        Set<String> declared = declaredNames(root);
        if (declared.isEmpty() || declared.contains(name)) {
            return List.of();
        }
        return List.of(page + ": no application named '" + name + "' under " + stack
                + " (it holds " + declared + ") in: " + command);
    }

    /** The {@code tesseraql.app.name} each application home under {@code root} declares. */
    private static Set<String> declaredNames(Path root) {
        Set<String> names = new LinkedHashSet<>();
        try (Stream<Path> homes = Files.list(root)) {
            for (Path home : homes.filter(Files::isDirectory).sorted().toList()) {
                Path config = home.resolve("config/tesseraql.yml");
                if (!Files.isRegularFile(config)) {
                    continue;
                }
                for (String line : Files.readString(config).lines().toList()) {
                    String trimmed = line.strip();
                    if (trimmed.startsWith("name:")) {
                        names.add(trimmed.substring("name:".length()).strip());
                        break;
                    }
                }
            }
        } catch (IOException unreadable) {
            return Set.of();
        }
        return names;
    }

    /** The index of the token that is the CLI binary, bare or at the end of a path. */
    private static int binaryAt(List<String> words) {
        for (int i = 0; i < words.size(); i++) {
            String word = words.get(i);
            if (word.equals("tesseraql") || word.endsWith("/tesseraql")) {
                return i;
            }
        }
        return -1;
    }

    private static String valueAfter(List<String> words, String flag) {
        int at = words.indexOf(flag);
        return at < 0 || at + 1 >= words.size() ? null : words.get(at + 1);
    }

    /** A stand-in the reader is meant to replace, not a literal to resolve. */
    private static boolean placeholder(String word) {
        return word.contains("<") || word.contains("$") || word.contains("*")
                || word.contains("{") || word.startsWith("-D");
    }

    /**
     * The CLI invocations inside fenced blocks, with shell continuations joined.
     *
     * <p>Only fenced blocks, because prose names a verb without meaning it as a command to run.
     */
    private static List<String> invocations(String markdown) {
        List<String> found = new ArrayList<>();
        boolean fenced = false;
        StringBuilder pending = new StringBuilder();
        for (String line : markdown.lines().toList()) {
            if (line.strip().startsWith("```")) {
                fenced = !fenced;
                pending.setLength(0);
                continue;
            }
            if (!fenced) {
                continue;
            }
            String text = line.strip();
            if (pending.length() > 0 || invokesCli(text)) {
                pending.append(pending.length() > 0 ? " " : "")
                        .append(text.endsWith("\\") ? text.substring(0, text.length() - 1) : text);
                if (!text.endsWith("\\")) {
                    found.add(pending.toString().strip());
                    pending.setLength(0);
                }
            }
        }
        return found;
    }

    /**
     * Every published page, the two front pages a newcomer reads first, and each example's own
     * README.
     *
     * <p>The examples were the gap. {@code examples/README.md} calls those pages "copy one as a
     * starting point", and one of them ran {@code tesseraql run --app …} — a verb that has never
     * existed — from #630 until it was found by measuring this guard's reach rather than its
     * result. The list was non-recursive and stopped at {@code examples/README.md}, so the pages
     * it points at were the one place a newcomer looks that nothing checked.
     */
    private static List<Path> pages() throws IOException {
        List<Path> pages = new ArrayList<>();
        pages.add(REPO.resolve("README.md"));
        pages.add(REPO.resolve("examples/README.md"));
        try (Stream<Path> docs = Files.list(REPO.resolve("docs"))) {
            pages.addAll(docs.filter(p -> p.toString().endsWith(".md")).sorted().toList());
        }
        try (Stream<Path> examples = Files.list(REPO.resolve("examples"))) {
            pages.addAll(examples.filter(Files::isDirectory)
                    .map(app -> app.resolve("README.md"))
                    .filter(Files::isRegularFile)
                    .sorted()
                    .toList());
        }
        return pages;
    }

    /**
     * A command line a <em>shipped page</em> teaches must also name a verb the CLI has.
     *
     * <p>Studio's PDF empty state told the reader to "start the server with it enabled
     * (<code>serve --modules pdf</code>)". {@code serve} was deleted in 0.15.0, and
     * {@code --modules} takes a directory rather than a module name, so the sentence was wrong
     * twice — in a page the product itself renders. The markdown guard above cannot see it: it is
     * HTML, not a fenced block, and the line does not begin with the binary's name.
     *
     * <p>The shape checked here is a {@code <code>} element whose content is a bare word followed
     * by a flag. Measured across every shipped resource tree before being written, that shape
     * matches exactly one element — so this is a check with no exemption list rather than a
     * heuristic that needs one.
     */
    @Test
    void everyCommandTaughtByAShippedPageNamesAVerbTheCliHas() throws IOException {
        CommandLine cli = new CommandLine(new TesseraqlCli());
        java.util.regex.Pattern embedded = java.util.regex.Pattern
                .compile("<code>([a-z][a-z-]*) (--[^<]*)</code>");
        List<String> problems = new ArrayList<>();
        int checked = 0;

        for (Path resources : shippedResourceRoots()) {
            if (!Files.isDirectory(resources)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(resources)) {
                for (Path page : files.filter(p -> p.toString().endsWith(".html")).toList()) {
                    java.util.regex.Matcher found = embedded.matcher(Files.readString(page));
                    while (found.find()) {
                        checked++;
                        String verb = found.group(1);
                        if (cli.getSubcommands().containsKey(verb)) {
                            continue;
                        }
                        problems.add(page + ": no such command '" + verb + "' in: "
                                + found.group());
                    }
                }
            }
        }

        // One element matches today. Zero means either that the last shipped page teaching a
        // command line was reworded - in which case delete this test, its subject is gone - or
        // that the shape stopped matching and the assertion below is passing on nothing.
        assertThat(checked)
                .as("shipped pages teaching a command line were found; zero means this guard has"
                        + " no subject left, or has stopped recognising it")
                .isPositive();
        assertThat(problems)
                .as("a shipped page teaches a verb the CLI does not have")
                .isEmpty();
    }

    /** The resource trees whose pages the product renders to a user. */
    private static List<Path> shippedResourceRoots() {
        return List.of(
                REPO.resolve("tesseraql-studio/src/main/resources"),
                REPO.resolve("tesseraql-ops-ui/src/main/resources"),
                REPO.resolve("tesseraql-runtime/src/main/resources"));
    }
}
