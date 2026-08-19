package io.tesseraql.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.core.jdbc.DriverManagerDataSource;
import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.manifest.ManifestLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code --datasource} selects which declared datasource backs the connection
 * (docs/cli-surface.md Decision 5): the default is {@code main}, and a named one resolves its own
 * coordinates — before the flag existed, {@code schema --datasource reporting} introspected
 * {@code main} while labelling the result {@code reporting}.
 */
class ConnectionOptionsTest {

    @Test
    void theNamedDatasourceBacksTheConnection(@TempDir Path dir) throws Exception {
        AppConfig config = configWith(dir, """
                tesseraql:
                  app:
                    name: demo
                  datasources:
                    main:
                      jdbcUrl: jdbc:postgresql://localhost:5432/main_db
                      username: main_user
                    reporting:
                      jdbcUrl: jdbc:postgresql://localhost:5432/reporting_db
                      username: reporting_user
                """);

        ConnectionOptions options = new ConnectionOptions();
        DriverManagerDataSource main = options.resolve(config);
        assertThat(main.url()).endsWith("/main_db");
        assertThat(main.user()).isEqualTo("main_user");

        options.name = "reporting";
        DriverManagerDataSource reporting = options.resolve(config);
        assertThat(reporting.url()).endsWith("/reporting_db");
        assertThat(reporting.user()).isEqualTo("reporting_user");
    }

    @Test
    void anExplicitJdbcUrlOutranksTheName(@TempDir Path dir) throws Exception {
        AppConfig config = configWith(dir, """
                tesseraql:
                  app:
                    name: demo
                  datasources:
                    main:
                      jdbcUrl: jdbc:postgresql://localhost:5432/main_db
                """);
        ConnectionOptions options = new ConnectionOptions();
        options.jdbcUrl = "jdbc:h2:mem:explicit";
        options.name = "reporting";
        assertThat(options.resolve(config).url()).isEqualTo("jdbc:h2:mem:explicit");
    }

    private static AppConfig configWith(Path dir, String applicationYml) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/application.yml"), applicationYml);
        return ManifestLoader.configOnly(dir);
    }
}
