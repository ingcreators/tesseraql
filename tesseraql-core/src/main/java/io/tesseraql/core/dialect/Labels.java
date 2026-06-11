package io.tesseraql.core.dialect;

import java.util.Locale;

/**
 * Result column label normalization (design ch. 42): Oracle folds unquoted identifiers to upper
 * case, so {@code select name} comes back as {@code NAME} - which would break the lowercase keys
 * route bindings, identity contracts and file exports address. All-uppercase labels fold to
 * lower case on Oracle; explicitly quoted mixed-case aliases pass through untouched. Other
 * dialects already return labels as written.
 */
public final class Labels {

    private Labels() {
    }

    public static String normalize(String dialect, String label) {
        if (label == null || !"oracle".equals(dialect)) {
            return label;
        }
        return label.equals(label.toUpperCase(Locale.ROOT))
                ? label.toLowerCase(Locale.ROOT)
                : label;
    }
}
