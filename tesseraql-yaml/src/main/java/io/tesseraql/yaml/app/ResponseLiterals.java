package io.tesseraql.yaml.app;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.yaml.app.ExportDeclarations.Kind;
import io.tesseraql.yaml.app.ExportDeclarations.Violation;
import io.tesseraql.yaml.model.ResponseSpec;
import io.tesseraql.yaml.model.RouteDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The response literals the edge cannot honour as written (docs/audit-low-leads.md EH-06),
 * judged where they are declared: a {@code response.file.contentType} whose {@code charset=}
 * names an encoding the body is not written in, and a {@code response.redirect.location} with
 * whitespace at either end. Reported at lint and refused at boot from this one predicate.
 *
 * <p>A file response's text body is encoded as UTF-8 wherever it is written — the edge's
 * {@code Buffer.buffer(String)} consults no parameter — so {@code charset=Shift_JIS} over UTF-8
 * bytes was a lint-clean 200 whose header contradicted its body. Honouring a declared encoding
 * would be a new declaration (csv-import.md decision 10's shape: an encoding is its own key,
 * never read out of a Content-Type parameter), not a renderer patch. A redirect location is a
 * quoted literal, so a space at its end reached the wire as {@code %20}
 * (download-name-and-bytes.md decision 13, the trailing case), while a space at its start
 * defeats the base-path join — a value that does not start with {@code /} is left alone — and
 * the browser resolves {@code %20/x} relative to the current page, outside the app.
 */
public final class ResponseLiterals {

    /**
     * TQL-YAML-1073: {@code response.file.contentType} declares a {@code charset=} the body is
     * not written in. The file response encodes its text as UTF-8; declare {@code charset=utf-8}
     * or omit the parameter.
     */
    public static final TqlErrorCode CHARSET_NOT_WRITTEN = new TqlErrorCode(TqlDomain.YAML, 1073);

    /**
     * TQL-YAML-1074: {@code response.redirect.location} starts or ends with whitespace. The
     * literal is written as given — a trailing space is {@code %20} on the wire, a leading one
     * keeps the base path off the value and the redirect leaves the application.
     */
    public static final TqlErrorCode LOCATION_WHITESPACE = new TqlErrorCode(TqlDomain.YAML, 1074);

    /** The spellings of the one encoding the body is written in. */
    private static final Set<String> UTF_8 = Set.of("utf-8", "utf8");

    private ResponseLiterals() {
    }

    /**
     * Every response literal of the document the edge would not honour as written, and every
     * download-name placeholder the request cannot resolve ({@link FilenameTemplates});
     * {@code urlPath} is the route's URL template (a consumer's or a tool's is {@code null}).
     */
    public static List<Violation> violations(String app, RouteDefinition definition,
            String urlPath) {
        List<Violation> out = new ArrayList<>();
        ResponseSpec response = definition.response();
        if (response == null) {
            return out;
        }
        String head = "app '" + ExportDeclarations.bounded(app) + "': route '"
                + ExportDeclarations.bounded(definition.id()) + "' ";
        ExportDeclarations.Site site = ExportDeclarations.Site.route(app, definition, urlPath);
        if (response.file() != null) {
            // A file response renders after the route's sources ran, so their names resolve
            // too (docs/route-filename-placeholders.md decision 3).
            out.addAll(FilenameTemplates.violations(site, "response.file.filename",
                    response.file().filename(), definition.sources().keySet()));
            String charset = declaredCharset(response.file().contentType());
            if (charset != null && !UTF_8.contains(charset.toLowerCase(Locale.ROOT))) {
                out.add(new Violation(CHARSET_NOT_WRITTEN, Kind.INVALID,
                        "response.file.contentType", head + "response.file.contentType: '"
                                + ExportDeclarations.bounded(response.file().contentType())
                                + "' declares charset=" + ExportDeclarations.bounded(charset)
                                + ", and the body is written as UTF-8 - declare charset=utf-8"
                                + " or omit the parameter"));
            }
        }
        if (response.stream() != null) {
            out.addAll(FilenameTemplates.violations(site, "response.stream.filename",
                    response.stream().filename(), java.util.Set.of()));
        }
        if (response.redirect() != null) {
            String location = response.redirect().location();
            if (location != null && !location.isEmpty() && !location.equals(location.strip())) {
                boolean leading = !location.isEmpty()
                        && Character.isWhitespace(location.charAt(0));
                out.add(new Violation(LOCATION_WHITESPACE, Kind.INVALID,
                        "response.redirect.location", head + "response.redirect.location: '"
                                + ExportDeclarations.bounded(location) + "' has whitespace at its"
                                + (leading ? " start" : " end") + " - the literal is written as"
                                + " given, so a trailing space is %20 on the wire and a leading"
                                + " one keeps the base path off a value that does not start"
                                + " with /"));
            }
        }
        return out;
    }

    /** The {@code charset} parameter's value, quotes stripped, or {@code null} when none is declared. */
    static String declaredCharset(String contentType) {
        if (contentType == null) {
            return null;
        }
        String[] parts = contentType.split(";");
        for (int i = 1; i < parts.length; i++) {
            String parameter = parts[i].strip();
            int equals = parameter.indexOf('=');
            if (equals > 0 && "charset".equalsIgnoreCase(parameter.substring(0, equals).strip())) {
                String value = parameter.substring(equals + 1).strip();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                return value;
            }
        }
        return null;
    }
}
