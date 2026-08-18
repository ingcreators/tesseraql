package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.util.Hashing;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Decision 28's two start-time module guards (docs/module-scope.md): a production host runs
 * offline from what resolution left in {@code work/modules}, so declared-but-unresolved modules
 * and a directory the lock disagrees with refuse the start — the state that used to be running
 * the application silently without its declared functions, codecs and drivers. Both fire before
 * any runtime starts, which is what makes them testable without a database.
 */
class ModulesGuardTest {

    /**
     * Declared {@code tesseraql.modules} + an empty {@code work/modules} =
     * {@code TQL-APP-4216}, naming the application and the resolve command. Today's silence —
     * routes failing at parse, or quietly binding to nothing — is the fail-open shape this
     * refusal replaces.
     */
    @Test
    void declaredModulesWithNoJarsRefuseTheHost(@TempDir Path dir) throws IOException {
        writeApplication(dir, "orders", true);

        assertThatThrownBy(() -> MultiAppHost.start(dir))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-APP-4216")
                .hasMessageContaining("orders")
                .hasMessageContaining("tesseraql modules resolve");
    }

    /**
     * Jars on disk that disagree with {@code modules.lock} = {@code TQL-APP-4217}: the lock
     * pins the resolved closure by checksum, and the host will not guess which of the two is
     * the intended truth.
     */
    @Test
    void jarsDisagreeingWithTheLockRefuseTheHost(@TempDir Path dir) throws IOException {
        writeApplication(dir, "orders", true);
        writeModuleJar(dir, "orders", "fn.jar", "module bytes");
        Files.writeString(dir.resolve("orders/modules.lock"), """
                {"lockfileVersion":1,"modules":["io.example:fn"],
                 "artifacts":[{"coordinate":"io.example:fn:1","sha256":"%s"}]}
                """.formatted("0".repeat(64)));

        assertThatThrownBy(() -> MultiAppHost.start(dir))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-APP-4217")
                .hasMessageContaining("orders")
                .hasMessageContaining("tesseraql modules resolve");
    }

    /**
     * A directory the lock agrees with passes the guard, and the start proceeds to real
     * runtimes — proving the refusals above happen before any runtime, not instead of one. An
     * absent lock passes too: the lock is optional, and 4217 verifies rather than requires.
     */
    @Test
    void agreementAndAnAbsentLockPassTheGuardBeforeRuntimes(@TempDir Path dir)
            throws IOException {
        writeApplication(dir, "orders", true);
        Path jar = writeModuleJar(dir, "orders", "fn.jar", "module bytes");
        Files.writeString(dir.resolve("orders/modules.lock"), """
                {"lockfileVersion":1,"modules":["io.example:fn"],
                 "artifacts":[{"coordinate":"io.example:fn:1","sha256":"%s"}]}
                """.formatted(Hashing.sha256(jar)));
        writeApplication(dir, "billing", true);
        writeModuleJar(dir, "billing", "other.jar", "other bytes");

        assertThatThrownBy(() -> MultiAppHost.start(dir))
                .satisfies(failure -> assertThat(String.valueOf(failure.getMessage()))
                        .doesNotContain("TQL-APP-4216")
                        .doesNotContain("TQL-APP-4217"));
    }

    private static void writeApplication(Path stack, String name, boolean declaresModules)
            throws IOException {
        Path config = stack.resolve(name).resolve("config");
        Files.createDirectories(config);
        Files.writeString(config.resolve("tesseraql.yml"), """
                tesseraql:
                  app:
                    name: %s
                %s  datasources:
                    main:
                      jdbcUrl: jdbc:postgresql://db:5432/one
                      username: app
                      password: secret
                """.formatted(name, declaresModules ? "  modules:\n    - io.example:fn\n" : ""));
    }

    private static Path writeModuleJar(Path stack, String name, String jarName, String content)
            throws IOException {
        Path modules = stack.resolve(name).resolve("work/modules");
        Files.createDirectories(modules);
        Path jar = modules.resolve(jarName);
        Files.writeString(jar, content);
        return jar;
    }
}
