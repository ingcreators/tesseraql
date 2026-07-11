package io.tesseraql.yaml.scaffold;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Applies scaffolded files to an app home with edit detection (roadmap Phase 23): pristine
 * generated files regenerate in place, edited or user-owned files are skipped and reported, and
 * {@code force} overrides both. Regeneration is therefore idempotent — applying the same
 * generation twice leaves every file byte-identical and reports it unchanged.
 */
public final class ScaffoldWriter {

    private static final TqlErrorCode TRAVERSAL = new TqlErrorCode(TqlDomain.APP, 5202);

    /**
     * The outcome of one apply: app-home-relative paths grouped by what happened to them.
     *
     * @param written   files created or regenerated
     * @param unchanged files already byte-identical to the generation
     * @param skipped   files left alone because the user edited them ({@link #blocked()} reports
     *                  whether any were) or owns them outright (no scaffold marker)
     */
    public record Report(List<String> written, List<String> unchanged, List<String> skipped) {

        public Report {
            written = List.copyOf(written);
            unchanged = List.copyOf(unchanged);
            skipped = List.copyOf(skipped);
        }

        /** Whether any file was held back; the caller decides if that fails the run. */
        public boolean blocked() {
            return !skipped.isEmpty();
        }
    }

    /** Writes the files under {@code appHome}, honoring the checksum contract. */
    public Report apply(Path appHome, List<ScaffoldedFile> files, boolean force) {
        Path home = appHome.toAbsolutePath().normalize();
        List<String> written = new ArrayList<>();
        List<String> unchanged = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (ScaffoldedFile file : files) {
            Path target = confine(home, file.path());
            String stamped = file.stampedContent();
            switch (decide(target, stamped, force)) {
                case WRITE -> {
                    write(target, stamped);
                    written.add(file.path());
                }
                case UNCHANGED -> unchanged.add(file.path());
                case SKIP -> skipped.add(file.path());
            }
        }
        return new Report(written, unchanged, skipped);
    }

    private enum Action {
        WRITE, UNCHANGED, SKIP
    }

    private static Action decide(Path target, String stamped, boolean force) {
        if (!Files.exists(target)) {
            return Action.WRITE;
        }
        String existing = read(target);
        if (existing.equals(stamped)) {
            return Action.UNCHANGED;
        }
        if (force) {
            return Action.WRITE;
        }
        return ScaffoldChecksum.status(existing) == ScaffoldChecksum.Status.PRISTINE
                ? Action.WRITE
                : Action.SKIP;
    }

    /** The path-confinement guardrail (design ch. 20.2): generated files stay in the app home. */
    private static Path confine(Path home, String relativePath) {
        Path target = home.resolve(relativePath).normalize();
        if (!target.startsWith(home)) {
            throw new TqlException(TRAVERSAL,
                    "Scaffolded path escapes the app home: " + relativePath);
        }
        return target;
    }

    private static String read(Path target) {
        try {
            return Files.readString(target);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static void write(Path target, String content) {
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, content);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
