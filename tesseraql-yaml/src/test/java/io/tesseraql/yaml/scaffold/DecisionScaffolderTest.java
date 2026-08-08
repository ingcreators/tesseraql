package io.tesseraql.yaml.scaffold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.SimpleYamlParser;
import io.tesseraql.yaml.decision.DecisionSets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code scaffold decision} (docs/decision-tables.md "Scaffolder and gallery adoption"): the
 * declaration and the typed backing-table migration come from one contract, so they cannot
 * disagree — proven here by loading the generated declaration through the same
 * {@link DecisionSets} pass the manifest loader runs, which compiles the source mapping.
 */
class DecisionScaffolderTest {

    private static Map<String, String> inputs() {
        Map<String, String> inputs = new LinkedHashMap<>();
        inputs.put("weight", "between");
        inputs.put("region", "eq");
        inputs.put("category", "in");
        return inputs;
    }

    @Test
    void theGeneratedDeclarationCompilesThroughTheManifestPass(@TempDir Path dir)
            throws Exception {
        List<ScaffoldedFile> files = new DecisionScaffolder().scaffold("shippingFee", inputs(),
                List.of("fee", "carrier"), false, true, 3);

        assertThat(files).extracting(ScaffoldedFile::path).containsExactly(
                "decisions/shipping-fee.yml", "db/migration/V3__decision_shipping_fee.sql");
        for (ScaffoldedFile file : files) {
            Path target = dir.resolve(file.path());
            Files.createDirectories(target.getParent());
            Files.writeString(target, file.stampedContent());
        }
        // The load compiles the source mapping (DecisionSets.compileSource), so a generated
        // declaration that did not match its own migration's columns would fail right here.
        DecisionSets sets = DecisionSets.load(dir, new SimpleYamlParser());
        assertThat(sets.decisions()).containsKey("shippingFee");
    }

    @Test
    void theMigrationCarriesOneColumnPerCellAndTheChildTable(@TempDir Path dir) {
        List<ScaffoldedFile> files = new DecisionScaffolder().scaffold("shippingFee", inputs(),
                List.of("fee"), false, true, 1);
        String migration = files.get(1).content();

        assertThat(migration)
                .contains("create table shipping_fee_rules")
                .contains("weight_min numeric")
                .contains("weight_max numeric")
                .contains("region varchar(100)")
                .contains("valid_from timestamp")
                .contains("priority int not null")
                .contains("fee varchar(100) not null")
                .contains("create table shipping_fee_rules_category");
    }

    @Test
    void uniqueSkipsThePriorityColumnAndDeclaration() {
        List<ScaffoldedFile> files = new DecisionScaffolder().scaffold("bonusTier",
                Map.of("amount", "between"), List.of("rate"), true, false, 1);

        assertThat(files.get(0).content())
                .contains("hitPolicy: unique")
                .doesNotContain("priority");
        assertThat(files.get(1).content()).doesNotContain("priority");
    }

    @Test
    void japaneseNamesScaffoldVerbatim() {
        // The identifier contract (docs/unicode-identifiers.md): a Japanese decision keeps
        // its name in the file stem, the rules table, and the migration.
        List<ScaffoldedFile> files = new DecisionScaffolder().scaffold("送料区分",
                Map.of("地域", "eq"), List.of("送料"), false, false, 1);

        assertThat(files.get(0).path()).isEqualTo("decisions/送料区分.yml");
        assertThat(files.get(1).path()).isEqualTo("db/migration/V1__decision_送料区分.sql");
        assertThat(files.get(1).content()).contains("create table 送料区分_rules");
    }

    @Test
    void aMalformedRequestFailsWithItsCode() {
        assertThatThrownBy(() -> new DecisionScaffolder().scaffold("Shipping Fee", inputs(),
                List.of("fee"), false, false, 1))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-DECISION-4730");
        assertThatThrownBy(() -> new DecisionScaffolder().scaffold("fee",
                Map.of("weight", "fuzzy"), List.of("fee"), false, false, 1))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-DECISION-4730");
    }
}
