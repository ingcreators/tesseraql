package io.tesseraql.operations.app;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * What a directory handed to {@code --app} or {@code --suite} actually holds
 * (docs/cli-surface.md Decisions 1–3).
 *
 * <p>The flag says how many applications the caller means; this says how many are there. One
 * application, a catalogue of installed ones, or a folder of source trees — and a refusal that names
 * the alternatives when the two disagree, because a command that guesses runs the wrong thing and a
 * command that says "expected a single application" costs a directory listing and a guess.
 *
 * <h2>Order and depth are load-bearing</h2>
 *
 * <p>An application is recognised <b>before</b> its children are looked at, and children are looked
 * at <b>one level</b> down. Every real application home unpacks the five bundled framework surfaces
 * into {@code work/apps/} — {@code account}, {@code auth-ui}, {@code iam-admin},
 * {@code ops-console}, {@code studio} — and each of those <em>is</em> an application home. A
 * resolver that scanned an application for children, or scanned recursively, would offer the
 * framework's own surfaces as if the operator had installed them. Both halves of the rule are
 * therefore deliberate, and the next person to make the scan recursive to be helpful will undo them.
 */
public final class AppDirectory {

    /** TQL-APP-4209: the directory holds no application at all. */
    private static final TqlErrorCode HOLDS_NOTHING = new TqlErrorCode(TqlDomain.APP, 4209);

    /** TQL-APP-4210: the directory holds applications, but not the number the flag means. */
    private static final TqlErrorCode WRONG_SHAPE = new TqlErrorCode(TqlDomain.APP, 4210);

    /** The catalogue an install root is recognised by. */
    private static final String CATALOG = "catalog.json";

    private AppDirectory() {
    }

    /** What a directory holds, before any flag has an opinion about it. */
    public enum Shape {
        /** The directory is itself an application home. */
        APPLICATION,
        /** The directory catalogues installed applications ({@code catalog.json}). */
        INSTALL_ROOT,
        /** The directory holds application homes one level down: a folder of source trees. */
        WORKSPACE,
        /** Nothing recognisable. */
        NOTHING
    }

    /**
     * A resolved directory: what it is, and every application in it.
     *
     * @param applications the application homes, in a stable order — exactly one for
     *                     {@link Shape#APPLICATION}, and empty for {@link Shape#NOTHING}
     */
    public record Resolved(Shape shape, Path root, List<Path> applications) {

        public Resolved {
            applications = List.copyOf(applications);
        }
    }

    /** Resolves without judging: the answer a flag is then checked against. */
    public static Resolved resolve(Path dir) {
        Path root = dir.toAbsolutePath().normalize();
        if (Files.isRegularFile(root.resolve(CATALOG))) {
            return new Resolved(Shape.INSTALL_ROOT, root, catalogued(root));
        }
        if (isApplication(root)) {
            return new Resolved(Shape.APPLICATION, root, List.of(root));
        }
        List<Path> children = childApplications(root);
        return children.isEmpty()
                ? new Resolved(Shape.NOTHING, root, List.of())
                : new Resolved(Shape.WORKSPACE, root, children);
    }

    /**
     * The one application {@code dir} holds — {@code --app}.
     *
     * <p>Refuses a directory holding several rather than picking one, and prints the commands that
     * would have worked.
     */
    public static Path application(Path dir, String commandForHelp) {
        Resolved resolved = resolve(dir);
        if (resolved.shape() == Shape.APPLICATION) {
            return resolved.applications().get(0);
        }
        if (resolved.shape() == Shape.NOTHING) {
            throw new TqlException(HOLDS_NOTHING, holdsNothing(resolved.root()));
        }
        throw new TqlException(WRONG_SHAPE, resolved.root() + " is not an application; it holds "
                + resolved.applications().size() + "."
                + alternatives(commandForHelp, "--app", resolved.applications()));
    }

    /**
     * Every application {@code dir} holds — {@code --suite}.
     *
     * <p>A directory that is itself an application is refused rather than scanned, which is what
     * keeps {@code work/apps/} out of reach entirely.
     */
    public static List<Path> suite(Path dir) {
        Resolved resolved = resolve(dir);
        return switch (resolved.shape()) {
            case INSTALL_ROOT, WORKSPACE -> resolved.applications();
            case APPLICATION -> throw new TqlException(WRONG_SHAPE, resolved.root()
                    + " is one application, not a suite — did you mean --app?");
            case NOTHING -> throw new TqlException(HOLDS_NOTHING, holdsNothing(resolved.root()));
        };
    }

    /**
     * Whether {@code dir} is an application home.
     *
     * <p>{@code config/} or {@code web/}: verified 2026-08-16 that every application home in the
     * tree has one — the examples, the five bundled framework surfaces, and the lint fixtures
     * except the deliberately empty one.
     */
    private static boolean isApplication(Path dir) {
        return Files.isDirectory(dir.resolve("config")) || Files.isDirectory(dir.resolve("web"));
    }

    /** The catalogued applications' homes, resolved against the install root. */
    private static List<Path> catalogued(Path root) {
        return new AppCatalog(root).list().stream()
                .map(app -> root.resolve(app.path()).normalize())
                .toList();
    }

    /** Application homes one level down — never deeper, see the class note. */
    private static List<Path> childApplications(Path root) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(root)) {
            return entries.filter(Files::isDirectory)
                    .filter(AppDirectory::isApplication)
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private static String holdsNothing(Path root) {
        return root + " holds no application. Expected an application home (config/ or web/), an"
                + " install root (catalog.json), or a folder of application homes.";
    }

    /** The runnable commands, so the refusal costs a second rather than a directory listing. */
    private static String alternatives(String command, String flag, List<Path> applications) {
        StringBuilder message = new StringBuilder();
        for (Path application : applications) {
            message.append("\n  ").append(command).append(' ').append(flag).append(' ')
                    .append(application);
        }
        return message.toString();
    }
}
