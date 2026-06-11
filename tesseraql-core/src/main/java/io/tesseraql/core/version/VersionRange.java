package io.tesseraql.core.version;

import java.util.ArrayList;
import java.util.List;

/**
 * A version constraint used in the compatibility matrix (design ch. 30.1).
 *
 * <p>A range is a comma-separated list of constraints, each {@code <op><version>} where {@code op}
 * is one of {@code >=}, {@code <=}, {@code >}, {@code <}, {@code =} (a bare version means {@code =}).
 * An empty range or {@code *} matches any version. A version satisfies the range when it satisfies
 * every constraint, for example {@code ">=0.1.0, <0.2.0"}.
 */
public final class VersionRange {

    private final List<Constraint> constraints;
    private final String text;

    private VersionRange(List<Constraint> constraints, String text) {
        this.constraints = constraints;
        this.text = text;
    }

    public static VersionRange parse(String value) {
        String raw = value == null ? "" : value.trim();
        List<Constraint> constraints = new ArrayList<>();
        if (!raw.isEmpty() && !"*".equals(raw)) {
            for (String part : raw.split(",")) {
                String token = part.trim();
                if (!token.isEmpty()) {
                    constraints.add(Constraint.parse(token));
                }
            }
        }
        return new VersionRange(List.copyOf(constraints), raw.isEmpty() ? "*" : raw);
    }

    /** Whether {@code version} satisfies every constraint (vacuously true for an open range). */
    public boolean includes(SemanticVersion version) {
        return constraints.stream().allMatch(constraint -> constraint.test(version));
    }

    @Override
    public String toString() {
        return text;
    }

    private record Constraint(String op, SemanticVersion bound) {

        static Constraint parse(String token) {
            for (String op : new String[]{">=", "<=", ">", "<", "="}) {
                if (token.startsWith(op)) {
                    return new Constraint(op, SemanticVersion.parse(token.substring(op.length())));
                }
            }
            return new Constraint("=", SemanticVersion.parse(token));
        }

        boolean test(SemanticVersion version) {
            int cmp = version.compareTo(bound);
            return switch (op) {
                case ">=" -> cmp >= 0;
                case "<=" -> cmp <= 0;
                case ">" -> cmp > 0;
                case "<" -> cmp < 0;
                default -> cmp == 0;
            };
        }
    }
}
