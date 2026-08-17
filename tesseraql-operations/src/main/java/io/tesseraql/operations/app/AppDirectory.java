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
 * What a directory handed to {@code --app} or {@code --stack} actually holds
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
     * Every application {@code dir} holds — {@code --stack}.
     *
     * <p>A directory that is itself an application is refused rather than scanned, which is what
     * keeps {@code work/apps/} out of reach entirely. A stack is a directory that <b>holds</b>
     * applications, never an application home itself: an application home cannot carry the stack's
     * own settings, and one application still cannot know stack-scoped answers such as its external
     * origin (docs/cli-surface.md Decision 1). The refusal prints the narrowing that serves just
     * this application from its parent directory.
     */
    public static List<Path> stack(Path dir) {
        Resolved resolved = resolve(dir);
        return switch (resolved.shape()) {
            case INSTALL_ROOT, WORKSPACE -> resolved.applications();
            case APPLICATION -> throw new TqlException(WRONG_SHAPE, resolved.root()
                    + " is one application, not a stack — a stack is a directory that holds"
                    + " applications." + narrowing(resolved.root()));
            case NOTHING -> throw new TqlException(HOLDS_NOTHING, holdsNothing(resolved.root()));
        };
    }

    /**
     * The narrowing that serves one application out of the directory that holds it —
     * {@code --stack <parent> --app-name <name>} — or nothing when the home has no parent to
     * name. Best-effort on the name: a home whose configuration cannot be read still gets the
     * shape of the fix, with the key to fill in.
     */
    private static String narrowing(Path home) {
        Path parent = home.getParent();
        if (parent == null) {
            return "";
        }
        String name;
        try {
            name = io.tesseraql.yaml.app.ApplicationName
                    .of(new io.tesseraql.yaml.manifest.ManifestLoader().load(home).config());
        } catch (RuntimeException unreadable) {
            name = "<its tesseraql.app.name>";
        }
        return " To serve just this one: --stack " + parent + " --app-name " + name;
    }

    /**
     * The applications {@code dir} holds, as catalogue entries — what a host needs whether or not
     * anything was ever installed.
     *
     * <p>An install root answers from its catalogue, so entitlements, hostnames and versions are
     * the ones recorded at install time. The other shapes have no catalogue, so an entry is
     * synthesised per application home from its own configuration: the name is
     * {@code tesseraql.app.name} — required since it is the application's identity, so there is
     * nothing to guess — the version is {@code tesseraql.app.version} or {@code 0.0.0}, and there
     * are no entitlements or hostnames, because a source tree has not been installed for anyone.
     *
     * <p>The {@code path} of every entry stays relative to {@link Resolved#root()}, exactly as a
     * catalogue's is, so a caller resolving {@code root.resolve(app.path())} needs no branch for
     * where the entries came from.
     */
    public static List<InstalledApp> applications(Resolved resolved) {
        if (resolved.shape() == Shape.INSTALL_ROOT) {
            return new AppCatalog(resolved.root()).list();
        }
        return resolved.applications().stream().map(home -> {
            io.tesseraql.yaml.config.AppConfig config = new io.tesseraql.yaml.manifest.ManifestLoader()
                    .load(home).config();
            // No address is derived from the shape the directory was resolved through: an
            // application has ONE address however it is reached, or serving it narrowed and
            // serving it as a stack member would change every URL it emits — the divergence
            // Decision 12 exists to remove (docs/stack-architecture.md, the flag reversal).
            return new InstalledApp(
                    io.tesseraql.yaml.app.ApplicationName.of(config),
                    config.getString("tesseraql.app.version").orElse("0.0.0"),
                    resolved.root().relativize(home).toString(),
                    List.of());
        }).toList();
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
