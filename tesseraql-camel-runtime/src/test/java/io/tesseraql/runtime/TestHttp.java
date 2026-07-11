package io.tesseraql.runtime;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

/**
 * The integration suites' HTTP door, hardened against the hung-runner pattern (the
 * recurring 5-minute {@code StudioIntegrationTest} stalls of issue #341): a plain
 * {@code HttpClient.newHttpClient().send(...)} has no request timeout, so a response the
 * runtime never writes blocks the test thread until the JUnit interrupt fires — reporting
 * only the client side of the story. Every send here carries a 30-second request timeout,
 * and a trip dumps <em>every thread in the JVM</em> before failing: the runtime under test
 * lives in the same process, so the dump shows exactly what the server was (not) doing
 * when the response went missing.
 */
final class TestHttp {

    /** Comfortably above the suites' slowest real request (~10s), far below the 5m gate. */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private TestHttp() {
    }

    /** Sends with the suite's request timeout; a timeout fails WITH a full thread dump. */
    static HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        HttpRequest timed = request.timeout(REQUEST_TIMEOUT).build();
        try {
            return CLIENT.send(timed, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException timeout) {
            throw new AssertionError(
                    "No response within " + REQUEST_TIMEOUT.toSeconds() + "s for "
                            + timed.method() + " " + timed.uri()
                            + " — the runtime never answered (issue #341). All JVM threads at"
                            + " that moment:\n" + fullThreadDump(),
                    timeout);
        }
    }

    /**
     * Every thread with its full stack and lock state — the server side of a hung request,
     * same JVM. Formatted by hand because {@link ThreadInfo#toString()} truncates stacks
     * to eight frames, and the interesting frame is rarely in the first eight.
     */
    private static String fullThreadDump() {
        StringBuilder dump = new StringBuilder();
        for (ThreadInfo thread : ManagementFactory.getThreadMXBean()
                .dumpAllThreads(true, true)) {
            dump.append('"').append(thread.getThreadName()).append("\" ")
                    .append(thread.getThreadState());
            if (thread.getLockName() != null) {
                dump.append(" on ").append(thread.getLockName());
                if (thread.getLockOwnerName() != null) {
                    dump.append(" owned by \"").append(thread.getLockOwnerName()).append('"');
                }
            }
            dump.append('\n');
            for (StackTraceElement frame : thread.getStackTrace()) {
                dump.append("    at ").append(frame).append('\n');
            }
            dump.append('\n');
        }
        return dump.toString();
    }
}
