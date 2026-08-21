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
 * Decision 24's start-time guard (docs/root-portal.md): {@code root.redirect} naming an
 * application the stack does not hold is {@code TQL-APP-4215}, refused before any runtime
 * starts — which is what makes it testable without a database — and validated against the full
 * membership, before {@code --app-name} narrowing, because the file describes the stack and the
 * flag filters a run.
 */
class GatewayRootRedirectTest {

    @Test
    void anUnknownRootRedirectTargetRefusesTheStart(@TempDir Path dir) throws IOException {
        writeApplication(dir, "orders");
        writeApplication(dir, "billing");
        Files.writeString(dir.resolve(StackSettings.FILE_NAME), """
                root:
                  redirect: shipping
                """);

        assertThatThrownBy(() -> MultiAppGateway.start(dir, 0))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-APP-4215")
                .hasMessageContaining("shipping")
                .hasMessageContaining("orders")
                .hasMessageContaining("billing")
                .hasMessageContaining(StackSettings.FILE_NAME);
    }

    /** A held name passes the guard; narrowing to a different member does not re-refuse it. */
    @Test
    void aHeldNamePassesTheGuardEvenUnderNarrowing(@TempDir Path dir) throws IOException {
        writeApplication(dir, "orders");
        writeApplication(dir, "billing");
        Files.writeString(dir.resolve(StackSettings.FILE_NAME), """
                root:
                  redirect: orders
                """);

        // The guard passes and the start proceeds to real runtimes, which fail on the
        // unreachable database — proving the refusal above happens before any runtime, and that
        // a valid name narrowed away is not a refusal (it 404s at request time instead, like
        // every other link to a narrowed-away neighbour).
        assertThatThrownBy(() -> MultiAppGateway.start(dir, 0,
                new MultiAppGateway.Settings(), "billing"))
                .satisfies(failure -> org.assertj.core.api.Assertions.assertThat(
                        String.valueOf(failure.getMessage()))
                        .doesNotContain("TQL-APP-4215"));
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
                      jdbcUrl: jdbc:postgresql://unreachable:5432/one
                      username: app
                      password: secret
                """.formatted(name));
    }
}
