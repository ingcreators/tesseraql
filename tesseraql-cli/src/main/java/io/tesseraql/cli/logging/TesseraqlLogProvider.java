package io.tesseraql.cli.logging;

import org.slf4j.ILoggerFactory;
import org.slf4j.IMarkerFactory;
import org.slf4j.helpers.BasicMDCAdapter;
import org.slf4j.helpers.BasicMarkerFactory;
import org.slf4j.spi.MDCAdapter;
import org.slf4j.spi.SLF4JServiceProvider;

/**
 * The CLI distribution's SLF4J provider (roadmap Phase 45): structured JSON or plain-text
 * lines on stderr, JDK-only in the spirit of the OIDC/mTLS/Prometheus decisions. Before it,
 * the standalone runtime shipped NO provider at all — every log line fell into SLF4J's NOP
 * sink. It lives in tesseraql-cli, not the runtime, so the Spring distribution keeps Boot's
 * Logback untouched. Configure via {@code -Dtesseraql.logging.format=text|json} and
 * {@code -Dtesseraql.logging.level=trace|debug|info|warn|error} (the serve command exposes
 * them as {@code --log-format}/{@code --log-level}); MDC rides {@link BasicMDCAdapter}, so
 * the runtime's trace-id correlation shows up in every line.
 */
public final class TesseraqlLogProvider implements SLF4JServiceProvider {

    private final TesseraqlLoggerFactory loggerFactory = new TesseraqlLoggerFactory();
    private final IMarkerFactory markerFactory = new BasicMarkerFactory();
    private final MDCAdapter mdcAdapter = new BasicMDCAdapter();

    @Override
    public ILoggerFactory getLoggerFactory() {
        return loggerFactory;
    }

    @Override
    public IMarkerFactory getMarkerFactory() {
        return markerFactory;
    }

    @Override
    public MDCAdapter getMDCAdapter() {
        return mdcAdapter;
    }

    @Override
    public String getRequestedApiVersion() {
        return "2.0.99";
    }

    @Override
    public void initialize() {
        // Settings resolve lazily per line, so a serve-command flag set just before the
        // runtime starts is honored even though the provider loads with the first logger.
    }
}
