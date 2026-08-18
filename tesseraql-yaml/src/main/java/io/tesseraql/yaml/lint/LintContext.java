package io.tesseraql.yaml.lint;

import static io.tesseraql.yaml.lint.LintFinding.Severity.WARNING;

import io.tesseraql.core.expr.ExpressionFunctions;
import io.tesseraql.core.sql.Sql2WayParser;
import io.tesseraql.core.sql.SqlNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Per-run state for one {@link AppLinter#lint(Path)} pass: memoized file IO — text content,
 * parsed YAML trees, parsed 2-way SQL — plus the cross-rule state a run accumulates. The rules
 * are independent by design, so before this the same route SQL file was read and parsed once
 * per rule that looked at it, and every positioned finding re-read its whole document.
 *
 * <p>It is also the one failure policy for a file a rule cannot read. An unreadable file
 * surfaces once as {@code TQL-YAML-1053} and every accessor answers {@code null} — never a
 * silent empty value that lets a lint pass on content it could not see (the fail-open shape
 * the silent-tolerance campaign hunted). Malformed content also answers {@code null} but adds
 * no finding of its own: a document that does not parse is already reported with a parse code
 * where it loads, or by the lint that owns the SQL, and the context never double-reports it.
 */
final class LintContext {

    private static final String UNREADABLE_FILE = "TQL-YAML-1053";

    private final Path appHome;
    private final List<LintFinding> findings;
    private final Set<String> catalogTables;
    private final ExpressionFunctions functions;
    private final io.tesseraql.yaml.SimpleYamlParser parser = new io.tesseraql.yaml.SimpleYamlParser();
    private final Map<Path, Optional<String>> contents = new HashMap<>();
    private final Map<Path, Optional<Map<String, Object>>> trees = new HashMap<>();
    private final Map<Path, Optional<List<SqlNode>>> sqlNodes = new HashMap<>();
    private io.tesseraql.yaml.calendar.Calendars calendars = io.tesseraql.yaml.calendar.Calendars
            .empty();

    LintContext(Path appHome, List<LintFinding> findings, Set<String> catalogTables,
            ExpressionFunctions functions) {
        this.appHome = appHome;
        this.findings = findings;
        // Not Set.copyOf: the declaration order feeds finding messages, and copyOf randomizes it.
        this.catalogTables = java.util.Collections
                .unmodifiableSet(new java.util.LinkedHashSet<>(catalogTables));
        this.functions = functions;
    }

    /**
     * The app home every rule resolves its documents against, absolutized and normalized by
     * {@link AppLinter#lint(Path)} before the run starts.
     */
    Path appHome() {
        return appHome;
    }

    /** The expression-function set every parse of this run resolves custom calls against. */
    ExpressionFunctions functions() {
        return functions;
    }

    /**
     * The app's business-day calendars, loaded once by the calendar family and read by the job
     * family that checks schedules against them — the second piece of cross-rule state, held
     * here for the same reason {@link #catalogTables()} is: two families, one load.
     */
    io.tesseraql.yaml.calendar.Calendars calendars() {
        return calendars;
    }

    /** Publishes the loaded calendars to the families that check schedules against them. */
    void calendars(io.tesseraql.yaml.calendar.Calendars loaded) {
        this.calendars = loaded;
    }

    /**
     * The 1-based line of {@code token}'s first occurrence in {@code source} (authoring
     * feedback, roadmap Phase 43) — a best-effort position for document rules, so editors can
     * jump near the offending key; null when the file is unreadable or the token is absent.
     */
    Integer lineOf(Path source, String token) {
        String text = content(source);
        if (text == null) {
            return null;
        }
        int at = text.indexOf(token);
        if (at < 0) {
            return null;
        }
        int line = 1;
        for (int i = 0; i < at; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /**
     * The source tables the app's code catalogs read, for the {@code invalidates:} check.
     * Held for the run rather than threaded through every route-shaped surface: the check
     * belongs beside {@code lintEmit}, which those surfaces already share.
     */
    Set<String> catalogTables() {
        return catalogTables;
    }

    /**
     * The file's text, read once per run; {@code null} — after the one {@code TQL-YAML-1053}
     * finding — when it cannot be read.
     */
    String content(Path file) {
        return contents.computeIfAbsent(key(file), f -> {
            try {
                return Optional.of(Files.readString(f));
            } catch (IOException unreadable) {
                findings.add(new LintFinding(UNREADABLE_FILE, WARNING, source(f),
                        "The file could not be read (" + unreadable.getMessage()
                                + ") — every lint that reads its content was skipped, so fix"
                                + " its readability before trusting this report"));
                return Optional.empty();
            }
        }).orElse(null);
    }

    /**
     * The file parsed as a YAML tree, once per run; {@code null} when the file is unreadable
     * (reported by {@link #content}) or malformed (reported where the document loads).
     */
    Map<String, Object> tree(Path file) {
        return trees.computeIfAbsent(key(file), f -> {
            String text = content(f);
            if (text == null) {
                return Optional.empty();
            }
            try {
                return Optional.of(parser.parseTree(text));
            } catch (RuntimeException malformed) {
                return Optional.empty();
            }
        }).orElse(null);
    }

    /**
     * The file parsed as a 2-way SQL template, once per run; {@code null} when the file is
     * unreadable (reported by {@link #content}) or unparseable (its own lint's concern).
     */
    List<SqlNode> sqlNodes(Path file) {
        return sqlNodes.computeIfAbsent(key(file), f -> {
            String text = content(f);
            if (text == null) {
                return Optional.empty();
            }
            try {
                return Optional.of(Sql2WayParser.parse(text, functions));
            } catch (RuntimeException unparseable) {
                return Optional.empty();
            }
        }).orElse(null);
    }

    /** One canonical memo key per file, however a rule spelled the path to it. */
    private static Path key(Path file) {
        return file.toAbsolutePath().normalize();
    }

    /** The app-relative source for a finding; the raw path when the file sits outside the app. */
    private String source(Path file) {
        return file.startsWith(appHome)
                ? appHome.relativize(file).toString().replace('\\', '/')
                : file.toString().replace('\\', '/');
    }
}
