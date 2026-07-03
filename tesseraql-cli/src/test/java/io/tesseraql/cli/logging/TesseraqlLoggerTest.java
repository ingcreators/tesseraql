package io.tesseraql.cli.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class TesseraqlLoggerTest {

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
    void textLinesCarryLevelLoggerMdcAndFormattedMessage() {
        MDC.put("traceId", "abc123");
        new TesseraqlLogger("io.tesseraql.Test").info("hello {} ({})", "world", 42);

        assertThat(output()).contains("INFO").contains("io.tesseraql.Test")
                .contains("traceId=abc123").contains("hello world (42)");
    }

    @Test
    void jsonLinesAreStructuredAndEscaped() {
        System.setProperty("tesseraql.logging.format", "json");
        MDC.put("traceId", "abc123");
        new TesseraqlLogger("io.tesseraql.Test").warn("a \"quoted\"\nvalue");

        String line = output().trim();
        assertThat(line).startsWith("{").endsWith("}")
                .contains("\"level\":\"WARN\"")
                .contains("\"logger\":\"io.tesseraql.Test\"")
                .contains("\"traceId\":\"abc123\"")
                .contains("\\\"quoted\\\"").contains("\\n");
    }

    @Test
    void theThresholdFiltersAndErrorsCarryTheStack() {
        System.setProperty("tesseraql.logging.level", "warn");
        TesseraqlLogger logger = new TesseraqlLogger("t");
        logger.info("dropped");
        logger.error("kept", new IllegalStateException("boom"));

        assertThat(output()).doesNotContain("dropped")
                .contains("kept").contains("IllegalStateException").contains("boom");
        assertThat(logger.isInfoEnabled()).isFalse();
        assertThat(logger.isWarnEnabled()).isTrue();
    }
}
