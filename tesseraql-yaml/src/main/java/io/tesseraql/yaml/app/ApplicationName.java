package io.tesseraql.yaml.app;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.config.AppConfig;

/**
 * An application's name — {@code tesseraql.app.name} — required rather than defaulted.
 *
 * <p>It reads as a label and is an <b>identity</b>. It scopes outbox claims and cluster job claim
 * keys, it is the owner recorded against every job execution and therefore what
 * {@code tql.ops.view.<name>} grants are checked against, it names the MCP server, and under
 * docs/stack-architecture.md Decision 12 it is the application's address: {@code /<name>/}.
 *
 * <p>It used to default to the literal {@code app} when absent, which made the value <em>required
 * to deploy and optional to run</em> — `AppInstaller` refuses a package without it and a stack
 * addresses members by it, while a runtime started one anyway under a shared constant. Nothing
 * collided only because installation's requirement kept unnamed applications to one at a time; the
 * places the identity reaches are not ones a shared default is safe in. Two unnamed applications
 * against one database would have shared a migration history table and each other's outbox claims.
 *
 * <p>So it is required everywhere, and the lint rule reports it before a boot has to.
 */
public final class ApplicationName {

    /**
     * TQL-YAML-1404: the application declares no {@code tesseraql.app.name}.
     *
     * <p>Not defaulted, because the default is an identity shared with every other unnamed
     * application: one migration history table, one set of outbox claims, one
     * {@code tql.ops.view.<name>} grant covering things that are not the same application.
     */
    public static final TqlErrorCode MISSING = new TqlErrorCode(TqlDomain.YAML, 1404);

    /** The message both the lint rule and the runtime refusal carry, so they read identically. */
    public static final String MESSAGE = "This application declares no tesseraql.app.name."
            + " The name is its identity — it scopes outbox claims and job ownership, it is what"
            + " tql.ops.view.<name> grants are checked against, and in a stack it is the"
            + " application's address — so it cannot be defaulted to a value every unnamed"
            + " application shares.";

    /**
     * TQL-YAML-1405: the name is not a safe URL path segment (docs/stack-architecture.md
     * Decision 25), or it collides with the framework's authorization grammar
     * (docs/stack-shells.md structural decision 1).
     *
     * <p>The rule is segment safety plus the atom grammar, not the scaffolder's ASCII pattern:
     * the name becomes the application's address, so it must be one path segment (no {@code /})
     * and must not open the framework's fence (no leading {@code _} — {@code /_tesseraql/} is the
     * framework's) or hide as a dotted segment (no leading {@code .}). It also carries no dot at
     * all: a permission atom {@code tql.<family>.<verb>.<name>} parses by splitting on dots, so a
     * dotted name would be ambiguous against a name-plus-verb. Two words are reserved outright:
     * {@code assets}, because the framework serves its asset bundle at {@code <scope>/assets} at
     * every scope and a member named after it would shadow the stack's stylesheets
     * (docs/root-portal.md); and {@code tql}, the framework's own mark — every framework atom
     * lives under {@code tql.}, exactly as every framework address lives under
     * {@code /_tesseraql/}, so the mark is reserved once and future atom families never touch the
     * name rules again. Names are deliberately not confined to ASCII — the migration history
     * guard measures them in UTF-8 bytes for exactly that reason — so a character-class pattern
     * here would outlaw what that guard exists to measure.
     */
    public static final TqlErrorCode UNSAFE_SEGMENT = new TqlErrorCode(TqlDomain.YAML, 1405);

    private ApplicationName() {
    }

    /** The declared name, or {@link #MISSING} / {@link #UNSAFE_SEGMENT}. */
    public static String of(AppConfig config) {
        String declared = config.getString("tesseraql.app.name").map(String::trim)
                .filter(name -> !name.isEmpty()).orElse(null);
        if (declared == null) {
            throw new TqlException(MISSING, MESSAGE);
        }
        String violation = segmentViolation(declared);
        if (violation != null) {
            throw new TqlException(UNSAFE_SEGMENT, violation);
        }
        return declared;
    }

    /**
     * Why {@code name} cannot be an address segment, or {@code null} when it can — shared by the
     * boot refusal above and the lint rule, so the two read identically.
     */
    public static String segmentViolation(String name) {
        if (name.indexOf('/') >= 0) {
            return unsafe(name, "it contains '/', and the name is one path segment");
        }
        if (name.startsWith("_")) {
            return unsafe(name, "a leading '_' opens the framework's own fence (/_tesseraql/)");
        }
        if (name.startsWith(".")) {
            return unsafe(name, "a leading '.' hides it as a dotted segment");
        }
        if (name.indexOf('.') >= 0) {
            return unsafe(name, "it contains '.', and a permission atom"
                    + " (tql.<family>.<verb>.<name>) parses by splitting on dots — a dotted name"
                    + " would be ambiguous against a name-plus-verb");
        }
        if (name.equals("tql")) {
            return unsafe(name, "'tql' is the framework's own mark — every framework permission"
                    + " atom lives under tql., exactly as every framework address lives under"
                    + " /_tesseraql/");
        }
        if (name.equals("assets")) {
            return unsafe(name, "'assets' is the framework's own claim — every scope serves its"
                    + " asset bundle at <scope>/assets, so a member named after it would shadow"
                    + " the stack's stylesheets");
        }
        return null;
    }

    private static String unsafe(String name, String why) {
        return "tesseraql.app.name '" + name + "' cannot be the application's address: " + why
                + ". The rule is segment safety, not an ASCII pattern — non-ASCII names stay"
                + " legal.";
    }
}
