package io.tesseraql.core.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The language dimension of a code catalog (docs/lookups.md, decision 12). */
class CodeCatalogLanguageTest {

    /** 現金 in Japanese, Cash in English, one code — language is a dimension, not a key. */
    private static CodeCatalog bilingual() {
        return CodeCatalog.of("取引区分", List.of(
                new CodeCatalog.Entry("1", "現金", true, "ja"),
                new CodeCatalog.Entry("1", "Cash", true, "en"),
                new CodeCatalog.Entry("2", "振込", true, "ja"),
                new CodeCatalog.Entry("2", "Transfer", true, "en"),
                // Retired, and translated into neither of the app's languages.
                new CodeCatalog.Entry("9", "手形", false, "ja")));
    }

    @Test
    void narrowsToTheRequestedLanguage() {
        CodeCatalog english = bilingual().inLanguage("en", "ja");
        assertThat(english.of("1")).isEqualTo("Cash");
        assertThat(english.of("2")).isEqualTo("Transfer");
        assertThat(english.language()).isEqualTo("en");
    }

    @Test
    void anUntranslatedCodeFallsBackToTheDefaultLanguage() {
        // '9' has only a Japanese name. Falling back to the raw code would print a number
        // where a name belongs; falling back to Japanese prints something a person can act on.
        assertThat(bilingual().inLanguage("en", "ja").of("9")).isEqualTo("手形");
    }

    @Test
    void aRegionalTagMatchesTheLanguageTheMasterStores() {
        // A master stores ja; a browser asks for ja-JP. RFC 4647 lookup, both directions.
        assertThat(bilingual().inLanguage("ja-JP", "en").of("1")).isEqualTo("現金");
    }

    @Test
    void theKeySetDoesNotNarrowWithTheLabels() {
        // Validation asks whether a code exists, which is not a question about language: a
        // code with no English name is still a code, and must not be refused for that.
        CodeCatalog english = bilingual().inLanguage("en", "en");
        assertThat(english.has("9")).isTrue();
    }

    @Test
    void optionsCarryOneEntryPerCodeInTheSourcesOrder() {
        // The rows arrive twice per code (once per language); a form offers each code once,
        // in the order the source gave, and never the retired one.
        List<CodeCatalog.Entry> offered = bilingual().inLanguage("en", "ja").options();
        assertThat(offered).extracting(CodeCatalog.Entry::key).containsExactly("1", "2");
        assertThat(offered).extracting(CodeCatalog.Entry::label)
                .containsExactly("Cash", "Transfer");
    }

    @Test
    void aCatalogWithNoLanguageColumnIsItsOwnView() {
        // The single-language app: nothing declares a language, so narrowing is identity and
        // the rows answer whatever locale asks.
        CodeCatalog plain = CodeCatalog.of("取引区分",
                List.of(new CodeCatalog.Entry("1", "現金", true)));
        assertThat(plain.inLanguage("en", "en")).isSameAs(plain);
        assertThat(plain.inLanguage("en", "en").of("1")).isEqualTo("現金");
    }

    @Test
    void aCodeNamedInNeitherLanguageStillRendersAsItself() {
        CodeCatalog only = CodeCatalog.of("取引区分",
                List.of(new CodeCatalog.Entry("7", "Sonstige", true, "de")));
        CodeCatalog english = only.inLanguage("en", "ja");
        assertThat(english.of("7")).isEqualTo("7");
        assertThat(english.has("7")).isTrue();
    }
}
