package io.tesseraql.yaml.app;

import io.tesseraql.core.dialect.DialectSqlResolver;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.files.ConfinedPath;
import io.tesseraql.yaml.model.Binding;
import io.tesseraql.yaml.model.RouteDefinition;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The files a document names by relative path — its 2-way SQL statements, the template a
 * codec reads, the page a response renders — resolved against the declaring directory and
 * fenced by the application home (docs/audit-low-leads.md slice 14).
 *
 * <p>One resolver for every altitude. The linter, the admission gate, the compiler (boot and
 * the hot reload) and {@code tesseraql job run} each resolved a declaration with nothing but
 * {@code directory.resolve(declared).normalize()}, so a reference outside the application
 * home linted clean, passed admission, booted and was served — a workbook's bytes through the
 * Excel codec, a statement through the SQL source — while the pdf codec refused the same
 * layout at request time and the html template was refused at boot alone. The contracts the
 * loader and the codec interface state ("every file read must resolve inside the app home")
 * are true through here.
 *
 * <p>What is fenced is the application home, not the document's directory: {@code ../shared/}
 * inside the home stays legal (the pdf codec's and the page template's rule), and the
 * colocation {@code docs/app-layout.md} shows is the convention, not a bound.
 */
public final class RouteFiles {

    /**
     * TQL-YAML-1075: a declared file reference resolves outside the application home (or is
     * not a path the filesystem can express) — refused at lint, admission, boot, the hot
     * reload and {@code job run} alike, naming the document, the key and the value.
     */
    public static final TqlErrorCode OUTSIDE_APP_HOME = new TqlErrorCode(TqlDomain.YAML, 1075);

    /**
     * TQL-SQL-2103: a binding's 2-way SQL file is not there — the lint's own code, refused at
     * every resolve site the compiler wires a statement from (docs/audit-low-leads.md slice
     * 8): the source reads lazily, so a missing statement used to boot green and answer every
     * request with a raw {@code NoSuchFileException}.
     */
    public static final TqlErrorCode MISSING_SQL_FILE = new TqlErrorCode(TqlDomain.SQL, 2103);

    /**
     * TQL-TPL-2001: a response's page template does not resolve — neither beside the document
     * nor under the application's {@code templates/} — one rule, declared once, for the html,
     * file and text renderers.
     */
    public static final TqlErrorCode UNRESOLVED_TEMPLATE = new TqlErrorCode(TqlDomain.TPL, 2001);

    /** What a reference is read as, which decides its existence rule. */
    public enum Kind {
        /** A 2-way SQL statement: the dialect variant when one is there, else the file. */
        SQL,
        /** The template a codec opens (an export's {@code template:}); judged beside the document. */
        EXPORT_TEMPLATE,
        /** A response's page template: beside the document, else under {@code templates/}. */
        PAGE
    }

    /**
     * One file a document declares.
     *
     * @param key      the dotted key it hangs on ({@code sources.main.file},
     *                 {@code export.template}, {@code response.html.template})
     * @param declared the value as written, relative to the document's directory
     * @param kind     how it is read
     */
    public record Reference(String key, String declared, Kind kind) {
    }

    private RouteFiles() {
    }

    /**
     * Every file {@code definition} names by relative path, in authored order: each source's
     * and step's statement and the enrichments hanging off them, each SQL validation rule, the
     * export's template and follow-up statement, the response's page template. A contract,
     * service, sequence or HTTP arm carries no file and does not appear.
     */
    public static List<Reference> references(RouteDefinition definition) {
        List<Reference> out = new ArrayList<>();
        definition.sources().forEach((name, binding) -> binding(out, "sources." + name, binding));
        definition.steps().forEach((name, binding) -> binding(out, "steps." + name, binding));
        definition.validate().forEach((name, rule) -> {
            if (rule.file() != null && !rule.file().isBlank()) {
                out.add(new Reference("validate." + name + ".file", rule.file(), Kind.SQL));
            }
        });
        if (definition.fileExport() != null) {
            if (definition.fileExport().template() != null
                    && !definition.fileExport().template().isBlank()) {
                out.add(new Reference("export.template", definition.fileExport().template(),
                        Kind.EXPORT_TEMPLATE));
            }
            if (definition.fileExport().after() != null
                    && definition.fileExport().after().sql() != null
                    && definition.fileExport().after().sql().file() != null
                    && !definition.fileExport().after().sql().file().isBlank()) {
                out.add(new Reference("export.after.sql.file",
                        definition.fileExport().after().sql().file(), Kind.SQL));
            }
        }
        if (definition.response() != null) {
            if (definition.response().html() != null
                    && definition.response().html().template() != null
                    && !definition.response().html().template().isBlank()) {
                out.add(new Reference("response.html.template",
                        definition.response().html().template(), Kind.PAGE));
            }
            if (definition.response().file() != null
                    && definition.response().file().template() != null
                    && !definition.response().file().template().isBlank()) {
                out.add(new Reference("response.file.template",
                        definition.response().file().template(), Kind.PAGE));
            }
            if (definition.response().text() != null
                    && definition.response().text().template() != null
                    && !definition.response().text().template().isBlank()) {
                out.add(new Reference("response.text.template",
                        definition.response().text().template(), Kind.PAGE));
            }
        }
        return out;
    }

