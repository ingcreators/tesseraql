package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.identity.IdentityService;
import io.tesseraql.identity.RealmConfig;
import io.tesseraql.yaml.config.AppConfig;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * An identity realm names its own connector ({@code tesseraql.identity.realms.<id>.datasource}),
 * and that connector need not be {@code main}. The runtime could only resolve {@code main}'s
 * dialect, so a realm on another connector ran under the wrong vendor in three ways at once: it
 * selected the wrong {@code <contract>.<dialect>.sql} variant — all six variant contracts in the
 * pack are upsert-shaped writes — folded its labels under the wrong vendor, and since the
 * pagination retrofit appended the wrong vendor's clause.
 *
 * <p>The compiler has resolved a named connector correctly since Phase 53. This asserts the
 * runtime now does too, and that the two answers genuinely differ for the same configuration —
 * which is the defect, not merely the fix.
 */
class IdentityRealmDialectTest {

    private static final Path APP_HOME = Path.of("target", "test-app-home");

    /** A MySQL realm connector beside a PostgreSQL main — the configuration the defect needs. */
    private AppConfig config() {
        return new AppConfig(Map.of("tesseraql", Map.of(
                "identity", Map.of(
                        "defaultRealm", "corp",
                        "realms", Map.of("corp", Map.of("datasource", "people"))),
                "datasources", Map.of(
                        "main", Map.of("jdbcUrl", "jdbc:postgresql://localhost:5432/app"),
                        "people", Map.of("jdbcUrl", "jdbc:mysql://localhost:3306/people")))),
                name -> null);
    }

    @Test
    void aRealmResolvesTheDialectOfItsOwnConnectorAndNotMains() {
        AppConfig config = config();
        RealmConfig realm = IdentityConfigFactory.defaultRealm(config, APP_HOME);

        assertThat(realm.datasource()).isEqualTo("people");
        assertThat(TesseraqlRuntime.datasourceDialect(config, realm.datasource()))
                .isEqualTo("mysql");
    }

    /**
     * The wiring itself, not a restatement of it: this asks for the same service the boot binds
     * and reads the vendor back off it.
     */
    @Test
    void theServiceABootBindsCarriesTheRealmsOwnVendor() {
        AppConfig config = config();
        RealmConfig realm = IdentityConfigFactory.defaultRealm(config, APP_HOME);

        IdentityService identity = TesseraqlRuntime.identityService(config, realm, name -> null);

        assertThat(identity.dialect()).isEqualTo("mysql");
    }

    /**
     * The other half of the same fact: {@code main}'s dialect is a different answer, and it is the
     * one the identity service was handed. Asserting both is what makes this a defect rather than
     * a preference.
     */
    @Test
    void mainsDialectIsADifferentAnswerForTheSameConfiguration() {
        AppConfig config = config();

        assertThat(TesseraqlRuntime.datasourceDialect(config)).isEqualTo("postgres");
        assertThat(TesseraqlRuntime.datasourceDialect(config, "people"))
                .isNotEqualTo(TesseraqlRuntime.datasourceDialect(config));
    }

    /** A realm that does not name a connector still gets {@code main}'s, unchanged. */
    @Test
    void aRealmWithoutItsOwnConnectorKeepsMains() {
        AppConfig config = new AppConfig(Map.of("tesseraql", Map.of(
                "datasources", Map.of(
                        "main", Map.of("jdbcUrl", "jdbc:postgresql://localhost:5432/app")))),
                name -> null);
        RealmConfig realm = IdentityConfigFactory.defaultRealm(config, APP_HOME);

        assertThat(realm.datasource()).isEqualTo("main");
        assertThat(TesseraqlRuntime.datasourceDialect(config, realm.datasource()))
                .isEqualTo(TesseraqlRuntime.datasourceDialect(config));
    }
}
