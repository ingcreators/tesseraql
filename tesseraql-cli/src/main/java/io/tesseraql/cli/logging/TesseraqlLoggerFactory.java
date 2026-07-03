package io.tesseraql.cli.logging;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;

/** One {@link TesseraqlLogger} per name (roadmap Phase 45). */
public final class TesseraqlLoggerFactory implements ILoggerFactory {

    private final ConcurrentMap<String, Logger> loggers = new ConcurrentHashMap<>();

    @Override
    public Logger getLogger(String name) {
        return loggers.computeIfAbsent(name, TesseraqlLogger::new);
    }
}
