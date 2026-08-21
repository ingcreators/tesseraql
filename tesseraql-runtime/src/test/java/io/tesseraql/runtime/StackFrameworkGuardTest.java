package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.operations.app.StackSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Decision 22's two start-time guards (docs/stack-architecture.md): the framework datasource is
 * where divergence presents as "signing in does not carry", which reads as a framework defect —
 * so absence is a check and a replaced request is a refusal, never a silence. Both fire before
 * any runtime starts, which is what makes them testable without a database.
 */
class StackFrameworkGuardTest {

    /**
     * No stack-supplied framework datasource + more than one application + disagreeing
     * coordinates = {@code TQL-APP-4211}, naming each application and its coordinate. The
     * comparison is the resolved strings, exactly — a false refusal is loud and one edit from
     * fixed, a false pass is a stack where one sign-in silently is not one.
     */
    @Test
    void disagreeingFrameworkCoordinatesRefuseTheStack(@TempDir Path dir) throws IOException {
        writeApplication(dir, "orders", "jdbc:postgresql://db:5432/one");
        writeApplication(dir, "billing", "jdbc:postgresql://db:5432/two");

        assertThatThrownBy(() -> MultiAppHost.start(dir))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-APP-4211")
                .hasMessageContaining("orders: jdbc:postgresql://db:5432/one")
                .hasMessageContaining("billing: jdbc:postgresql://db:5432/two")
                .hasMessageContaining(StackSettings.FILE_NAME);
    }

    /**
     * An application that explicitly declares {@code tesseraql.framework.datasource} while the
     * stack supplies the connection is refused ({@code TQL-APP-4212}): it asked for framework
     * state on a particular pool and the host would be replacing that pool. The unstated
     * {@code main} default is not a request, so only the declarer is named.
     */
    @Test
    void anExplicitDeclarationAgainstAStackSuppliedConnectionIsRefused(@TempDir Path dir)
            throws IOException {
        writeApplication(dir, "orders", "jdbc:postgresql://db:5432/one");
        Files.writeString(dir.resolve("orders/config/application.yml"), """
                tesseraql:
                  framework:
                    datasource: main
                """);
        writeApplication(dir, "billing", "jdbc:postgresql://db:5432/one");
        Files.writeString(dir.resolve(StackSettings.FILE_NAME), """
                framework:
                  datasource:
                    jdbcUrl: jdbc:postgresql://db:5432/stack
                """);

        assertThatThrownBy(() -> MultiAppHost.start(dir))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-APP-4212")
                .hasMessageContaining("orders")
                .hasMessageContaining("explicitly declares");
    }

    /** Two applications that agree, and no stack file: nothing to refuse before runtimes start. */
    @Test
    void agreeingCoordinatesPassTheGuard(@TempDir Path dir) throws IOException {
        writeApplication(dir, "orders", "jdbc:postgresql://db:5432/one");
        writeApplication(dir, "billing", "jdbc:postgresql://db:5432/one");

        // The guard passes and the start proceeds to real runtimes, which fail on the
        // unreachable database — proving the refusal above happened before any runtime, not
        // instead of one. Any TqlException here must NOT be the guard's.
        assertThatThrownBy(() -> MultiAppHost.start(dir))
                .satisfies(failure -> org.assertj.core.api.Assertions.assertThat(
                        String.valueOf(failure.getMessage()))
                        .doesNotContain("TQL-APP-4211")
                        .doesNotContain("TQL-APP-4212"));
    }

    private static void writeApplication(Path stack, String name, String jdbcUrl)
            throws IOException {
        Path config = stack.resolve(name).resolve("config");
        Files.createDirectories(config);
        Files.writeString(config.resolve("tesseraql.yml"), """
                tesseraql:
                  app:
                    name: %s
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: app
                      password: secret
                """.formatted(name, jdbcUrl));
    }
}
