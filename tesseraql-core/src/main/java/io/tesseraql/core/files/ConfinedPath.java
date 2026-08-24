package io.tesseraql.core.files;

import java.nio.file.InvalidPathException;
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
 *
 * <p>The comparison is <em>lexical</em>: {@code ..} segments fold before it, and symlinks are
 * deliberately not resolved — the guard confines what the path says, not what the filesystem
 * aliases it to. A root whose descendants may carry hostile symlinks needs an out-of-band
 * answer (ownership, mount options), not this class.
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
     * segments are folded before the comparison, so {@code a/../../etc/passwd} escapes. A
     * candidate the filesystem cannot even express (a NUL byte, an illegal character) refuses
     * the same way an escape does — the caller's own domain refusal, never a raw parse error.
     */
    public Optional<Path> resolve(String candidate) {
        try {
            return confine(root.resolve(candidate));
        } catch (InvalidPathException invalid) {
            return Optional.empty();
        }
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
