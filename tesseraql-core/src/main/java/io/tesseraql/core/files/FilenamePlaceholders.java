package io.tesseraql.core.files;

import io.tesseraql.core.expr.EvaluationContext;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The placeholder grammar of a delivered or downloaded file name, and its one resolver:
 * {@code {dotted.path}} over letters, digits, {@code _} and {@code .}, resolved against the
 * context of the site that fixes the name — a job step's ({@code {batch.businessDate}},
 * {@code {steps.report.filename}}) or a route's request ({@code {params.month}},
 * {@code {path.id}}) — with every value folded to a filename component the way a split key is
 * (docs/route-filename-placeholders.md decisions 2 and 4).
 *
 * <p>One pattern for the runtimes that interpolate the name and the lint that judges it: the
 * lint used to accept any brace-delimited text, so {@code {batch.business-date}} passed as a
 * context path and was delivered literally. {@link SplitExport#KEY} is not this class's: it
 * passes through {@link #resolve} untouched and the split writer replaces it per group.
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

    /**
     * The template with every {@link #RESOLVED} placeholder replaced by its value, looked up as
     * a dotted path in {@code evaluation} and folded by the split key's rule ({@code 2026/09}
     * is {@code 2026_09}, an absent value is {@code _}, a value is cut at a hundred graphemes);
     * {@code {key}} is appended as itself. A spelling the grammar does not match stays in the
     * name literally, braces on — the shape the lint refuses. A template with no brace is
     * returned as is.
     */
    public static String resolve(String template, EvaluationContext evaluation) {
        if (template == null || template.indexOf('{') < 0) {
            return template;
        }
        Matcher matcher = RESOLVED.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String replacement;
            if (SplitExport.KEY.equals(matcher.group())) {
                replacement = matcher.group();
            } else {
                Object value = evaluation.resolve(Arrays.asList(matcher.group(1).split("\\.")));
                replacement = SplitExport.safe(value == null ? "" : value);
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        return matcher.appendTail(out).toString();
    }
}
