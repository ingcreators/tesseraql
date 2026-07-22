package io.tesseraql.scim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Fuzzes the SCIM filter parser (docs/security-hardening.md), the one runtime-reachable,
 * client-supplied parser: the {@code ?filter=} value from a provisioning client. Every input
 * yields a filter or a {@link ScimException} — never a crash — and the anchored {@code eq}-only
 * regex must not backtrack catastrophically. The regression cases pin the ReDoS-safety confirmed
 * by probing (millions of quotes/spaces/value characters all terminate promptly).
 */
class ScimFilterFuzzTest {

    private static final String[] TOKENS = {
            "userName", " ", "eq", "\"", "value", "'", "and", "or", "not", "(", ")", "[", "]",
            "co", "sw", "pr", "\\", "\t", "\n", "members", ".", ",", "%", "*", "+",
    };
    private static final String[] CORPUS = {
            "userName eq \"alice\"",
            "userName eq \"a b c\"",
            "  externalId eq \"x-1\"  ",
            "members[value eq \"g-1\"]",
    };

    @Test
    @Timeout(30)
    void everyFilterYieldsAResultOrAScimException() {
        Random rnd = new Random(20260722L);
        for (int i = 0; i < 4000; i++) {
            String input = generate(rnd);
            try {
                ScimFilter.parse(input);
            } catch (ScimException expected) {
                assertThat(expected.status()).isEqualTo(400);
            } catch (Throwable thrown) {
                throw new AssertionError("ScimFilter raised " + thrown.getClass().getName()
                        + " (expected only ScimException) on input:\n  [" + input.length()
                        + " chars] " + input.replace("\n", "\\n"), thrown);
            }
        }
    }

    @Test
    @Timeout(10)
    void pathologicalFiltersTerminatePromptly() {
        // The anchored regex must not ReDoS on adversarial quote/space runs.
        for (String hostile : new String[]{
                "a eq \"" + "\"".repeat(200_000),
                " ".repeat(1_000_000) + "a eq \"x\"",
                "a" + " ".repeat(1_000_000) + "eq \"x\"",
                "a eq \"" + "x".repeat(2_000_000) + "\"",
        }) {
            try {
                ScimFilter.parse(hostile);
            } catch (ScimException ignored) {
                // rejection is fine; the point is that it returns rather than hangs
            }
        }
    }

    @Test
    void validEqFilterParses() {
        ScimFilter filter = ScimFilter.parse("userName eq \"alice\"");
        assertThat(filter.attribute()).isEqualTo("userName");
        assertThat(filter.value()).isEqualTo("alice");
    }

    @Test
    void unsupportedOperatorIsRejected() {
        assertThatThrownBy(() -> ScimFilter.parse("userName co \"al\""))
                .isInstanceOf(ScimException.class);
    }

    private static String generate(Random rnd) {
        return switch (rnd.nextInt(3)) {
            case 0 -> {
                StringBuilder sb = new StringBuilder();
                int n = rnd.nextInt(40);
                for (int i = 0; i < n; i++) {
                    sb.append(TOKENS[rnd.nextInt(TOKENS.length)]);
                }
                yield sb.toString();
            }
            case 1 -> {
                StringBuilder sb = new StringBuilder(CORPUS[rnd.nextInt(CORPUS.length)]);
                int edits = 1 + rnd.nextInt(5);
                for (int e = 0; e < edits && sb.length() > 0; e++) {
                    int at = rnd.nextInt(sb.length());
                    switch (rnd.nextInt(3)) {
                        case 0 -> sb.deleteCharAt(at);
                        case 1 -> sb.insert(at, TOKENS[rnd.nextInt(TOKENS.length)]);
                        default -> sb.setCharAt(at, (char) (' ' + rnd.nextInt(95)));
                    }
                }
                yield sb.toString();
            }
            default -> TOKENS[rnd.nextInt(TOKENS.length)].repeat(1 + rnd.nextInt(2000));
        };
    }
}