    private static void binding(List<Reference> out, String slot, Binding binding) {
        if (binding.isSql()) {
            out.add(new Reference(slot + ".file", binding.file(), Kind.SQL));
        }
        if (binding.enrich() == null) {
            return;
        }
        binding.enrich().forEach((name, enrich) -> {
            if (enrich.sql() != null && enrich.sql().file() != null
                    && !enrich.sql().file().isBlank()) {
                out.add(new Reference(slot + ".enrich." + name + ".sql.file",
                        enrich.sql().file(), Kind.SQL));
            }
        });
    }

    /**
     * The words every refusal opens with: the app, the document and the key —
     * {@code app 'shop': route 'orders' sources.main.file: }.
     */
    public static String head(String app, String subject, String key) {
        return "app '" + ExportDeclarations.bounded(app) + "': " + subject + " " + key + ": ";
    }

    /**
     * Resolves {@code declared} against {@code directory} inside {@code appHome}: the file the
     * declaration names, absolutized and folded, or the {@link #OUTSIDE_APP_HOME} refusal when
     * it lands outside the home or cannot be expressed as a path. Existence is the caller's
     * question — {@link #sql} and {@link #page} ask it with their own codes.
     *
     * @param head the sentence's opening, from {@link #head}
     */
    public static Path resolve(Path appHome, Path directory, String declared, String head) {
        ConfinedPath home = ConfinedPath.under(appHome);
        try {
            Path candidate = directory.toAbsolutePath().normalize().resolve(declared);
            return home.confine(candidate).orElseThrow(() -> new TqlException(OUTSIDE_APP_HOME,
                    head + "'" + ExportDeclarations.bounded(declared)
                            + "' resolves outside the application home"));
        } catch (InvalidPathException invalid) {
            throw new TqlException(OUTSIDE_APP_HOME, head + "'"
                    + ExportDeclarations.bounded(declared) + "' is not a file path");
        }
    }

    /**
     * A 2-way SQL statement: {@link #resolve resolved}, then required to be there — the
     * dialect variant ({@code search.mysql.sql} beside {@code search.sql}) counts when
     * {@code dialect} names one, the file as declared otherwise. Returns the file as declared;
     * the reader picks the variant when it opens the statement.
     */
    public static Path sql(Path appHome, Path directory, String declared, String dialect,
            String head) {
        Path file = resolve(appHome, directory, declared, head);
        if (!Files.isRegularFile(DialectSqlResolver.resolve(file, dialect))) {
            throw new TqlException(MISSING_SQL_FILE, head + "referenced SQL file is missing: "
                    + ExportDeclarations.bounded(declared));
        }
        return file;
    }

    /**
     * A response's page template: beside the document first (the colocated yml + sql + html
     * unit), else under the application's shared {@code templates/} for cross-route fragments
     * and layouts; {@link #resolve fenced} either way, and required to be there
     * ({@link #UNRESOLVED_TEMPLATE}).
     */
    public static Path page(Path appHome, Path directory, String declared, String head) {
        Path colocated = resolve(appHome, directory, declared, head);
        Path file = Files.isRegularFile(colocated)
                ? colocated
                : resolve(appHome, appHome.toAbsolutePath().normalize().resolve("templates"),
                        declared, head);
        if (!Files.isRegularFile(file)) {
            throw new TqlException(UNRESOLVED_TEMPLATE, head + "'"
                    + ExportDeclarations.bounded(declared)
                    + "' resolves to no file beside the document or under templates/");
        }
        return file;
    }
}
