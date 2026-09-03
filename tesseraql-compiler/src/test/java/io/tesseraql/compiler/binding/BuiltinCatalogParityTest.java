package io.tesseraql.compiler.binding;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.yaml.i18n.MessageCatalog;
import java.io.InputStream;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The two bundled message catalogs carry the same keys.
 *
 * <p>Nothing held them together before, and the fallback hides the drift: a key present only in
 * English resolves to the English text for a Japanese reader, silently. The keys added for the
 * conflict dialog are the immediate reason to fix that, but the guard is general.
 *
 * <p>Read from the resources directly rather than through {@code I18nSettings.builtinCatalog()},
 * whose Japanese layer already falls back to English — asking that for parity would always
 * answer yes.
 */
class BuiltinCatalogParityTest {

    @Test
    void theBundledEnglishAndJapaneseCatalogsCarryTheSameKeys() throws Exception {
        Set<String> en = keys("en");
        Set<String> ja = keys("ja");

        assertThat(ja).as("keys missing from ja.yml").containsAll(en);
        assertThat(en).as("keys missing from en.yml").containsAll(ja);
    }

    @Test
    void theRefusalKeysTheFrameworkResolvesAreThere() throws Exception {
        // Set equality alone would stay green if a key vanished from BOTH files, which is exactly
        // how tql.workflow.illegal-transition came to render its own name to a user.
        assertThat(keys("en")).contains(
                "tql.conflict.stale", "tql.conflict.title", "tql.conflict.keep",
                "tql.conflict.reload", "tql.conflict.overwrite",
                "tql.workflow.illegal-transition");
    }

    private static Set<String> keys(String tag) throws Exception {
        String resource = "tesseraql/messages/" + tag + ".yml";
        try (InputStream stream = BuiltinCatalogParityTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            assertThat(stream).as(resource).isNotNull();
            return MessageCatalog.parse(tag, stream, resource).entries(tag).keySet();
        }
    }
}
