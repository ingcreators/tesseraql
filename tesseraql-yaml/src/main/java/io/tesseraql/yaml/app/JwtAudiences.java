package io.tesseraql.yaml.app;

import java.util.ArrayList;
import java.util.List;

/**
 * The declared JWT audiences ({@code tesseraql.security.jwt.audience}), read from the
 * configuration the one way both altitudes read it (docs/audit-hardening.md decision 1,
 * docs/audit-low-leads.md XD-07i): one string or a list, blank and null entries dropped.
 *
 * <p>The lint used to report the missing-audience refusal on an absent key only, while the
 * boot refused an empty list and a blank string and let {@code [""]} through — a configuration that then
 * demanded a token {@code aud} of {@code ""}, which no identity provider mints, so every bearer
 * request was refused while the CLI's own mint (which does emit {@code aud: ""}) made a local
 * smoke test pass. An audience that says nothing is "no audience" on both sides now.
 */
public final class JwtAudiences {

    private JwtAudiences() {
    }

    /**
     * The audiences {@code declared} names — the navigated value of the {@code audience} key:
     * a string is one audience, a list is each of its entries, trimmed, with blank and
     * {@code null} entries dropped; anything else (absent, a map) is none.
     */
    public static List<String> declared(Object declared) {
        List<String> out = new ArrayList<>();
        if (declared instanceof List<?> list) {
            for (Object element : list) {
                add(out, element);
            }
        } else {
            add(out, declared);
        }
        return List.copyOf(out);
    }

    private static void add(List<String> out, Object element) {
        if (element == null || element instanceof List<?>
                || element instanceof java.util.Map<?, ?>) {
            return;
        }
        String audience = String.valueOf(element).trim();
        if (!audience.isEmpty()) {
            out.add(audience);
        }
    }
}
