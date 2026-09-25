package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import io.tesseraql.operations.app.StackSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Who owns the stack surface's {@code main} (docs/capacity-defaults.md decision 12). Where the host
 * holds a framework pool, the surface borrows it and must leave it open when it closes: the host
 * closes it, after the surface. Where the stack supplies none, the surface builds its own pool on
 * the coordinate the applications agreed on, at TesseraQL's defaults.
 *
 * <p>Each case starts a host of its own, because closing the surface is part of the assertion.
 */
@Testcontainers
class SurfaceFrameworkPoolIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Test
    void theSurfaceLeavesTheBorrowedPoolOpenAndTheHostClosesIt(@TempDir Path stack)
            throws Exception {
        writeApplication(stack, "orders");
        Files.writeString(stack.resolve(StackSettings.FILE_NAME), """
                framework:
                  datasource:
                    jdbcUrl: %s
                    username: %s
                    password: %s
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));

        MultiAppHost host = MultiAppHost.start(stack);
        HikariDataSource framework;
        try {
            framework = (HikariDataSource) host.context().frameworkDataSource();
            TesseraqlRuntime surface = host.app(MultiAppHost.SURFACE_SLOT);
            assertThat(surface.context().lookup("main", javax.sql.DataSource.class))
                    .isSameAs(framework);

            surface.close();

            assertThat(framework.isClosed()).as("the surface closed a pool it borrowed").isFalse();
            try (Connection connection = framework.getConnection()) {
                assertThat(connection.isValid(5)).isTrue();
            }
        } finally {
            host.close();
        }
        assertThat(framework.isClosed()).as("the host closes the pool it built").isTrue();
    }

    @Test
    void withNoFrameworkDatasourceTheSurfaceBuildsItsOwnMainAtTheDefaults(@TempDir Path stack)
            throws Exception {
        writeApplication(stack, "orders");

        MultiAppHost host = MultiAppHost.start(stack);
        try {
            assertThat(host.context().frameworkDataSource()).isNull();
            HikariDataSource main = host.app(MultiAppHost.SURFACE_SLOT).context()
                    .lookup("main", HikariDataSource.class);
            assertThat(main.getPoolName()).isEqualTo("tesseraql-main");
            assertThat(main.getJdbcUrl()).isEqualTo(POSTGRES.getJdbcUrl());
            assertThat(main.getMaximumPoolSize()).isEqualTo(10);
            assertThat(main.getConnectionTimeout()).isEqualTo(30_000L);
        } finally {
            host.close();
        }
    }

    private static void writeApplication(Path stack, String name) throws IOException {
        Path config = stack.resolve(name).resolve("config");
        Files.createDirectories(config);
        Files.writeString(config.resolve("tesseraql.yml"), """
                tesseraql:
                  app:
                    name: %s
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                """.formatted(name, POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
    }
}
