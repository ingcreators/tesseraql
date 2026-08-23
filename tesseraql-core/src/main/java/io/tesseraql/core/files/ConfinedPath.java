package io.tesseraql.core.files;

import java.nio.file.Path;
import java.util.Optional;

/**
 * A filesystem root that caller-influenced paths must stay under
 * (docs/duplication-consolidation.md, campaign 2). Twenty-two sites carried their own
 * {@code resolve().normalize()} + {@code startsWith(root)} sequence, and they disagreed on the
 * one thing that makes the guard hold: <em>both</em> sides must be absolutized <em>and</em>
 * normalized before comparison. A guard that normalizes only the candidate compares it against
 * whatever shape the root happened to arrive in — a relative or {@code ..}-carrying root makes
 * the check vacuous, which is a traversal waiting for the right working directory.
 *
 * <p>The root is canonicalized once, here. What escaping <em>means</em> stays with each caller —
 * a template resolver refuses with its own render error, a zip extractor with its package code, a
 * lint reports a finding, an asset route answers 404 — so escape comes back as an empty
 * {@link Optional}, never as one flattened exception.
 */
public final class ConfinedPath {

    private final Path root;

    private ConfinedPath(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /** The confinement root, absolutized and normalized once. */
    public static ConfinedPath under(Path root) {
        return new ConfinedPath(root);
    }

    /** The canonical root. */
    public Path root() {
        return root;
    }

    /**
     * Resolves {@code candidate} against the root; empty when the result escapes it. A relative
     * candidate resolves under the root; an absolute one is checked as it stands. {@code ..}
     * segments are folded before the comparison, so {@code a/../../etc/passwd} escapes.
     */
    public Optional<Path> resolve(String candidate) {
        return confine(root.resolve(candidate));
    }

    /**
     * Confines an already-built path — the caller composed it from segments of its own (a
     * tenant partition, a scope suffix, a zip entry) — folding and absolutizing before the
     * comparison; empty when it escapes the root.
     */
    public Optional<Path> confine(Path candidate) {
        Path resolved = candidate.isAbsolute()
                ? candidate.normalize()
                : root.resolve(candidate).normalize();
        return resolved.startsWith(root) ? Optional.of(resolved) : Optional.empty();
    }

    /** Whether the path stays under the root — the report-don't-throw form the lints use. */
    public boolean contains(Path candidate) {
        return confine(candidate).isPresent();
    }
}
