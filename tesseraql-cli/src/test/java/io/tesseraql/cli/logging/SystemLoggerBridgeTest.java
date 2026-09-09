package io.tesseraql.cli.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * {@code tesseraql-core} may not depend on SLF4J (AGENTS.md rules 2 and 3), so it logs through
 * {@code System.Logger} — and so do six other modules, at forty-nine call sites. Without a
 * {@code System.LoggerFinder} on the classpath those lines fall to the JDK's default, which is
 * JUL: a two-line non-JSON record with no MDC, unaffected by {@code --log-format} and
 * {@code --log-level}. Half the framework's own logging bypassed the provider the CLI ships.
 *
 * <p>The repair is one artifact rather than a conversion of the forty-nine sites, and it has to
 * be: `tesseraql-core` cannot be converted at all, so converting the rest would leave the split in
 * place while fixing nothing core emits.
 *
 * <p>These assertions go through {@code System.getLogger} deliberately. Asserting that the
 * dependency is present, or that {@code LoggerFinder.getLoggerFinder()} has a particular class,
 * would pass on a bridge that never actually routed a line.
 */
class SystemLoggerBridgeTest {

    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    private PrintStream original;

    @BeforeEach
    void capture() {
        original = TesseraqlLogger.stream;
        TesseraqlLogger.stream = new PrintStream(captured, true, StandardCharsets.UTF_8);
    }

    @AfterEach
    void restore() {
        TesseraqlLogger.stream = original;
        System.clearProperty("tesseraql.logging.format");
        System.clearProperty("tesseraql.logging.level");
        MDC.clear();
    }

    private String output() {
        return captured.toString(StandardCharsets.UTF_8);
    }

    @Test
    void aSystemLoggerLineIsFormattedByTheProviderTheCliShips() {
        MDC.put("traceId", "abc123");

        System.getLogger("io.tesseraql.core.Probe")
                .log(Level.WARNING, "JWKS fetch from {0} failed: {1}", "https://idp/jwks",
                        "timeout");

        assertThat(output())
                .contains("WARN")
                .contains("io.tesseraql.core.Probe")
                .contains("traceId=abc123")
                // MessageFormat placeholders, which is what System.Logger substitutes - not
                // SLF4J's {}. A bridge that passed the pattern through unsubstituted would fail
                // here.
                .contains("JWKS fetch from https://idp/jwks failed: timeout");
    }

    @Test
    void aSystemLoggerLineBecomesJsonWhenTheCliAsksForJson() {
        System.setProperty("tesseraql.logging.format", "json");
        MDC.put("traceId", "T-abc");

        System.getLogger("io.tesseraql.core.Probe").log(Level.ERROR, "scheduled job failed");

        assertThat(output())
                .contains("\"level\":\"ERROR\"")
                .contains("\"logger\":\"io.tesseraql.core.Probe\"")
                .contains("\"traceId\":\"T-abc\"")
                .contains("\"message\":\"scheduled job failed\"");
    }

    /**
     * The threshold has to move in both directions, and the two failures are different: with no
     * bridge an INFO line is emitted whatever {@code --log-level} says, and a DEBUG line is
     * suppressed whatever it says.
     */
    @Test
    void theCliLogLevelGovernsSystemLoggerLinesInBothDirections() {
        System.setProperty("tesseraql.logging.level", "error");
        System.getLogger("io.tesseraql.core.Probe").log(Level.INFO, "sweep starting");
        assertThat(output()).as("info is silenced at level=error").isEmpty();

        // The same call at the default threshold, so the silence above is a threshold decision
        // and not the line failing to arrive at all - which is exactly what it was before the
        // bridge existed, and would have made the assertion above pass for the wrong reason.
        System.setProperty("tesseraql.logging.level", "info");
        System.getLogger("io.tesseraql.core.Probe").log(Level.INFO, "sweep starting");
        assertThat(output()).as("the same line arrives at level=info").contains("sweep starting");

        System.setProperty("tesseraql.logging.level", "trace");
        System.Logger logger = System.getLogger("io.tesseraql.core.Probe");
        assertThat(logger.isLoggable(Level.DEBUG)).as("debug is enabled at level=trace").isTrue();
        logger.log(Level.DEBUG, "candidate row {0}", 7);
        assertThat(output()).contains("DEBUG").contains("candidate row 7");
    }
}
