package io.tesseraql.core.files;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The process-wide compiled-pattern cache a declared {@code pattern:} is matched through.
 *
 * <p>It lives in core because two surfaces match against the same declarations and must not
 * disagree: the request binder checks one submitted value, and a reviewed import checks one
 * column of every row in a file. Compiling per row would be the difference between a cheap pass
 * and a slow one; compiling in two places would be two caches with one purpose.
 *
 * <p>Syntax is a build-time concern — the lint refuses a malformed pattern where it is written —
 * so a compile failure here is not the diagnostic path and is left to propagate.
 */
public final class FieldPatterns {

    private static final Map<String, Pattern> PATTERNS = new ConcurrentHashMap<>();

    private FieldPatterns() {
    }

    public static Pattern compiled(String pattern) {
        return PATTERNS.computeIfAbsent(pattern, Pattern::compile);
    }
}
