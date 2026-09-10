package io.tesseraql.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import io.tesseraql.yaml.scaffold.AppScaffolder;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code dev --embedded-db} stops the database after the runtimes have let go of it, and never
 * leaves one running (docs/audit-medium-leads.md, F113).
 *
 * <p><strong>Every case forks a JVM and sends it a real signal.</strong> The defect is that two
 * shutdown hooks — the CLI's and the one the embedded-postgres library used to register for itself
 * — run <em>concurrently</em>, so the library stopped the server while the gateway was still
 * draining. Hook concurrency exists only in a real JVM shutdown: a test that calls {@code close()}
 * itself, or that runs the hooks in an order it chose, cannot see this defect at all.
 *
 * <p><strong>An idle stack proves nothing here.</strong> On the unfixed code the postmaster stops
 * about 105 ms after the signal and the whole ordered close finishes at about 21 ms, so the natural
 * "the postmaster line comes last" assertion is <em>green on the defect</em>. A request held in the
 * database is what makes the order observable, which is why the first case holds one.
 *
 * <p>Linux and macOS only: {@link Process#destroy()} is {@code TerminateProcess} on Windows and
 * runs no shutdown hooks at all, so none of this is expressible there.
 */
@EnabledOnOs({OS.LINUX, OS.MAC})
class EmbeddedDbShutdownIntegrationTest {

    /** The port a running postmaster announces on the fourth line of its own pid file. */
    private static final int PID_FILE_PORT_LINE = 4;

    private static final Pattern GATEWAY_PORT = Pattern.compile("app\\(s\\) on port (\\d+)");

    /**
     * The defect. A request is held in the database when the signal arrives; it must be allowed to
     * finish, and the log must not show PostgreSQL cutting it off.
     *
     * <p>The two log assertions key on PostgreSQL's own {@code 57P01} and its message, never on the
     * framework's error code: any {@code TQL-} literal in a source file — comments included — is
     * indexed as a raise site of that code in the generated error reference.
     */
    @Test
    void aRequestHeldInTheDatabaseOutlivesTheSignalThatStopsTheStack(@TempDir Path dir)
            throws Exception {
        Path stack = stackWithASlowRoute(dir, 8);
        Forked cli = fork(dir, "dev", "--stack", stack.toString(), "--port", "0", "--embedded-db");
        try {
            int port = cli.awaitGatewayPort();
            HttpClient client = HttpClient.newHttpClient();
            CompletableFuture<HttpResponse<String>> slow = client.sendAsync(
                    HttpRequest
                            .newBuilder(URI.create("http://localhost:" + port + "/demo/api/slow"))
                            .timeout(Duration.ofSeconds(60)).build(),
                    HttpResponse.BodyHandlers.ofString());

            // Long enough that the request is genuinely inside pg_sleep, short enough that it has
            // seconds left to run — the window the unfixed code kills it in is ~105 ms.
            Thread.sleep(2500);
            cli.process.destroy();

            HttpResponse<String> answered = slow.get(60, TimeUnit.SECONDS);
            cli.awaitExit();
            String log = cli.log();

            assertThat(log)
                    .as("the gateway saw the request and drained for it")
                    .contains("draining 1 in-flight request(s)");
            assertThat(answered.statusCode())
                    .as("the request held in the database was allowed to finish")
                    .isEqualTo(200);
            assertThat(answered.body()).contains("\"ok\"");
            assertThat(log)
                    .as("and PostgreSQL did not cut it off from under the drain")
                    .doesNotContain("57P01")
                    .doesNotContain("terminating connection due to administrator command");
        } finally {
            cli.kill();
        }
    }

    /**
     * The window the library's own hook used to cover and no hoisted hook can reach: it registers
     * <em>inside</em> the start, after spawning the postmaster and before waiting for readiness.
     * Interrupting there must not leave a server running.
     *
     * <p>Retries the window a few times and then fails. It never skips: a case that quietly passes
     * when it could not set itself up is indistinguishable from one that works.
     */
    @Test
    void anInterruptWhileTheDatabaseIsStartingDoesNotLeaveOneRunning(@TempDir Path dir)
            throws Exception {
        Path stack = stackWithASlowRoute(dir, 1);
        for (int attempt = 1; attempt <= 3; attempt++) {
            Path data = Files.createDirectories(dir.resolve("pgdata" + attempt));
            Forked cli = fork(dir, "dev", "--stack", stack.toString(), "--port", "0",
                    "--embedded-db", data.toString());
            try {
                if (!cli.awaitWindow("postmaster started as", "Embedded PostgreSQL")) {
                    continue;
                }
                cli.process.destroy();
                cli.awaitExit();
                assertThat(servingPort(data))
                        .as("no PostgreSQL is left serving %s after an interrupt during its"
                                + " startup.%n--- the interrupted run's own log ---%n%s",
                                data, cli.log())
                        .isNull();
                return;
            } finally {
                cli.kill();
            }
        }
        fail("could not land an interrupt inside the startup window in three attempts — the case"
                + " did not run, which is not the same as passing");
    }

    /**
     * The same window, reached with no signal and no timing at all: the everyday mistake of
     * running {@code dev} while the gateway port is taken. The gateway refuses after the database
     * is up, and the command must not exit leaving it behind.
     */
    @Test
    void aGatewayPortAlreadyInUseDoesNotLeaveTheDatabaseRunning(@TempDir Path dir)
            throws Exception {
        Path stack = stackWithASlowRoute(dir, 1);
        Path data = Files.createDirectories(dir.resolve("pgdata"));
        try (ServerSocket taken = new ServerSocket(0)) {
            Forked cli = fork(dir, "dev", "--stack", stack.toString(),
                    "--port", String.valueOf(taken.getLocalPort()),
                    "--embedded-db", data.toString());
            try {
                int exit = cli.awaitExit();
                String log = cli.log();
                assertThat(exit).as("the command failed rather than running.%n%s", log)
                        .isNotZero();
                // From the run's own log: a clean stop takes the pid file with it, so the
                // directory cannot say afterwards which port there was something to leak on.
                Matcher announced = Pattern.compile("local client on port (\\d+)").matcher(log);
                assertThat(announced.find())
                        .as("the database did start, so there is something to leak.%n%s", log)
                        .isTrue();
                assertThat(accepts(Integer.parseInt(announced.group(1))))
                        .as("and it was stopped when the gateway could not bind.%n%s", log)
                        .isFalse();
            } finally {
                cli.kill();
            }
        }
    }

    /**
     * The window this change opens for itself. Turning the library's hook off leaves the callers
     * that never close their handle — thirteen of them in this module's own tests — with no net at
     * all, so the support class registers one of its own.
     */
    @Test
    void aStartWhoseCallerNeverClosesDoesNotOutliveTheProcess(@TempDir Path dir) throws Exception {
        Forked jvm = fork(dir, ForgetfulStart.class.getName());
        try {
            assertThat(jvm.awaitExit()).as("the forgetful process exited").isZero();
            Matcher port = Pattern.compile("FORGOTTEN-PORT=(\\d+)").matcher(jvm.log());
            assertThat(port.find()).as("the fork reported the port it started on").isTrue();
            assertThat(accepts(Integer.parseInt(port.group(1))))
                    .as("a start nobody closed is stopped when its process ends")
                    .isFalse();
        } finally {
            jvm.kill();
        }
    }

    /** Starts an embedded instance, announces its port, and returns without closing it. */
    static final class ForgetfulStart {
        public static void main(String[] args) {
            EmbeddedPostgresSupport.Handle handle = EmbeddedPostgresSupport.start(null, false);
            String url = handle.override().jdbcUrl();
            System.out.println("FORGOTTEN-PORT="
                    + url.substring(url.lastIndexOf(':') + 1, url.lastIndexOf('/')));
            System.out.flush();
        }
    }

    // ---- harness ---------------------------------------------------------------------------

    /** A forked JVM whose merged output is on disk, so a test can poll it while it runs. */
    private record Forked(Process process, Path output) {

        String log() throws IOException {
            return Files.exists(output) ? Files.readString(output) : "";
        }

        int awaitGatewayPort() throws Exception {
            long deadline = System.currentTimeMillis() + 120_000;
            while (System.currentTimeMillis() < deadline) {
                Matcher matcher = GATEWAY_PORT.matcher(log());
                if (matcher.find()) {
                    return Integer.parseInt(matcher.group(1));
                }
                if (!process.isAlive()) {
                    throw new IllegalStateException("the fork died before it announced:\n" + log());
                }
                Thread.sleep(100);
            }
            throw new IllegalStateException("no gateway port announced:\n" + log());
        }

        /** True once {@code opened} has been logged and {@code closed} has not — a live window. */
        boolean awaitWindow(String opened, String closed) throws Exception {
            long deadline = System.currentTimeMillis() + 120_000;
            while (System.currentTimeMillis() < deadline && process.isAlive()) {
                String log = log();
                if (log.contains(opened)) {
                    return !log.contains(closed);
                }
                Thread.sleep(20);
            }
            return false;
        }

        int awaitExit() throws Exception {
            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the fork did not exit:\n" + log());
            }
            return process.exitValue();
        }

        /**
         * Ends the fork, giving it the chance to run its own shutdown first.
         *
         * <p>Not {@code destroyForcibly()} alone. This class forks a command whose whole job is to
         * own a PostgreSQL, so killing it outright orphans one — for the rest of the module, which
         * boots thirteen more. A case that leaks the very thing the code under test stops is a case
         * that makes its neighbours flaky.
         */
        void kill() {
            if (!process.isAlive()) {
                return;
            }
            process.destroy();
            try {
                if (!process.waitFor(60, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }

    private static Forked fork(Path dir, String... command) throws IOException {
        Path output = Files.createTempFile(dir, "fork", ".log");
        java.util.List<String> line = new java.util.ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path")));
        if (command.length > 0 && command[0].contains(".")) {
            line.add(command[0]);
            line.addAll(List.of(command).subList(1, command.length));
        } else {
            line.add(TesseraqlCli.class.getName());
            line.addAll(List.of(command));
        }
        Process process = new ProcessBuilder(line)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start();
        return new Forked(process, output);
    }

    /** A stack of one scaffolded application plus a route that holds a connection for a while. */
    private static Path stackWithASlowRoute(Path dir, int seconds) throws IOException {
        Path stack = Files.createDirectories(dir.resolve("stack"));
        Files.writeString(stack.resolve("tesseraql-stack.yml"), "# a stack for one test app\n");
        Path app = stack.resolve("demo");
        AppScaffolder scaffolder = new AppScaffolder();
        scaffolder.writeNew(app, scaffolder.scaffold("demo"));
        Path slow = Files.createDirectories(app.resolve("web/api/slow"));
        Files.writeString(slow.resolve("slow.sql"),
                "select 1 as ok\nfrom (select pg_sleep(" + seconds + ")) as napping\n");
        Files.writeString(slow.resolve("get.yml"), """
                version: tesseraql/v1
                id: slow.read
                kind: route
                recipe: query-json
                security:
                  auth: public
                sources:
                  main:
                    sql:
                      file: slow.sql
                      mode: query
                response:
                  json:
                    body:
                      data: main.rows
                """);
        return stack;
    }

    /**
     * The port a server is still serving this directory on, or null when none is. A clean stop
     * removes the pid file with the postmaster, so an absent file is itself the answer.
     */
    private static Integer servingPort(Path dataDirectory) throws IOException {
        Integer port = announcedPort(dataDirectory);
        return port != null && accepts(port) ? port : null;
    }

    /** The port a data directory's own {@code postmaster.pid} announces, or null when there is none. */
    private static Integer announcedPort(Path dataDirectory) throws IOException {
        Path pidFile = dataDirectory.resolve("postmaster.pid");
        if (!Files.isRegularFile(pidFile)) {
            return null;
        }
        List<String> lines = Files.readAllLines(pidFile);
        if (lines.size() < PID_FILE_PORT_LINE) {
            return null;
        }
        try {
            return Integer.parseInt(lines.get(PID_FILE_PORT_LINE - 1).trim());
        } catch (NumberFormatException notAPort) {
            return null;
        }
    }

    private static boolean accepts(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", port), 1000);
            return true;
        } catch (IOException refused) {
            return false;
        }
    }
}
