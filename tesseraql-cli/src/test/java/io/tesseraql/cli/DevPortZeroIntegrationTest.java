package io.tesseraql.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.yaml.scaffold.AppScaffolder;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code dev --port 0} gives its applications the port its socket got (docs/host-development.md
 * decision 7). The development gateway's origin is its own address, and it used to be built from
 * the number asked for before anything had bound: under {@code --port 0} every member booted at
 * {@code http://localhost:0} — the stack issuer, the session tokens and the MCP surface all said
 * so — while the console printed the real port. The front now binds before the members boot, and
 * the address is left at {@code work/dev.origin} for a session that did not read the console.
 *
 * <p>Forked, because {@code dev} parks until interrupted; Linux and macOS only, like every case
 * that stops a running {@code dev} (see {@link DevNarrowingIntegrationTest}).
 */
@EnabledOnOs({OS.LINUX, OS.MAC})
class DevPortZeroIntegrationTest {

    // HTTP/1.1 pinned: the JDK client's h2c upgrade against the gateway is its own flake
    // (OpsConsoleIntegrationTest, #1401), and nothing here is about the protocol.
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private static final ObjectMapper MAPPER = io.tesseraql.yaml.JsonMappers.constrained();

    @Test
    void theOriginTheApplicationsAreGivenIsThePortTheSocketGot(@TempDir Path dir)
            throws Exception {
        Path stack = stackOf(dir, """
                # a stack whose authorization server names the development gateway's origin
                security:
                  oauth:
                    enabled: true
                  token:
                    enabled: true
                """);
        dropTheScaffoldsKeySource(stack.resolve("alpha"));
        Path marker = stack.resolve("alpha/work/dev.origin");
        ForkedCli cli = ForkedCli.fork(dir, "dev", "--stack", stack.toString(), "--port", "0",
                "--embedded-db", "--offline");
        try {
            int port = cli.awaitGatewayPort();
            String origin = "http://localhost:" + port;

            assertThat(port).as("a bound port, not the number asked for").isPositive();
            HttpResponse<String> metadata = get(origin + "/.well-known/oauth-authorization-server");
            assertThat(metadata.statusCode()).as(metadata.body()).isEqualTo(200);
            assertThat(MAPPER.readTree(metadata.body()).get("issuer").asString())
                    .as("the stack issuer is the gateway's own address; it was http://localhost:0")
                    .isEqualTo(origin);
            assertThat(Files.readString(marker).strip())
                    .as("where this run answers, for a reader that did not see the console")
                    .isEqualTo(origin);
        } finally {
            cli.kill();
        }
        assertThat(marker).as("a graceful stop takes the marker with it").doesNotExist();
    }

    /**
     * The case the flag exists for: two sessions, each running {@code dev} on its own worktree,
     * on one machine, at once. A fixed default would refuse the second.
     */
    @Test
    void twoRunsOnPortZeroShareOneMachine(@TempDir Path dir) throws Exception {
        Path oneDir = Files.createDirectories(dir.resolve("one"));
        Path twoDir = Files.createDirectories(dir.resolve("two"));
        Path one = stackOf(oneDir, "# the first session's stack\n");
        Path two = stackOf(twoDir, "# the second session's stack\n");
        ForkedCli first = ForkedCli.fork(oneDir, "dev", "--stack", one.toString(), "--port", "0",
                "--embedded-db", "--offline");
        ForkedCli second = ForkedCli.fork(twoDir, "dev", "--stack", two.toString(), "--port",
                "0", "--embedded-db", "--offline");
        try {
            int firstPort = first.awaitGatewayPort();
            int secondPort = second.awaitGatewayPort();

            assertThat(firstPort).isNotEqualTo(secondPort);
            for (int port : new int[]{firstPort, secondPort}) {
                HttpResponse<String> ready = get(
                        "http://localhost:" + port + "/_tesseraql/health/ready");
                assertThat(ready.statusCode()).as("port %d: %s", port, ready.body())
                        .isEqualTo(200);
            }
            assertThat(Files.readString(one.resolve("alpha/work/dev.origin")).strip())
                    .isEqualTo("http://localhost:" + firstPort);
            assertThat(Files.readString(two.resolve("alpha/work/dev.origin")).strip())
                    .isEqualTo("http://localhost:" + secondPort);
        } finally {
            first.kill();
            second.kill();
        }
    }

    /** One scaffolded member under a stack file carrying {@code stackFile}. */
    private static Path stackOf(Path dir, String stackFile) throws IOException {
        Path stack = Files.createDirectories(dir.resolve("stack"));
        Files.writeString(stack.resolve("tesseraql-stack.yml"), stackFile);
        AppScaffolder scaffolder = new AppScaffolder();
        scaffolder.writeNew(stack.resolve("alpha"), scaffolder.scaffold("alpha"));
        return stack;
    }

    /**
     * Under the stack's authorization server there is one issuer, so the scaffold's own signing
     * secret is refused as a second (TQL-OAUTH-3001); its audience and claim names stay.
     */
    private static void dropTheScaffoldsKeySource(Path app) throws IOException {
        boolean dropped = false;
        try (Stream<Path> files = Files.walk(app.resolve("config"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".yml")).toList()) {
                String text = Files.readString(file);
                String without = text.replaceAll("(?m)^[ \\t]*secret: \\$\\{JWT_SECRET[^\\n]*\\n",
                        "");
                if (!without.equals(text)) {
                    Files.writeString(file, without);
                    dropped = true;
                }
            }
        }
        assertThat(dropped).as("the scaffold's jwt secret line, which this case removes").isTrue();
    }

    private static HttpResponse<String> get(String url) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create(url)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
