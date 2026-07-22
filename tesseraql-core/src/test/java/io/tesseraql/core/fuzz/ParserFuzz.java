package io.tesseraql.core.fuzz;

import java.util.Random;
import java.util.function.Consumer;

/**
 * A tiny deterministic generative fuzz harness (docs/security-hardening.md). A seeded PRNG drives
 * structure-aware token generation and corpus mutation; the oracle is the fail-closed invariant —
 * a parser may raise only its declared coded exception type on any input, never a
 * {@link StackOverflowError}, an {@link Error}, or an off-contract exception. Deterministic (fixed
 * seed, fixed budget) so it runs in ordinary CI as a regression net rather than a flaky campaign.
 */
public final class ParserFuzz {

    private ParserFuzz() {
    }

    /**
     * Runs {@code iterations} generated inputs through {@code parse}; fails on the first input that
     * escapes {@code allowed} with the offending input attached, so any regression is reproducible.
     */
    public static void fuzz(String label, Consumer<String> parse,
            Class<? extends Throwable> allowed,
            String[] tokens, String[] corpus, long seed, int iterations) {
        Random rnd = new Random(seed);
        for (int i = 0; i < iterations; i++) {
            String input = generate(rnd, tokens, corpus);
            try {
                parse.accept(input);
            } catch (Throwable thrown) {
                if (!allowed.isInstance(thrown)) {
                    throw new AssertionError(label + " raised " + thrown.getClass().getName()
                            + " (expected only " + allowed.getSimpleName() + ") on input:\n"
                            + preview(input), thrown);
                }
            }
        }
    }

    /** One of: a random token sequence, a mutated corpus entry, or a single token nested deep. */
    private static String generate(Random rnd, String[] tokens, String[] corpus) {
        return switch (rnd.nextInt(3)) {
            case 0 -> randomTokens(rnd, tokens);
            case 1 -> mutate(rnd, corpus[rnd.nextInt(corpus.length)], tokens);
            default -> tokens[rnd.nextInt(tokens.length)].repeat(1 + rnd.nextInt(4000));
        };
    }

    private static String randomTokens(Random rnd, String[] tokens) {
        StringBuilder sb = new StringBuilder();
        int n = rnd.nextInt(60);
        for (int i = 0; i < n; i++) {
            sb.append(tokens[rnd.nextInt(tokens.length)]);
            if (rnd.nextInt(4) == 0) {
                sb.append((char) (' ' + rnd.nextInt(95)));
            }
        }
        return sb.toString();
    }

    /** Byte/token-level mutation of a valid seed: delete, duplicate, splice, or corrupt a span. */
    private static String mutate(Random rnd, String seed, String[] tokens) {
        StringBuilder sb = new StringBuilder(seed);
        int edits = 1 + rnd.nextInt(6);
        for (int e = 0; e < edits && sb.length() > 0; e++) {
            int at = rnd.nextInt(sb.length());
            switch (rnd.nextInt(4)) {
                case 0 -> sb.deleteCharAt(at);
                case 1 -> sb.insert(at, tokens[rnd.nextInt(tokens.length)]);
                case 2 -> sb.insert(at, sb.substring(0, Math.min(sb.length(), at + 8)));
                default -> sb.setCharAt(at, (char) (' ' + rnd.nextInt(95)));
            }
        }
        return sb.toString();
    }

    private static String preview(String input) {
        String head = input.length() <= 200 ? input : input.substring(0, 200) + "…";
        return "  [" + input.length() + " chars] " + head.replace("\n", "\\n");
    }
}
