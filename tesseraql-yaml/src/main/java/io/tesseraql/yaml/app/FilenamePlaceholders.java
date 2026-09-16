package io.tesseraql.yaml.app;

import java.util.regex.Pattern;

/**
 * The placeholder grammar of a job's delivered or exported file name: {@code {dotted.path}}
 * over letters, digits, {@code _} and {@code .}, resolved against the job context
 * ({@code {batch.businessDate}}, {@code {steps.report.filename}}).
 *
 * <p>One pattern for the runtime that interpolates the name and the lint that judges it
 * (docs/audit-low-leads.md, the {@code push.as:} grammar): the lint used to accept any
 * brace-delimited text, so {@code {batch.business-date}} passed as a context path and was
 * delivered literally.
 */
public final class FilenamePlaceholders {

    /** A placeholder the runtime resolves; group 1 is the dotted path. */
    public static final Pattern RESOLVED = Pattern.compile("\\{([\\p{L}\\p{N}_.]+)}");

    /** Any brace-delimited text an author may have meant as a placeholder; group 1 is the text. */
    public static final Pattern WRITTEN = Pattern.compile("\\{([^{}]+)}");

    private FilenamePlaceholders() {
    }

    /** Whether the runtime resolves {@code {<path>}} — the text the wide pattern captured. */
    public static boolean resolves(String path) {
        return RESOLVED.matcher("{" + path + "}").matches();
    }
}
