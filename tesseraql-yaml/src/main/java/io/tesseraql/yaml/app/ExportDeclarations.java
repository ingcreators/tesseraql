package io.tesseraql.yaml.app;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.manifest.JobFile;
import io.tesseraql.yaml.model.ColumnSpec;
import io.tesseraql.yaml.model.ExportSpec;
import io.tesseraql.yaml.model.ImportSpec;
import io.tesseraql.yaml.model.PipelineStep;
import io.tesseraql.yaml.model.RouteDefinition;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.text.DateFormatSymbols;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IllformedLocaleException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The literal values an {@code export:} / {@code import:} block and the
 * {@code tesseraql.files.*} keys declare, judged once (docs/export-declarations.md): the same
 * predicate the linter reports from and the compiler, the job registration and
 * {@code tesseraql job run} refuse from, fed the one Jackson-bound {@link ExportSpec} /
 * {@link ImportSpec} on every side — the {@link DeclaredRoles} shape, so lint and boot cannot
 * drift.
 *
 * <p>A value is judged as the runtime will read it, and only where the runtime reads it: a
 * zone through {@link ZoneId#of(String)} (region ids are case-sensitive; a short id such as
 * {@code JST} is not a zone the codec accepts), a locale through the strict
 * {@link Locale.Builder} parse plus the JDK's own formatting data, a column pattern through the
 * parser its {@code type:} selects on the formats that run that parser (csv and pdf; a workbook
 * writes the string as the cell's own format), a cell reference through the grammar
 * {@code toWriteSpec} parses on every format and the workbook bound where a workbook reads it. A
 * key the format never reads is said so and served without ({@link Kind#INERT}); a blank value
 * is the platform default and is not judged. A request source expression is judged for its
 * shape and its reach (its value is the request's, refused there); a job has no request, so a
 * source on a step or a poll job is refused outright.
 */
public final class ExportDeclarations {

    /**
     * TQL-YAML-1063: an export or import declaration carries a value the runtime cannot honour
     * where it reads it — a time-zone id the JDK does not know, a language tag it cannot format,
     * a column pattern the parser refuses, an import column type the parser does not know, a
     * cell or column reference that is not one or lies outside a workbook, a mixed-case format
     * name, or a request source that names nothing the surface binds. Reported at lint and
     * refused at boot from the same predicate.
     */
    public static final TqlErrorCode INVALID_VALUE = new TqlErrorCode(TqlDomain.YAML, 1063);

    /**
     * TQL-YAML-1005: a declared key cannot apply where it is declared — the format never reads
     * it, the type is not one the export renders, or no column the declaration reaches is
     * typed. A lint error (a warning when the declaration is honoured less than it reads); at
     * boot the runtime serves without the key, so it says so and continues.
     */
    public static final TqlErrorCode INAPPLICABLE = new TqlErrorCode(TqlDomain.YAML, 1005);

    /** TQL-YAML-1041: a piece the export needs is missing — the block itself, or a follow-up's statement. */
    public static final TqlErrorCode INCOMPLETE = new TqlErrorCode(TqlDomain.YAML, 1041);

    /** TQL-YAML-1006: the export names a template that is not there, or the wrong kind of file for the format. */
    public static final TqlErrorCode UNUSABLE_TEMPLATE = new TqlErrorCode(TqlDomain.YAML, 1006);

    /** The app-wide export defaults; literals, judged like a route's own declaration. */
    public static final List<String> CONFIG_KEYS = List.of("tesseraql.files.locale",
            "tesseraql.files.timezone");

    /**
     * What a route's {@code locale:}/{@code timezone:} may resolve from the request: the same
     * grammar the binder classifies a declaration by, so a value that is not a source is a
     * literal on both sides.
     */
    public static final Pattern SOURCE_EXPRESSION = Pattern
            .compile("(principal|query|body|params|request)\\..+");

    /** The formats the framework ships; a name outside the set belongs to a module codec and is not judged. */
    private static final Set<String> SHIPPED_FORMATS = Set.of("csv", "excel", "pdf");

    private static final Set<String> COLUMN_TYPES = Set.of("date", "datetime", "number");

    /** The workbook bounds (POI's XFD / 1,048,576): a reference past them fails at the first row. */
    private static final int MAX_COLUMN_INDEX = 16_383;

    private static final int MAX_ROW_INDEX = 1_048_575;

    private static final int VALUE_CAP = 40;

    private static final Set<String> JDK_LANGUAGES = Arrays.stream(Locale.getAvailableLocales())
            .map(Locale::getLanguage).filter(language -> !language.isEmpty())
            .collect(Collectors.toUnmodifiableSet());

    private ExportDeclarations() {
    }

    /** What each violation does on each side. */
    public enum Kind {
        /** Refused at lint (error) and at boot (the exception). */
        INVALID,
        /** A lint error; the runtime serves without the key, so boot warns and continues. */
        INERT,
        /** A warning on both sides. */
        ADVISORY
    }

    /** Which reader the block has — what it binds, and what it never reads. */
    public enum Surface {
        /** A {@code query-export} route: a request binder, no {@code after:} hook. */
        QUERY_EXPORT,
        /** A {@code file-export} route: a request binder and the {@code after:} hook. */
        FILE_EXPORT,
        /** A {@code file-import} route: no request binder — only {@code request.locale} and the principal resolve. */
        FILE_IMPORT,
        /** A job's export step or a poll job's import block: no request at all. */
        JOB,
        /** An app-wide configuration key: a literal. */
        CONFIG
    }

    /** One finding; {@code key} is the declaration's path ({@code export.timezone}, {@code tesseraql.files.locale}). */
    public record Violation(TqlErrorCode code, Kind kind, String key, String message) {
    }

    /**
     * Where the declaration rides — the words every message opens with, and the facts the
     * source-expression arms need — computed ONCE for a bound route, so the linter and the
     * compiler cannot classify the same route differently.
     *
     * @param app          the application's name
     * @param subject      {@code route 'items.dump'}, {@code job 'daily' step 'report'} or {@code config}
     * @param surface      which reader the block has
     * @param inputs       the route's declared input names
     * @param principal    whether a principal can be present (an authenticated route)
     * @param declaredBody whether the request body is limited to declared inputs
     *                     ({@code inputPolicy.unknownFields: reject}, the default)
     */
    public record Site(String app, String subject, Surface surface, Set<String> inputs,
            boolean principal, boolean declaredBody) {

        /**
         * The site of a bound route: its recipe's surface, its declared inputs, whether a
         * principal can be bound (a route whose merged {@code security.auth} is present and not
         * {@code public} — the defaults are merged before either side judges), and whether the
         * body is limited to declared fields.
         */
        public static Site route(String app, RouteDefinition route) {
            boolean authenticated = route.security() != null
                    && route.security().auth() != null
                    && !"public".equals(route.security().auth());
            Surface surface = "file-export".equals(route.recipe())
                    ? Surface.FILE_EXPORT
                    : "file-import".equals(route.recipe())
                            ? Surface.FILE_IMPORT
                            : Surface.QUERY_EXPORT;
            return new Site(app, "route '" + bounded(route.id()) + "'", surface,
                    route.input().keySet(), authenticated,
                    route.effectiveInputPolicy().rejectsUnknownFields());
        }

        public static Site step(String app, String jobId, String stepId) {
            return new Site(app, "job '" + bounded(jobId) + "' step '" + bounded(stepId) + "'",
                    Surface.JOB, Set.of(), false, true);
        }

        public static Site job(String app, String jobId) {
            return new Site(app, "job '" + bounded(jobId) + "'", Surface.JOB, Set.of(), false,
                    true);
        }

        static Site config(String app) {
            return new Site(app, "config", Surface.CONFIG, Set.of(), false, true);
        }

        boolean job() {
            return surface == Surface.JOB;
        }

        String prefix(String key) {
            return "app '" + bounded(app) + "': " + subject + " " + key + ": ";
        }
    }

    /** Whether the declaration is a request source expression rather than a literal. */
    public static boolean isSourceExpression(String declaration) {
        return declaration != null && SOURCE_EXPRESSION.matcher(declaration).matches();
    }

    /**
     * Every violation of a route's or step's {@code export:} block ({@code null} being "no
     * block"); {@code directory} is where {@code template:} resolves, the route's or the job
     * file's.
     */
    public static List<Violation> violations(Site site, ExportSpec spec, Path directory) {
        List<Violation> out = new ArrayList<>();
        if (spec == null) {
            return out;
        }
        formatName(site, spec.format(), out);
        boolean declaredFormat = spec.format() != null && !spec.format().isBlank();
        if (!declaredFormat && site.job()) {
            // A step's missing format: is the linter's own TQL-YAML-1041 and the step never
            // runs — the format-dependent arms have no format to judge against.
            zone(site, "export.timezone", spec.timezone(), false, out);
            locale(site, "export.locale", spec.locale(), false, out);
            return out;
        }
        String format = declaredFormat ? spec.format().toLowerCase(Locale.ROOT) : "csv";
        boolean shipped = SHIPPED_FORMATS.contains(format);
        boolean excel = "excel".equals(format);
        boolean pdf = "pdf".equals(format);
        boolean csv = "csv".equals(format);
        boolean template = spec.template() != null && !spec.template().isBlank();
        boolean report = excel && template && (spec.startCell() == null
                || spec.startCell().isBlank());

        // The zone reaches every temporal cell of a grid or placement workbook and the typed
        // columns of csv/pdf; a jxls report hands the template raw values (the advisory below).
        zone(site, "export.timezone", spec.timezone(), report, out);
        // A workbook never reads the locale (the inert arm below says so); csv/pdf do.
        locale(site, "export.locale", spec.locale(), excel, out);
        columns(site, "export.columns", spec.columns(), format, false, out);
        cellReference(site, "export.startCell", spec.startCell(), excel, out);
        if (site.surface() == Surface.FILE_EXPORT && spec.after() != null
                && (spec.after().sql() == null || spec.after().sql().file() == null
                        || spec.after().sql().file().isBlank())) {
            // Used to escape buildFileExport as a NullPointerException; a query-export's
            // after: stays the compiler's own refusal (TQL-ROUTE-3101).
            out.add(new Violation(INCOMPLETE, Kind.INVALID, "export.after",
                    site.prefix("export.after") + "a follow-up needs its statement - declare"
                            + " after.sql: { file: ... }"));
        }
        if (template && !csv) {
            templateFile(site, spec.template(), pdf, directory, out);
        }
        if (!shipped) {
            return out;
        }
        // Keys the format never reads: a lint error, a boot warning (the runtime serves
        // without them — docs/export-declarations.md decision 2).
        if (spec.bom() != null && !csv) {
            out.add(new Violation(INAPPLICABLE, Kind.INERT, "export.bom", site.prefix("export.bom")
                    + "bom: is a csv option - " + format + " output has no text stream to mark"));
        }
        if (!excel && (spec.sheet() != null || spec.startCell() != null)) {
            out.add(new Violation(INAPPLICABLE, Kind.INERT, "export.sheet", site.prefix(
                    "export.sheet/startCell") + "sheet:/startCell: are workbook options - a "
                    + format + (pdf
                            ? " lays out through its template, not cell placement"
                            : " export writes rows and nothing else")));
        }
        if (csv && template) {
            out.add(new Violation(INAPPLICABLE, Kind.INERT, "export.template",
                    site.prefix("export.template") + "'" + bounded(spec.template())
                            + "' is a workbook or print option - csv output writes rows and"
                            + " reads no template"));
        }
        if (excel && spec.locale() != null && !spec.locale().isBlank()) {
            out.add(new Violation(INAPPLICABLE, Kind.INERT, "export.locale",
                    site.prefix("export.locale") + "'" + bounded(spec.locale())
                            + "' drives nothing in a workbook - cells carry values and a cell"
                            + " format, and the reader's own locale renders them"));
        }
        if (report) {
            List<String> unread = new ArrayList<>();
            if (spec.timezone() != null && !spec.timezone().isBlank()) {
                unread.add("timezone:");
            }
            if (spec.columns().stream().anyMatch(column -> column.type() != null
                    || column.format() != null)) {
                unread.add("columns[].type:/format:");
            }
            if (!unread.isEmpty()) {
                out.add(new Violation(INAPPLICABLE, Kind.ADVISORY, "export.template",
                        site.prefix("export.template") + "a jxls report (template: without"
                                + " startCell:) hands the template raw values, so "
                                + String.join(", ", unread) + " reach no cell today"));
            }
        }
        if ((csv || pdf) && !spec.columns().isEmpty()
                && ((spec.timezone() != null && !spec.timezone().isBlank())
                        || (spec.locale() != null && !spec.locale().isBlank()))
                && spec.columns().stream().noneMatch(column -> column.type() != null
                        || column.format() != null)) {
            out.add(new Violation(INAPPLICABLE, Kind.ADVISORY, "export.columns",
                    site.prefix("export.columns") + "locale:/timezone: reach only a typed or"
                            + " formatted column on " + format + ", and none of the declared"
                            + " columns is - untyped cells are the driver's text (this check"
                            + " cannot see derived columns)"));
        }
        return out;
    }

    /** The refusal a file-export route with no {@code export:} block draws, on both sides. */
    public static String missingBlock(Site site) {
        return site.prefix("export") + "a file-export route needs an export: block saying"
                + " how the rows are written (format:, filename:, columns:)";
    }

    /**
     * Every violation of a route's or poll job's {@code import:} block. An import parses every
     * typed column through the declared pattern whatever the file format, so the pattern and
     * type arms are refusals here, not warnings.
     */
    public static List<Violation> violations(Site site, ImportSpec spec) {
        List<Violation> out = new ArrayList<>();
        if (spec == null) {
            return out;
        }
        formatName(site, spec.format(), out);
        locale(site, "import.locale", spec.locale(), false, out);
        String format = spec.format() == null || spec.format().isBlank()
                ? "csv"
                : spec.format().toLowerCase(Locale.ROOT);
        columns(site, "import.columns", spec.columns(), format, true, out);
        return out;
    }

    /**
     * The app-wide {@code tesseraql.files.locale} / {@code tesseraql.files.timezone} values, read
     * from the configuration the way every reader of the keys does: a literal, judged like a
     * route's; a source expression has no request to resolve it from and is refused (decision
     * 5). A placeholder this environment cannot resolve is the deployment's to supply and is
     * skipped on both sides — the key's own reader still refuses it where the key is read.
     */
    public static List<Violation> configViolations(String app, AppConfig config) {
        List<Violation> out = new ArrayList<>();
        for (String key : CONFIG_KEYS) {
            String value;
            try {
                value = config.getString(key).orElse(null);
            } catch (TqlException unresolved) {
                continue;
            }
            out.addAll(configViolations(app, key, value));
        }
        return out;
    }

    /** One app-wide key's violations; {@code value} is the resolved literal. */
    public static List<Violation> configViolations(String app, String key, String value) {
        List<Violation> out = new ArrayList<>();
        if (value == null || value.isBlank()) {
            return out;
        }
        Site site = Site.config(app);
        if (isSourceExpression(value)) {
            out.add(new Violation(INVALID_VALUE, Kind.INVALID, key, site.prefix(key) + "'"
                    + bounded(value) + "' is a request source expression, and an app-wide key"
                    + " has no request to resolve it from - declare the source on the route"
                    + " whose request carries it, and keep the key a literal"));
            return out;
        }
        if (key.endsWith("timezone")) {
            zoneLiteral(site, key, value, out);
        } else {
            localeLiteral(site, key, value, out);
        }
        return out;
    }

    /**
     * The job arm (decision 1), for every place a job map is filled — the runtime's two fills
     * and {@code tesseraql job run}: each export step's block (its template resolving beside
     * the job file) and a poll job's import block, refused or warned about like a route's.
     */
    public static void requireJob(String app, JobFile job, Consumer<String> warn) {
        String jobId = job.definition().id();
        Path directory = job.source() == null ? null : job.source().getParent();
        for (PipelineStep step : job.definition().pipeline()) {
            if (step.export() != null) {
                require(violations(Site.step(app, jobId, step.id()), step.export(), directory),
                        warn);
            }
        }
        if (job.definition().fileImport() != null) {
            require(violations(Site.job(app, jobId), job.definition().fileImport()), warn);
        }
    }

    /**
     * The boot backstop: hands every warning (an inert or advisory key) to {@code warn} first,
     * so the log holds the whole block's verdict, then throws the first refusal as a
     * {@link TqlException} carrying its own code.
     */
    public static void require(List<Violation> violations, Consumer<String> warn) {
        for (Violation violation : violations) {
            if (violation.kind() != Kind.INVALID) {
                warn.accept(violation.message());
            }
        }
        for (Violation violation : violations) {
            if (violation.kind() == Kind.INVALID) {
                throw new TqlException(violation.code(), violation.message());
            }
        }
    }

    /**
     * The value as a message may carry it: control, format and line-separator characters
     * replaced, the length capped on a code-point boundary — so an author-controlled string
     * cannot forge a log line, split one, or leave a lone surrogate in a JSON rendering.
     */
    public static String bounded(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder clean = new StringBuilder();
        int points = 0;
        for (int i = 0; i < value.length() && points < VALUE_CAP;) {
            int point = value.codePointAt(i);
            int type = Character.getType(point);
            boolean unsafe = type == Character.CONTROL || type == Character.FORMAT
                    || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR
                    || type == Character.SURROGATE || type == Character.UNASSIGNED;
            clean.appendCodePoint(unsafe ? '?' : point);
            i += Character.charCount(point);
            points++;
        }
        return value.codePointCount(0, value.length()) <= VALUE_CAP
                ? clean.toString()
                : clean + "...";
    }

    private static void formatName(Site site, String format, List<Violation> out) {
        if (format == null || format.isBlank()) {
            return;
        }
        String lower = format.toLowerCase(Locale.ROOT);
        if (SHIPPED_FORMATS.contains(lower) && !lower.equals(format)) {
            out.add(new Violation(INVALID_VALUE, Kind.INVALID, "format", site.prefix("format")
                    + "'" + bounded(format) + "' - format names are lower-case (" + lower + ")"));
        }
    }

    /**
     * The zone arm: a source is judged for its shape on every mode (the binder resolves it
     * per request whatever the codec reads); a literal is judged only where the codec reads
     * it, which a jxls report never does.
     */
    private static void zone(Site site, String key, String value, boolean unread,
            List<Violation> out) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (isSourceExpression(value)) {
            source(site, key, value, false, out);
            return;
        }
        if (!unread) {
            zoneLiteral(site, key, value, out);
        }
    }

    private static void zoneLiteral(Site site, String key, String value, List<Violation> out) {
        zoneProblem(value).ifPresent(problem -> out.add(new Violation(INVALID_VALUE,
                Kind.INVALID, key, site.prefix(key) + problem + sourceHint(site, value))));
    }

    /**
     * Why {@code value} is not a zone the codec can render in, or empty when it is: the one
     * sentence the literal arms and the request-time judge share (docs/export-declarations.md
     * decision 2), so a bad value is described the same way wherever it was declared. The
     * grammar is {@link ZoneId#of}'s, as the codec reads it: region ids are case-sensitive, an
     * offset is a zone, and the short ids ({@code JST}) are not.
     */
    public static Optional<String> zoneProblem(String value) {
        try {
            ZoneId.of(value);
            return Optional.empty();
        } catch (RuntimeException ex) {
            return Optional.of("'" + bounded(value) + "' is not a time-zone id the JDK knows"
                    + " (expected a region id such as Asia/Tokyo - case-sensitive - or an"
                    + " offset such as +09:00)");
        }
    }

    private static void locale(Site site, String key, String value, boolean unread,
            List<Violation> out) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (isSourceExpression(value)) {
            source(site, key, value, true, out);
            return;
        }
        if (!unread) {
            localeLiteral(site, key, value, out);
        }
    }

    private static void localeLiteral(Site site, String key, String value, List<Violation> out) {
        localeProblem(value).ifPresent(problem -> out.add(new Violation(INVALID_VALUE,
                Kind.INVALID, key, site.prefix(key) + problem + sourceHint(site, value))));
    }

    /**
     * Why {@code value} is not a locale the codec can format in, or empty when it is - the
     * zone's twin ({@link #zoneProblem}), over {@link #isFormattableLocale}.
     */
    public static Optional<String> localeProblem(String value) {
        return isFormattableLocale(value)
                ? Optional.empty()
                : Optional.of("'" + bounded(value) + "' is not a language tag the JDK can"
                        + " format (expected e.g. en, ja-JP)");
    }

    /**
     * The one locale rule (decision 7): the strict BCP-47 parse succeeds and the JDK has
     * formatting data for the language — it is one the JDK lists a locale for, or (a CLDR
     * alias such as {@code tl} for Filipino) its date and number symbols differ from the root
     * locale's, which is what the runtime's own {@code Locale.forLanguageTag} renders through.
     * {@code ja_JP} fails the parse; {@code japanese}, {@code xx-YY} and {@code und} parse and
     * render as the root locale. Extensions pass.
     */
    public static boolean isFormattableLocale(String tag) {
        Locale locale;
        try {
            locale = new Locale.Builder().setLanguageTag(tag).build();
        } catch (IllformedLocaleException ex) {
            return false;
        }
        if (locale.getLanguage().isEmpty()) {
            return false;
        }
        if (JDK_LANGUAGES.contains(locale.getLanguage())) {
            return true;
        }
        DateFormatSymbols dates = DateFormatSymbols.getInstance(locale);
        DateFormatSymbols rootDates = DateFormatSymbols.getInstance(Locale.ROOT);
        DecimalFormatSymbols numbers = DecimalFormatSymbols.getInstance(locale);
        DecimalFormatSymbols rootNumbers = DecimalFormatSymbols.getInstance(Locale.ROOT);
        return !Arrays.equals(dates.getMonths(), rootDates.getMonths())
                || !Arrays.equals(dates.getShortWeekdays(), rootDates.getShortWeekdays())
                || numbers.getDecimalSeparator() != rootNumbers.getDecimalSeparator()
                || numbers.getGroupingSeparator() != rootNumbers.getGroupingSeparator();
    }

    /** A dotted lower-case literal that failed: the author probably meant a request source. */
    private static String sourceHint(Site site, String value) {
        if (site.surface() == Surface.JOB || site.surface() == Surface.CONFIG
                || !value.matches("[a-z]+\\..*")) {
            return "";
        }
        return "; a request source starts with query., params., body., principal. or"
                + " request.locale";
    }

    private static void source(Site site, String key, String value, boolean locale,
            List<Violation> out) {
        String shown = "'" + bounded(value) + "'";
        if (site.surface() == Surface.JOB) {
            out.add(new Violation(INVALID_VALUE, Kind.INVALID, key, site.prefix(key) + shown
                    + " is a request source expression, and a job has no request - a job's"
                    + " locale:/timezone: are literals"));
            return;
        }
        int dot = value.indexOf('.');
        String root = value.substring(0, dot);
        String rest = value.substring(dot + 1);
        switch (root) {
            case "request" -> {
                if (!locale || !"locale".equals(rest)) {
                    out.add(new Violation(INVALID_VALUE, Kind.INVALID, key, site.prefix(key)
                            + shown + " names nothing a request carries - request.locale (on"
                            + " locale:) is the negotiated request locale"));
                }
            }
            case "principal" -> {
                if (!site.principal()) {
                    out.add(new Violation(INVALID_VALUE, Kind.INVALID, key, site.prefix(key)
                            + shown + " reads the principal, but the route is public - no"
                            + " principal is ever bound, so the value would silently be the"
                            + " platform default"));
                }
            }
            default -> {
                if (site.surface() == Surface.FILE_IMPORT) {
                    // buildFileImport mounts no request binder: input: is the row contract,
                    // and query./params./body. never resolve on this surface.
                    out.add(new Violation(INVALID_VALUE, Kind.INVALID, key, site.prefix(key)
                            + shown + " names a request input, and a file-import binds none -"
                            + " an import's locale: is a literal, request.locale, or"
                            + " principal.<claim>"));
                    return;
                }
                int next = rest.indexOf('.');
                String input = next < 0 ? rest : rest.substring(0, next);
                if (input.isEmpty()) {
                    out.add(new Violation(INVALID_VALUE, Kind.INVALID, key, site.prefix(key)
                            + shown + " names no field - the form is " + root + ".<name>"));
                } else if (!"body".equals(root) || site.declaredBody()) {
                    // query./params. carry the declared inputs only; body. carries the raw
                    // body, which keeps an undeclared field under unknownFields: ignore.
                    if (!site.inputs().contains(input)) {
                        out.add(new Violation(INVALID_VALUE, Kind.INVALID, key, site.prefix(key)
                                + shown + " names no declared input - declare input: "
                                + bounded(input) + ", or the value would silently be the"
                                + " platform default"));
                    }
                }
            }
        }
    }

    /**
     * The column list: a typed pattern is judged by the parser its type selects where that
     * parser runs — an import on every file format, an export on csv and pdf (a workbook
     * writes the string as the cell's own format); a type the export does not render is inert,
     * a type the import cannot parse is refused; a reference is judged by the grammar
     * {@code toWriteSpec} parses on every format, and by the workbook bound where a workbook
     * reads it.
     */
    private static void columns(Site site, String key, List<ColumnSpec> columns, String format,
            boolean importing, List<Violation> out) {
        boolean excel = "excel".equals(format);
        boolean parses = importing || "csv".equals(format) || "pdf".equals(format);
        for (ColumnSpec column : columns) {
            String at = key + "[" + bounded(column.name()) + "]";
            String type = column.type();
            if (type != null && !COLUMN_TYPES.contains(type)) {
                if (importing) {
                    out.add(new Violation(INVALID_VALUE, Kind.INVALID, at + ".type",
                            site.prefix(at + ".type") + "'" + bounded(type)
                                    + "' is not a column type the import can parse (date,"
                                    + " datetime, number)"));
                } else {
                    out.add(new Violation(INAPPLICABLE, Kind.INERT, at + ".type",
                            site.prefix(at + ".type") + "'" + bounded(type)
                                    + "' is not a column type the export renders (date,"
                                    + " datetime, number) - the column is written as the"
                                    + " driver's text"));
                }
            }
            String pattern = column.format();
            if (pattern != null && parses) {
                String problem = patternProblem(type, pattern);
                if (problem != null) {
                    out.add(new Violation(INVALID_VALUE, Kind.INVALID, at + ".format",
                            site.prefix(at + ".format") + "'" + bounded(pattern) + "' "
                                    + problem));
                }
            }
            if (column.column() != null && !column.column().isBlank()) {
                columnReference(site, at + ".column", column.column(), excel, out);
            }
        }
    }

    /**
     * By {@code type:} when present — number through {@code DecimalFormat}, date/datetime
     * through {@code DateTimeFormatter.ofPattern} — and, without one, refused only when neither
     * parser accepts it (the runtime picks the parser by the value's class, so this arm is
     * weaker: it catches a doubled separator or an unterminated quote, not a letter one parser
     * happens to tolerate). The parsers' own messages carry the whole pattern, so they are not
     * echoed.
     */
    static String patternProblem(String type, String format) {
        boolean decimal = acceptsDecimal(format);
        boolean temporal = acceptsTemporal(format);
        if ("number".equals(type)) {
            return decimal ? null : "is not a DecimalFormat pattern (expected e.g. #,##0.00)";
        }
        if ("date".equals(type) || "datetime".equals(type)) {
            return temporal
                    ? null
                    : "is not a DateTimeFormatter pattern (expected e.g. yyyy-MM-dd HH:mm)";
        }
        if (type == null && !decimal && !temporal) {
            return "is neither a DecimalFormat nor a DateTimeFormatter pattern - declare"
                    + " type: to say which";
        }
        return null;
    }

    private static boolean acceptsDecimal(String format) {
        try {
            new DecimalFormat(format);
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static boolean acceptsTemporal(String format) {
        try {
            DateTimeFormatter.ofPattern(format);
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    /** {@code startCell:}: the grammar on every format (today's raw throw), the bound on a workbook. */
    private static void cellReference(Site site, String key, String value, boolean workbook,
            List<Violation> out) {
        if (value == null || value.isBlank()) {
            return;
        }
        String ref = value.trim().toUpperCase(Locale.ROOT);
        io.tesseraql.core.files.CellRef cell;
        try {
            cell = io.tesseraql.core.files.CellRef.parse(value);
        } catch (IllegalArgumentException ex) {
            out.add(new Violation(INVALID_VALUE, Kind.INVALID, key, site.prefix(key) + "'"
                    + bounded(value) + "' is not a cell reference (expected e.g. B5)"));
            return;
        }
        if (workbook && (lettersPastBound(ref.replaceAll("[0-9]+$", ""))
                || cell.col() > MAX_COLUMN_INDEX || cell.row() > MAX_ROW_INDEX)) {
            out.add(new Violation(INVALID_VALUE, Kind.INVALID, key, site.prefix(key) + "'"
                    + bounded(value) + "' is outside a workbook (the last cell is XFD1048576)"));
        }
    }

    /** {@code columns[].column}: the grammar on every format, the bound on a workbook. */
    private static void columnReference(Site site, String key, String value, boolean workbook,
            List<Violation> out) {
        String ref = value.trim().toUpperCase(Locale.ROOT);
        int index;
        try {
            index = io.tesseraql.core.files.ColumnMapping.parseColumn(value);
        } catch (IllegalArgumentException ex) {
            out.add(new Violation(INVALID_VALUE, Kind.INVALID, key, site.prefix(key) + "'"
                    + bounded(value) + "' is not a column reference (expected a letter such as"
                    + " D, or a 1-based number)"));
            return;
        }
        if (workbook && (lettersPastBound(ref) || index > MAX_COLUMN_INDEX
                || index < 0)) {
            out.add(new Violation(INVALID_VALUE, Kind.INVALID, key, site.prefix(key) + "'"
                    + bounded(value) + "' is past the last workbook column (XFD)"));
        }
    }

    /** Four letters or more is past XFD whatever the arithmetic says (the parse overflows int). */
    private static boolean lettersPastBound(String letters) {
        return letters.matches("[A-Z]{4,}") || letters.matches("[0-9]{6,}");
    }

    /**
     * The template a workbook, a print or a module format reads (decision 10, the boot twin of
     * the linter's own code): a pdf template renders through the template engine and must be
     * {@code .html}; any template must be there beside the document.
     */
    private static void templateFile(Site site, String template, boolean pdf, Path directory,
            List<Violation> out) {
        if (pdf && !template.endsWith(".html")) {
            out.add(new Violation(UNUSABLE_TEMPLATE, Kind.INVALID, "export.template",
                    site.prefix("export.template") + "'" + bounded(template) + "' must be an"
                            + " .html file (a pdf renders through the template engine before"
                            + " conversion)"));
            return;
        }
        if (directory == null) {
            return;
        }
        try {
            if (!Files.isRegularFile(directory.resolve(template))) {
                out.add(new Violation(UNUSABLE_TEMPLATE, Kind.INVALID, "export.template",
                        site.prefix("export.template") + "references a missing template: "
                                + bounded(template)));
            }
        } catch (InvalidPathException ex) {
            out.add(new Violation(UNUSABLE_TEMPLATE, Kind.INVALID, "export.template",
                    site.prefix("export.template") + "'" + bounded(template)
                            + "' is not a file path"));
        }
    }
}
