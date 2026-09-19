package io.tesseraql.core.files;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.core.expr.EvaluationContext;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The one resolver of a download or delivered name (docs/route-filename-placeholders.md
 * decisions 2 and 4): a placeholder is looked up as a dotted path, its value folded by the
 * split key's rule, {@code {key}} passes through, and a spelling outside the grammar stays
 * literal. Every row here is red on a resolver that appends the raw value (the fold arm) or
 * renders an absent value empty (the {@code _} arm).
 */
class FilenamePlaceholdersTest {

    private static EvaluationContext request(Map<String, Object> params) {
        Map<String, Object> root = new HashMap<>();
        root.put("params", params);
        root.put("path", Map.of("id", 42));
        return new EvaluationContext(root);
    }

    @Test
    void aValueIsLookedUpAsADottedPathAndFoldedToAFilenameComponent() {
        EvaluationContext request = request(Map.of("month", "2026/09", "name", "a\"b\\c",
                "escape", "../../etc/passwd", "ja", "受注 一覧"));
        assertThat(FilenamePlaceholders.resolve("orders-{params.month}.csv", request))
                .isEqualTo("orders-2026_09.csv");
        assertThat(FilenamePlaceholders.resolve("{params.name}.csv", request))
                .isEqualTo("a_b_c.csv");
        assertThat(FilenamePlaceholders.resolve("x-{params.escape}.csv", request))
                .as("a request value never becomes a path").isEqualTo("x-__.._etc_passwd.csv");
        assertThat(FilenamePlaceholders.resolve("{params.ja}.pdf", request))
                .as("letters in any script are kept; the space folds").isEqualTo("受注_一覧.pdf");
        assertThat(FilenamePlaceholders.resolve("user-{path.id}.pdf", request))
                .as("a non-string value is its string").isEqualTo("user-42.pdf");
    }

    @Test
    void anAbsentValueRendersAnUnderscoreNotNothing() {
        EvaluationContext request = request(Map.of());
        assertThat(FilenamePlaceholders.resolve("orders-{params.month}.csv", request))
                .isEqualTo("orders-_.csv");
        assertThat(FilenamePlaceholders.resolve("orders-{nowhere.at.all}.csv", request))
                .isEqualTo("orders-_.csv");
    }

    @Test
    void aValueIsCutAtAHundredGraphemes() {
        String resolved = FilenamePlaceholders.resolve("{params.long}.csv",
                request(Map.of("long", "x".repeat(101))));
        assertThat(resolved).isEqualTo("x".repeat(100) + ".csv");
    }

    @Test
    void theSplitKeyPassesThroughAndAMalformedSpellingStaysLiteral() {
        EvaluationContext request = request(Map.of("month", "2026-09"));
        assertThat(FilenamePlaceholders.resolve("orders-{params.month}-{key}.csv", request))
                .isEqualTo("orders-2026-09-{key}.csv");
        assertThat(FilenamePlaceholders.resolve("orders-{batch.business-date}.csv", request))
                .as("the grammar is letters, digits, _ and .; the lint refuses this spelling")
                .isEqualTo("orders-{batch.business-date}.csv");
        assertThat(FilenamePlaceholders.resolve("orders.csv", request)).isEqualTo("orders.csv");
        assertThat(FilenamePlaceholders.resolve(null, request)).isNull();
    }
}
