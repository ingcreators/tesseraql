package io.tesseraql.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A forked CLI whose merged output is on disk, so a test can poll it while it runs. For the commands
 * that park until interrupted ({@code dev}) or whose defect lives in a real JVM shutdown — hook
 * concurrency, a signal — which no in-process call can reach.
 */
record ForkedCli(Process process, Path output) {

    private static final Pattern GATEWAY_PORT = Pattern.compile("app\\(s\\) on port (\\d+)");

    /**
     * Forks {@code command} with this JVM's classpath: a subcommand line for {@link TesseraqlCli},
     * or — when the first word names a class — that class's {@code main}.
     *
     * <p>The classpath travels in a launcher argument file: the test classpath is a few hundred
     * jars, which is longer than a Windows command line may be. Written with forward slashes,
     * which the JVM accepts on every platform and the argument file's quoting does not escape.
     */
    static ForkedCli fork(Path dir, String... command) throws IOException {
        Path output = Files.createTempFile(dir, "fork", ".log");
        Path options = Files.createTempFile(dir, "fork", ".args");
        Files.writeString(options, "-cp \""
                + System.getProperty("java.class.path").replace('\\', '/') + "\"\n");
        List<String> line = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "@" + options));
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
        return new ForkedCli(process, output);
    }

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

    /** Waits until the log carries {@code line}; the fork dying first is the failure it is. */
    void awaitLine(String line) throws Exception {
        long deadline = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < deadline) {
            if (log().contains(line)) {
                return;
            }
            if (!process.isAlive()) {
                throw new IllegalStateException(
                        "the fork died before it printed '" + line + "':\n" + log());
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("'" + line + "' was never printed:\n" + log());
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
     * <p>Not {@code destroyForcibly()} alone. The commands forked here own a PostgreSQL, so killing
     * one outright orphans it — for the rest of the module, which boots thirteen more. A case that
     * leaks the very thing the code under test stops is a case that makes its neighbours flaky.
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
