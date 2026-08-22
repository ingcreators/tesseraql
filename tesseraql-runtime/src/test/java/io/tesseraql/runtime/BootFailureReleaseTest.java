package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A failed boot releases what it took — before the old {@code try} as well as inside it
 * (docs/boot-phases.md slice 4). The prefix used to build every pool and then propagate a
 * refusal raw, leaving Hikari's housekeeper threads (and their connections) alive for the rest
 * of the process; {@code RuntimePools} now releases its own partial work, and everything after
 * it fails into the catch that releases the record. The observable is the pool's own threads:
 * Hikari names them after the pool, so a leak is a thread that outlives the refusal.
 */
@Testcontainers
class BootFailureReleaseTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    /** The refusal inside the pools phase itself: pools built, then the name lookup fails. */
    @Test
    void aRefusalInsideThePoolsPhaseLeavesNoPoolBehind(@TempDir Path dir) throws Exception {
        Path appHome = appHome(dir, "boot-leak-a", """
                  framework:
                    datasource: nope
                """);

        // A pools-phase refusal keeps its own message (the boot's wrapper applies only after
        // the phase hands its record back) - the key-naming contract the threading suite pins.
        assertThatThrownBy(() -> TesseraqlRuntime.start(appHome, 0))
                .hasMessageContaining("nope");

        assertThat(poolThreadsGone("tesseraql-main")).isTrue();
    }

    /** A refusal after the pools phase: the boot's catch releases the handed-back record. */
    @Test
    void aRefusalAfterThePoolsPhaseReleasesTheRecord(@TempDir Path dir) throws Exception {
        Path appHome = appHome(dir, "boot-leak-b", """
                  http:
                    workerThreads: not-a-number
                """);

        assertThatThrownBy(() -> TesseraqlRuntime.start(appHome, 0))
                .hasMessageContaining("Failed to start");

        assertThat(poolThreadsGone("tesseraql-main")).isTrue();
    }

    /** Hikari's threads carry the pool name; closed pools take theirs with them. */
    private static boolean poolThreadsGone(String poolName) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            boolean alive = Thread.getAllStackTraces().keySet().stream()
                    .anyMatch(thread -> thread.getName().startsWith(poolName));
            if (!alive) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }

    private static Path appHome(Path dir, String name, String tesseraqlTail) throws IOException {
        Path target = dir.resolve(name);
        Files.createDirectories(target.resolve("config"));
        Files.writeString(target.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: %s
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                %s""".formatted(name, POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword(), tesseraqlTail));
        return target;
    }

}
