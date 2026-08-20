package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The base-classpath refusal (docs/module-channel.md decision 6). Stack-scoped pools resolve their
 * driver through {@code DriverManager} on the process classpath — the module channel loads an
 * application's jars on that application's loader, and the framework pool belongs to no
 * application. A framework database that is not PostgreSQL therefore needs its driver placed with
 * the deployment, and the failure to do so used to surface as the JDBC layer's
 * {@code No suitable driver}, which names neither the step nor the declaration.
 *
 * <p>The absent-driver cases use a DB2 URL rather than the SQL Server one the story is told with:
 * this module's own test classpath carries the drivers its dialect suites need, so a scheme
 * nothing on it accepts is what actually exercises the guard.
 */
class FrameworkDriverGuardTest {

    /**
     * No driver accepts the URL: {@code TQL-APP-4220}, naming what the stack declared and where a
     * jar goes. The check is on the symptom rather than on the coordinate, because a Maven
     * coordinate does not map to a class.
     */
    @Test
    void aFrameworkUrlNoDriverAcceptsRefusesTheStack(@TempDir Path dir) throws IOException {
        writeApplication(dir, "orders");
        Files.writeString(dir.resolve("tesseraql-stack.yml"), """
                framework:
                  datasource:
                    jdbcUrl: "jdbc:db2://db:50000/stack"
                    username: sa
                    modules:
                      - com.ibm.db2:jcc
                """);

        assertThatThrownBy(() -> MultiAppHost.start(dir))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-APP-4220")
                .hasMessageContaining("com.ibm.db2:jcc")
                .hasMessageContaining("lib/ext/");
    }

    /**
     * With nothing declared the refusal still fires — the declaration improves the message, it does
     * not gate the check — and says to add both the coordinate and the jar.
     */
    @Test
    void anUndeclaredDriverIsRefusedWithAdviceToDeclareIt(@TempDir Path dir) throws IOException {
        writeApplication(dir, "orders");
        Files.writeString(dir.resolve("tesseraql-stack.yml"), """
                framework:
                  datasource:
                    jdbcUrl: "jdbc:db2://db:50000/stack"
                    username: app
                """);

        assertThatThrownBy(() -> MultiAppHost.start(dir))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-APP-4220")
                .hasMessageContaining("framework.datasource.modules");
    }

    /**
     * PostgreSQL ships with every distribution, so the guard is silent and the start proceeds to
     * the pool — which fails to connect here, and that is a different failure entirely.
     */
    @Test
    void aBundledDriverPassesTheGuard(@TempDir Path dir) throws IOException {
        writeApplication(dir, "orders");
        Files.writeString(dir.resolve("tesseraql-stack.yml"), """
                framework:
                  datasource:
                    jdbcUrl: "jdbc:postgresql://db:5432/stack"
                    username: app
                    password: secret
                """);

        assertThatThrownBy(() -> MultiAppHost.start(dir))
                .satisfies(failure -> org.assertj.core.api.Assertions
                        .assertThat(String.valueOf(failure.getMessage()))
                        .doesNotContain("TQL-APP-4220"));
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
                      jdbcUrl: jdbc:postgresql://db:5432/one
                      username: app
                      password: secret
                """.formatted(name));
    }
}
