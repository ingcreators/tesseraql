package io.tesseraql.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * The error-code reference is a committed artifact with a drift test behind it, so the bytes it
 * generates may not depend on the machine that generated them.
 *
 * <p>{@code ErrorIndex} pads the code number itself rather than delegating to
 * {@code TqlErrorCode.toString()}, and it has to: the scan finds domain strings that are not
 * {@code TqlDomain} constants, so {@code TqlDomain.valueOf} would throw on the real repository.
 * Because the padding is its own, the locale rule has to be applied there too — fixing
 * {@code TqlErrorCode} alone leaves the generated page localizing.
 *
 * <p>There is no parallel test execution in this repository, so restoring the default in a finally
 * block is enough.
 */
class ErrorIndexLocaleTest {

    private static final Path REPO = Paths.get("..").toAbsolutePath().normalize();

    @Test
    void theGeneratedTableSpellsEveryCodeInAsciiDigits() throws Exception {
        Locale original = Locale.getDefault();
        String underArabic;
        try {
            Locale.setDefault(Locale.of("ar", "EG"));
            underArabic = ErrorIndex.render(REPO);
        } finally {
            Locale.setDefault(original);
        }

        // Every row's code cell, as the page spells it. A localized digit here would be a code
        // the runtime never emits, in the page a user searches when they see one.
        assertThat(underArabic).contains("| `TQL-");
        assertThat(underArabic.lines()
                .filter(line -> line.startsWith("| `TQL-"))
                .filter(line -> !line.substring(0, line.indexOf("` |") + 1)
                        .chars().allMatch(c -> c < 128))
                .toList())
                .as("code cells carrying a non-ASCII digit")
                .isEmpty();
    }

    @Test
    void theGeneratedPageIsByteIdenticalWhateverTheDefaultLocale() throws Exception {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.US);
            String english = ErrorIndex.render(REPO);
            Locale.setDefault(Locale.of("bn", "BD"));
            String bengali = ErrorIndex.render(REPO);
            assertThat(bengali).isEqualTo(english);
        } finally {
            Locale.setDefault(original);
        }
    }
}
