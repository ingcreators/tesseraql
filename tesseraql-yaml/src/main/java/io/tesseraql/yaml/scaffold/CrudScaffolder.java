package io.tesseraql.yaml.scaffold;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.config.ResponseHeaderDefaults;
import io.tesseraql.yaml.config.SecurityDefaults;
import io.tesseraql.yaml.model.SecuritySpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Generates a table's CRUD slice from its introspected schema (roadmap Phase 23): list, detail,
 * and edit pages in Hypermedia Components markup, 2-way SQL following the Phase 18 audit and
 * optimistic-locking conventions, unique-index constraint mappings, and a declarative test suite
 * over the generated queries. Generation is a pure function of the {@link TableSchema}, so the
 * same schema always yields byte-identical artifacts (design ch. 48 reproducibility).
 *
 * <p>Conventions applied when the table opts in:
 * <ul>
 * <li>an auto-generated single primary key is captured via {@code keys:} and drives the
 * post/redirect/get flow; a non-generated key becomes a required form field,</li>
 * <li>a numeric {@code version} column pairs an optimistic-locking predicate with
 * {@code expect: rows: 1} so a stale edit answers {@code 409 Conflict},</li>
 * <li>{@code created_by/created_at/updated_by/updated_at} columns are stamped from the canonical
 * {@code audit.*} binds,</li>
 * <li>single-column unique indexes map to field-level constraint errors.</li>
 * </ul>
 *
 * <p>The generated security blocks reference the {@code app.read} / {@code app.write} policies
 * the {@code tesseraql new} skeleton defines; apps with their own policy names edit the generated
 * blocks (edit detection then leaves those files alone). When the target app's path-matched
 * security defaults (docs/route-defaults.md) already give these pages {@code auth: browser} and
 * CSRF on writes, the generated routes state only their {@code policy:} — the defaults carry the
 * rest.
 */
public final class CrudScaffolder {

    private final SecurityDefaults securityDefaults;
    private final ResponseHeaderDefaults responseHeaderDefaults;

    /** A scaffolder that spells out every security key (no app config to defer to). */
    public CrudScaffolder() {
        this(null, null);
    }

    /**
     * A scaffolder deferring to the target app's declared security and response-header defaults
     * where they cover the generated routes; {@code null} means none.
     */
    public CrudScaffolder(SecurityDefaults securityDefaults,
            ResponseHeaderDefaults responseHeaderDefaults) {
        this.securityDefaults = securityDefaults;
        this.responseHeaderDefaults = responseHeaderDefaults;
    }

    private static final TqlErrorCode UNSUPPORTED_TABLE = new TqlErrorCode(TqlDomain.APP, 5203);
    private static final String CSP_HEADERS = """
                headers:
                  Content-Security-Policy: "default-src 'self'; style-src 'self' 'unsafe-inline'; frame-ancestors 'none'"
                  X-Content-Type-Options: nosniff
                  X-Frame-Options: DENY
                  Referrer-Policy: no-referrer
            """;

    /**
     * The per-route security header block, or nothing when the app's declared
     * {@code security.responseHeaders} already sends one app-wide.
     */
    private String cspHeaders() {
        return responseHeaderDefaults != null && !responseHeaderDefaults.isEmpty()
                ? ""
                : CSP_HEADERS;
    }

    /** Generates the CRUD file set for the table (paths relative to the app home). */
    public List<ScaffoldedFile> scaffold(TableSchema table) {
        TableSchema.Column pk = table.primaryKeyColumn().orElseThrow(() -> new TqlException(
                UNSUPPORTED_TABLE, "Table '" + table.name() + "' needs a single-column primary"
                        + " key to scaffold (composite or missing keys are not supported)"));
        Names names = new Names(table, pk);
        List<ScaffoldedFile> files = new ArrayList<>();
        files.add(new ScaffoldedFile("domains/" + names.table() + ".yml",
                domainsFile(table, names)));
        files.add(new ScaffoldedFile(names.dir() + "/get.yml", listRoute(table, names)));
        files.add(new ScaffoldedFile(names.dir() + "/list.view.yml", listView(table, names)));
        files.add(new ScaffoldedFile(names.dir() + "/search.sql", searchSql(table, names)));
        files.add(new ScaffoldedFile(names.dir() + "/frags.html", fragsFile(table, names)));
        files.add(new ScaffoldedFile(names.dir() + "/new/get.yml", newRoute(names)));
        files.add(new ScaffoldedFile(names.dir() + "/new/new.view.yml", newView(names)));
        files.add(new ScaffoldedFile(names.dir() + "/create/post.yml",
                createRoute(table, names)));
        files.add(new ScaffoldedFile(names.dir() + "/create/insert.sql",
                insertSql(table, names)));
        files.add(new ScaffoldedFile(names.detailDir() + "/get.yml", detailRoute(table, names)));
        files.add(new ScaffoldedFile(names.detailDir() + "/select.sql", selectSql(table, names)));
        files.add(new ScaffoldedFile(names.detailDir() + "/edit.view.yml",
                editView(table, names)));
        files.add(new ScaffoldedFile(names.detailDir() + "/update/post.yml",
                updateRoute(table, names)));
        files.add(new ScaffoldedFile(names.detailDir() + "/update/update.sql",
                updateSql(table, names)));
        files.add(new ScaffoldedFile(names.detailDir() + "/delete/post.yml",
                deleteRoute(table, names)));
        files.add(new ScaffoldedFile(names.detailDir() + "/delete/delete.sql",
                deleteSql(table, names)));
        files.add(new ScaffoldedFile("tests/" + names.table() + "-crud-test.yml",
                testSuite(table, names)));
        if (defaultsCoverBrowserPages(names)) {
            files.replaceAll(CrudScaffolder::slimSecurity);
        }
        return List.copyOf(files);
    }

    /**
     * Whether the app's security defaults give the generated pages {@code auth: browser} and
     * CSRF on their writes — checked against the actual URLs this table scaffolds, so a
     * bearer-only or partially-matching rule set keeps the explicit blocks.
     */
    private boolean defaultsCoverBrowserPages(Names names) {
        if (securityDefaults == null || securityDefaults.isEmpty()) {
            return false;
        }
        String base = "/" + names.table();
        SecuritySpec read = securityDefaults.resolve("GET", base, null);
        SecuritySpec write = securityDefaults.resolve("POST", base + "/create", null);
        return read != null && "browser".equals(read.auth())
                && write != null && "browser".equals(write.auth())
                && Boolean.TRUE.equals(write.csrf());
    }

    /**
     * Drops the security keys the app defaults reproduce — exactly {@code auth: browser} and
     * {@code csrf: true} — from a generated route document; {@code policy:} stays route-local.
     */
    private static ScaffoldedFile slimSecurity(ScaffoldedFile file) {
        if (!file.path().endsWith(".yml")) {
            return file;
        }
        return new ScaffoldedFile(file.path(), file.content()
                .replace("security:\n  auth: browser\n", "security:\n")
                .replace("  csrf: true\n", ""));
    }

    /** Derived, deterministic naming for one table. */
    private record Names(TableSchema schema, TableSchema.Column pk) {

        /** The lowercased table name: the URL segment and the test-suite file stem. */
        String table() {
            return schema.name().toLowerCase(Locale.ROOT);
        }

        /** The camelCase route-id prefix, e.g. {@code order_lines} to {@code orderLines}. */
        String entity() {
            return camel(table());
        }

        /** The page title, e.g. {@code order_lines} to {@code Order lines}. */
        String title() {
            return label(table());
        }

        String url() {
            return "/" + table();
        }

        String dir() {
            return "web/" + table();
        }

        String detailDir() {
            return dir() + "/{" + pkField() + "}";
        }

        String pkColumn() {
            return pk.name().toLowerCase(Locale.ROOT);
        }

        String pkField() {
            return camel(pkColumn());
        }

        /** The first character-type data column, driving the list page's live search. */
        Optional<TableSchema.Column> searchColumn() {
            return schema.dataColumns().stream()
                    .filter(TableSchema.Column::isCharacter)
                    .findFirst();
        }

        static String camel(String snake) {
            StringBuilder out = new StringBuilder();
            for (String word : snake.toLowerCase(Locale.ROOT).split("_")) {
                if (word.isEmpty()) {
                    continue;
                }
                out.append(out.isEmpty()
                        ? word
                        : Character.toUpperCase(word.charAt(0)) + word.substring(1));
            }
            return out.toString();
        }

        static String label(String snake) {
            String words = snake.toLowerCase(Locale.ROOT).replace('_', ' ').trim();
            return words.isEmpty()
                    ? words
                    : Character.toUpperCase(words.charAt(0)) + words.substring(1);
        }

        static String field(TableSchema.Column column) {
            return camel(column.name().toLowerCase(Locale.ROOT));
        }

        static String columnName(TableSchema.Column column) {
            return column.name().toLowerCase(Locale.ROOT);
        }

        static String htmlId(TableSchema.Column column) {
            return "field-" + columnName(column).replace('_', '-');
        }
    }

    // ---------------------------------------------------------------- list page

    private String listRoute(TableSchema table, Names names) {
        // One route serves the whole list: the search/sort inputs feed the SQL, and the
        // tql/view/list pattern re-renders its own table region over htmx (no fragment route).
        StringBuilder yml = new StringBuilder();
        yml.append("""
                # Scaffolded list page for the %s table (tesseraql scaffold crud --table %s):
                # renders through the tql/view/list pattern (docs/declarative-views.md).
                version: tesseraql/v1
                id: %s.page
                kind: route
                recipe: query-html

                input:
                %s  sort:
                    type: string
                    enum: [%s]
                    default: %s
                  dir:
                    type: string
                    enum: [asc, desc]
                    default: asc

                security:
                  auth: browser
                  policy: app.read

                sql:
                  file: search.sql
                  mode: query
                  params:
                %s    sort: query.sort
                    dir: query.dir

                page:
                  size: 50
                  maxSize: 200
                  count: true

                response:
                  html:
                    view: list.view.yml
                %s""".formatted(names.table(), names.table(), names.entity(),
                names.searchColumn().isPresent()
                        ? "  q:\n    type: string\n    required: false\n    maxLength: 200\n"
                        : "",
                sortEnum(table, names), names.pkColumn(),
                names.searchColumn().isPresent() ? "    q: query.q\n" : "", cspHeaders()));
        return yml.toString();
    }

    /** The sortable-column allowlist: the primary key plus every data column. */
    private static String sortEnum(TableSchema table, Names names) {
        StringBuilder values = new StringBuilder(names.pkColumn());
        for (TableSchema.Column column : table.dataColumns()) {
            values.append(", ").append(Names.columnName(column));
        }
        return values.toString();
    }

    /** The list view: sortable columns, the live search box, and the per-row Open action. */
    private static String listView(TableSchema table, Names names) {
        StringBuilder yml = new StringBuilder();
        yml.append("""
                # Scaffolded list view for the %s table: renders through the tql/view/list
                # pattern (docs/declarative-views.md) — sortable headers, a live search box,
                # and the header slot's New button.
                version: tesseraql/v1
                id: %s
                kind: view
                view: list
                title: %s
                %scolumns:
                  - name: %s
                    sortable: true
                """.formatted(names.table(), names.entity(), names.title(),
                names.searchColumn().isPresent() ? "search: q\n" : "", names.pkColumn()));
        for (TableSchema.Column column : table.dataColumns()) {
            yml.append("  - name: ").append(Names.columnName(column))
                    .append("\n    sortable: true\n");
        }
        yml.append("""
                  - name: %s
                    label: ""
                    text: Open
                    link: %s/{%s}
                slots:
                  header: frags.html::new-link
                """.formatted(names.pkColumn(), names.url(), names.pkColumn()));
        return yml.toString();
    }

    private static String searchSql(TableSchema table, Names names) {
        StringBuilder sql = new StringBuilder();
        sql.append("-- Scaffolded search for the ").append(names.table())
                .append(" table; runnable as-is in a plain SQL tool. The ORDER BY lives in an\n");
        sql.append(
                "-- embedded variable, applied at render time from the sort/dir inputs (an enum\n");
        sql.append("-- allowlist), so a plain tool runs the base query unordered.\n");
        sql.append("select\n");
        List<TableSchema.Column> listed = new ArrayList<>();
        listed.add(names.pk());
        listed.addAll(table.dataColumns());
        for (int i = 0; i < listed.size(); i++) {
            sql.append("  t.").append(Names.columnName(listed.get(i)))
                    .append(i + 1 < listed.size() ? ",\n" : "\n");
        }
        sql.append("from\n  ").append(names.table()).append(" t\n");
        names.searchColumn().ifPresent(search -> sql.append("""
                where
                  1 = 1
                /*%%if q != null && q != "" */
                  and t.%s like /* q */ 'sample'
                /*%%end*/
                """.formatted(Names.columnName(search))));
        // The whole ORDER BY lives inside an embedded variable, so the statement stays runnable in a
        // plain SQL tool (the comment is skipped). {sort}/{dir} are enum-constrained inputs (the
        // route allowlists them), interpolated into the SQL text at render time; the primary key is
        // a stable tiebreaker so equal-keyed rows page deterministically.
        sql.append("/*# order by t.{sort} {dir}, t.").append(names.pkColumn()).append(" */\n");
        sql.append(";\n");
        return sql.toString();
    }

    /** Slot fragments the scaffolded views pull in (customization ladder L1). */
    private static String fragsFile(TableSchema table, Names names) {
        String deleteVersion = table.versionColumn().isPresent()
                ? "  <input type=\"hidden\" name=\"version\" th:value=\"${v.row['version']}\">\n"
                : "";
        return """
                <!-- Scaffolded slot fragments for the %s pages: the list's New button, the form
                     pages' back link, and the confirmed delete the edit view mounts in its footer
                     slot (docs/declarative-views.md, docs/hypermedia-ui.md). -->
                <html xmlns:th="http://www.thymeleaf.org">
                <a th:fragment="new-link" class="hc-button" data-variant="primary" href="%s/new">New</a>
                <a th:fragment="back-link" class="hc-button" data-variant="ghost" data-size="sm" href="%s">&larr; %s</a>
                <form th:fragment="confirm-delete" id="%s-delete-form" method="post" th:action="|%s/${v.row['%s']}/delete|"
                      th:attr="hx-post=|%s/${v.row['%s']}/delete|" hx-trigger="hc:confirmed"
                      hx-target="#%s-delete-form-errors" hx-swap="innerHTML"
                      hx-disabled-elt="find button[type=submit]" hx-indicator="find .hc-spinner">
                  <input type="hidden" name="_csrf" th:value="${_csrf}">
                  <div id="%s-delete-form-errors"></div>
                %s  <span class="hc-action">
                    <button type="submit" class="hc-button" data-variant="error"
                            data-hc-confirm="Delete this record?" data-hc-confirm-title="Confirm delete"
                            data-hc-confirm-ok="Delete" data-hc-confirm-variant="error">Delete</button>
                    <span class="hc-spinner htmx-indicator" aria-hidden="true"></span>
                  </span>
                </form>
                </html>
                """
                .formatted(names.table(), names.url(), names.url(), names.title(),
                        names.entity(), names.url(), names.pkColumn(), names.url(),
                        names.pkColumn(),
                        names.entity(), names.entity(), deleteVersion);
    }

    private String newRoute(Names names) {
        // Browser-authed so the create form's page carries the CSRF meta tag.
        return """
                # Scaffolded create form page for the %s table.
                version: tesseraql/v1
                id: %s.new
                kind: route
                recipe: page

                security:
                  auth: browser
                  policy: app.read

                response:
                  html:
                    view: new.view.yml
                %s""".formatted(names.table(), names.entity(), cspHeaders());
    }

    /** The create form view: every field derives from the create route's input: block. */
    private static String newView(Names names) {
        return """
                # Scaffolded create view for the %s table: renders through the tql/view/form
                # pattern (docs/declarative-views.md); fields derive from the create route.
                version: tesseraql/v1
                id: %s.new
                kind: view
                view: form
                title: New
                action: %s/create
                slots:
                  header: ../frags.html::back-link
                """.formatted(names.table(), names.entity(), names.url());
    }

    private static String createRoute(TableSchema table, Names names) {
        boolean generatedKey = names.pk().autoGenerated();
        StringBuilder route = new StringBuilder();
        route.append("""
                # Scaffolded create command for the %s table: one transaction, audit binds, and
                # field-level constraint errors (docs/transactional-writes.md).
                version: tesseraql/v1
                id: %s.create
                kind: route
                recipe: command-json

                """.formatted(names.table(), names.entity()));
        route.append(inputBlock(names, formColumns(table, names)));
        route.append("""

                inputPolicy:
                  unknownFields: reject

                security:
                  auth: browser
                  policy: app.write
                  csrf: true
                """);
        if (generatedKey) {
            route.append("""

                    steps:
                      record:
                        file: insert.sql
                        mode: update
                        keys: [%s]
                    """.formatted(names.pkColumn()));
            route.append(paramsBlock("    ", formColumns(table, names)));
            route.append("""

                    response:
                      redirect:
                        location: %s/{steps.record.keys.%s}
                    """.formatted(names.url(), names.pkColumn()));
        } else {
            route.append("""

                    sql:
                      file: insert.sql
                      mode: update
                    """);
            route.append(paramsBlock("  ", formColumns(table, names)));
            route.append("""

                    response:
                      redirect:
                        location: %s/{params.%s}
                    """.formatted(names.url(), names.pkField()));
        }
        return route.toString();
    }

    private static String insertSql(TableSchema table, Names names) {
        List<String> columns = new ArrayList<>();
        List<String> values = new ArrayList<>();
        for (TableSchema.Column column : table.columns()) {
            if (table.isPrimaryKey(column) && column.autoGenerated()) {
                continue;
            }
            columns.add(Names.columnName(column));
            if (column.name().equalsIgnoreCase(TableSchema.VERSION_COLUMN)) {
                values.add("1");
            } else if (table.isAuditColumn(column)) {
                values.add(auditBind(column));
            } else {
                values.add("/* " + Names.field(column) + " */ " + dummy(column));
            }
        }
        StringBuilder sql = new StringBuilder();
        sql.append("-- Scaffolded insert for the ").append(names.table())
                .append(" table: audit columns stay explicit in the SQL (Phase 18).\n");
        sql.append("insert into ").append(names.table()).append(" (\n");
        for (int i = 0; i < columns.size(); i++) {
            sql.append("  ").append(columns.get(i)).append(i + 1 < columns.size() ? ",\n" : "\n");
        }
        sql.append(") values (\n");
        for (int i = 0; i < values.size(); i++) {
            sql.append("  ").append(values.get(i)).append(i + 1 < values.size() ? ",\n" : "\n");
        }
        // No statement terminator on command SQL (matching transactional-writes.md): drivers
        // append RETURNING for generated-key capture, which a trailing semicolon would break.
        sql.append(")\n");
        return sql.toString();
    }

    // ---------------------------------------------------------------- detail / edit

    private String detailRoute(TableSchema table, Names names) {
        // The path parameter is declared as a typed input: raw path values are strings, and the
        // coerced params.* view is what binds cleanly against a typed key column.
        return """
                # Scaffolded detail and edit page for the %s table.
                version: tesseraql/v1
                id: %s.detail
                kind: route
                recipe: query-html

                %ssecurity:
                  auth: browser
                  policy: app.read

                sql:
                  file: select.sql
                  mode: query
                  params:
                    %s: params.%s

                response:
                  html:
                    view: edit.view.yml
                %s""".formatted(names.table(), names.entity(),
                inputBlock(names, List.of(names.pk())) + "\n", names.pkField(), names.pkField(),
                cspHeaders());
    }

    private static String selectSql(TableSchema table, Names names) {
        StringBuilder sql = new StringBuilder();
        sql.append("-- Scaffolded single-row select for the ").append(names.table())
                .append(" table.\n");
        sql.append("select\n");
        List<TableSchema.Column> columns = table.columns();
        for (int i = 0; i < columns.size(); i++) {
            sql.append("  t.").append(Names.columnName(columns.get(i)))
                    .append(i + 1 < columns.size() ? ",\n" : "\n");
        }
        sql.append("from\n  ").append(names.table()).append(" t\n");
        sql.append("where\n  t.").append(names.pkColumn()).append(" = /* ")
                .append(names.pkField()).append(" */ ").append(dummy(names.pk())).append("\n;\n");
        return sql.toString();
    }

    /** The edit form view: fields derive from the update route, version rides hidden. */
    private static String editView(TableSchema table, Names names) {
        StringBuilder yml = new StringBuilder();
        yml.append("""
                # Scaffolded edit view for the %s table: renders through the tql/view/form
                # pattern (docs/declarative-views.md). Fields derive from the update route's
                # input: block; the footer slot carries the confirmed delete.
                version: tesseraql/v1
                id: %s.edit
                kind: view
                view: form
                title: Edit
                action: %s/{%s}/update
                fields:
                """.formatted(names.table(), names.entity(), names.url(), names.pkField()));
        for (TableSchema.Column column : table.dataColumns()) {
            yml.append("  - name: ").append(Names.field(column)).append('\n');
        }
        if (table.versionColumn().isPresent()) {
            yml.append("  - name: version\n    widget: hidden\n");
        }
        yml.append("""
                slots:
                  header: ../frags.html::back-link
                  footer: ../frags.html::confirm-delete
                """);
        return yml.toString();
    }

    private static String updateRoute(TableSchema table, Names names) {
        boolean locked = table.versionColumn().isPresent();
        StringBuilder route = new StringBuilder();
        route.append("""
                # Scaffolded update command for the %s table%s.
                version: tesseraql/v1
                id: %s.update
                kind: route
                recipe: command-json

                """.formatted(names.table(),
                locked ? ": optimistic locking turns a stale edit into 409 Conflict" : "",
                names.entity()));
        // The key arrives as a path parameter; declaring it as an input coerces it to the key
        // column's type, like every other bind (raw path/body values are strings).
        List<TableSchema.Column> inputs = new ArrayList<>();
        if (names.pk().autoGenerated()) {
            inputs.add(names.pk());
        }
        inputs.addAll(formColumns(table, names));
        table.versionColumn().ifPresent(inputs::add);
        route.append(inputBlock(names, inputs));
        route.append("""

                inputPolicy:
                  unknownFields: reject

                security:
                  auth: browser
                  policy: app.write
                  csrf: true
                """);
        route.append("""

                sql:
                  file: update.sql
                  mode: update
                """);
        if (locked) {
            route.append("  expect:\n    rows: 1\n    onMismatch: conflict\n");
        }
        route.append("  params:\n    ").append(names.pkField()).append(": params.")
                .append(names.pkField()).append('\n');
        for (TableSchema.Column column : formColumns(table, names)) {
            route.append("    ").append(Names.field(column)).append(": params.")
                    .append(Names.field(column)).append('\n');
        }
        if (locked) {
            route.append("    version: params.version\n");
        }
        route.append("""

                response:
                  redirect:
                    location: %s/{path.%s}
                """.formatted(names.url(), names.pkField()));
        return route.toString();
    }

    private static String updateSql(TableSchema table, Names names) {
        boolean locked = table.versionColumn().isPresent();
        StringBuilder sql = new StringBuilder();
        sql.append("-- Scaffolded update for the ").append(names.table()).append(" table")
                .append(locked
                        ? ": the version predicate pairs with expect.rows (Phase 18)."
                        : ".")
                .append('\n');
        sql.append("update ").append(names.table()).append("\nset\n");
        List<String> assignments = new ArrayList<>();
        for (TableSchema.Column column : formColumns(table, names)) {
            assignments.add(Names.columnName(column) + " = /* " + Names.field(column) + " */ "
                    + dummy(column));
        }
        if (locked) {
            assignments.add("version = version + 1");
        }
        if (table.column("updated_by").isPresent()) {
            assignments.add("updated_by = /* audit.user */ 'someone'");
        }
        if (table.column("updated_at").isPresent()) {
            assignments.add("updated_at = /* audit.now */ '2026-01-01 00:00:00'");
        }
        for (int i = 0; i < assignments.size(); i++) {
            sql.append("  ").append(assignments.get(i))
                    .append(i + 1 < assignments.size() ? ",\n" : "\n");
        }
        sql.append("where\n  ").append(names.pkColumn()).append(" = /* ")
                .append(names.pkField()).append(" */ ").append(dummy(names.pk())).append('\n');
        if (locked) {
            sql.append("  and version = /* version */ 1\n");
        }
        return sql.toString();
    }

    private static String deleteRoute(TableSchema table, Names names) {
        boolean locked = table.versionColumn().isPresent();
        StringBuilder route = new StringBuilder();
        route.append("""
                # Scaffolded delete command for the %s table.
                version: tesseraql/v1
                id: %s.delete
                kind: route
                recipe: command-json
                """.formatted(names.table(), names.entity()));
        route.append('\n');
        List<TableSchema.Column> inputs = new ArrayList<>();
        inputs.add(names.pk());
        table.versionColumn().ifPresent(inputs::add);
        route.append(inputBlock(names, inputs));
        route.append("""

                inputPolicy:
                  unknownFields: reject

                security:
                  auth: browser
                  policy: app.write
                  csrf: true

                sql:
                  file: delete.sql
                  mode: update
                """);
        if (locked) {
            route.append("  expect:\n    rows: 1\n    onMismatch: conflict\n");
        }
        route.append("  params:\n    ").append(names.pkField()).append(": params.")
                .append(names.pkField()).append('\n');
        if (locked) {
            route.append("    version: params.version\n");
        }
        route.append("""

                response:
                  redirect:
                    location: %s
                """.formatted(names.url()));
        return route.toString();
    }

    private static String deleteSql(TableSchema table, Names names) {
        StringBuilder sql = new StringBuilder();
        sql.append("-- Scaffolded delete for the ").append(names.table()).append(" table.\n");
        sql.append("delete from ").append(names.table()).append("\nwhere\n  ")
                .append(names.pkColumn()).append(" = /* ").append(names.pkField()).append(" */ ")
                .append(dummy(names.pk())).append('\n');
        if (table.versionColumn().isPresent()) {
            sql.append("  and version = /* version */ 1\n");
        }
        return sql.toString();
    }

    // ---------------------------------------------------------------- tests

    private static String testSuite(TableSchema table, Names names) {
        StringBuilder suite = new StringBuilder();
        suite.append("""
                # Scaffolded suite for the %s table (design ch. 13): exercises the generated
                # queries with data-independent expectations, so it passes against any contents.
                tests:
                """.formatted(names.table()));
        // Every search.sql case sets sort/dir: the ORDER BY is an embedded variable, so it needs
        // them to render (input defaults apply only on the live route, not to a raw SQL test).
        suite.append("""
                  - name: the %s search runs without a filter
                    sql:
                      file: %s/search.sql
                    params:
                      sort: %s
                      dir: asc
                """.formatted(names.table(), names.dir(), names.pkColumn()));
        names.searchColumn().ifPresent(search -> suite.append("""

                  - name: the %s search filters by %s
                    sql:
                      file: %s/search.sql
                    params:
                      q: no-such-row
                      sort: %s
                      dir: asc
                    expect:
                      rowCount: 0
                """.formatted(names.table(), Names.columnName(search), names.dir(),
                names.pkColumn())));
        // The embedded ORDER BY adds no branches; one case with a non-default column and descending
        // direction proves it renders and runs (data-independent).
        String sortCol = table.dataColumns().isEmpty()
                ? names.pkColumn()
                : Names.columnName(table.dataColumns().get(0));
        suite.append("""

                  - name: the %s search sorts by %s descending
                    sql:
                      file: %s/search.sql
                    params:
                      sort: %s
                      dir: desc
                """.formatted(names.table(), sortCol, names.dir(), sortCol));
        suite.append("""

                  - name: the %s detail select misses for an unknown key
                    sql:
                      file: %s/select.sql
                    params:
                      %s: %s
                    expect:
                      rowCount: 0
                """.formatted(names.table(), names.detailDir(), names.pkField(),
                names.pk().isIntegerLike() ? "-1" : "no-such-key"));
        return suite.toString();
    }

    // ---------------------------------------------------------------- shared pieces

    /** The form-editable columns: the data columns, plus a non-generated primary key. */
    private static List<TableSchema.Column> formColumns(TableSchema table, Names names) {
        List<TableSchema.Column> columns = new ArrayList<>();
        if (!names.pk().autoGenerated()) {
            columns.add(names.pk());
        }
        columns.addAll(table.dataColumns());
        return columns;
    }

    /**
     * Route inputs reference the table's scaffolded field domains (docs/field-domains.md) and
     * state only the operational choice — whether this operation requires the field. The field
     * itself (type, size, parse format) lives once in {@code domains/<table>.yml}, so a schema
     * change re-scaffolds one file, not every route.
     */
    private static String inputBlock(Names names, List<TableSchema.Column> columns) {
        StringBuilder input = new StringBuilder("input:\n");
        for (TableSchema.Column column : columns) {
            input.append("  ").append(Names.field(column)).append(":\n");
            input.append("    domain: ").append(names.table()).append('.')
                    .append(Names.field(column)).append('\n');
            if (!column.nullable()) {
                input.append("    required: true\n");
            }
        }
        return input.toString();
    }

    /**
     * The table's field domains and constraint catalog: the DDL-derived knowledge, declared once
     * (docs/field-domains.md). Re-scaffolding after a schema change updates this file; the
     * routes referencing the domains stay untouched.
     */
    private static String domainsFile(TableSchema table, Names names) {
        StringBuilder yml = new StringBuilder();
        yml.append("""
                # Scaffolded field domains for the %s table (tesseraql scaffold crud --table %s):
                # every route's input: references these, so the DDL-derived knowledge lives once
                # (docs/field-domains.md). Route-operational keys (required) stay in the routes.
                version: tesseraql/v1

                domains:
                """.formatted(names.table(), names.table()));
        java.util.LinkedHashSet<TableSchema.Column> columns = new java.util.LinkedHashSet<>();
        columns.add(names.pk());
        table.versionColumn().ifPresent(columns::add);
        columns.addAll(table.dataColumns());
        for (TableSchema.Column column : columns) {
            yml.append("  ").append(names.table()).append('.').append(Names.field(column))
                    .append(":\n");
            yml.append("    type: ").append(column.inputType()).append('\n');
            if (column.isCharacter() && column.size() > 0) {
                yml.append("    maxLength: ").append(column.size()).append('\n');
            }
            switch (column.inputType()) {
                case "date" -> yml.append("    format: yyyy-MM-dd\n");
                case "datetime" -> yml.append("    format: \"yyyy-MM-dd'T'HH:mm\"\n");
                default -> {
                }
            }
        }
        if (!table.uniqueIndexes().isEmpty()) {
            yml.append("\nconstraints:\n");
            table.uniqueIndexes().forEach((index, column) -> {
                yml.append("  ").append(index.toLowerCase(Locale.ROOT)).append(":\n");
                yml.append("    field: ")
                        .append(Names.camel(column.toLowerCase(Locale.ROOT))).append('\n');
            });
        }
        return yml.toString();
    }

    /**
     * Bind sources read the coerced {@code params.*} view of the declared inputs — never the raw
     * {@code body.*} values, which stay strings for browser form posts (Phase 22 input parsing).
     */
    private static String paramsBlock(String indent, List<TableSchema.Column> columns) {
        StringBuilder params = new StringBuilder(indent).append("params:\n");
        for (TableSchema.Column column : columns) {
            params.append(indent).append("  ").append(Names.field(column)).append(": params.")
                    .append(Names.field(column)).append('\n');
        }
        return params.toString();
    }

    private static String auditBind(TableSchema.Column column) {
        return switch (Names.columnName(column)) {
            case "created_by", "updated_by" -> "/* audit.user */ 'someone'";
            default -> "/* audit.now */ '2026-01-01 00:00:00'";
        };
    }

    /** A SQL-tool-runnable dummy literal for a bind (the 2-way SQL convention). */
    private static String dummy(TableSchema.Column column) {
        return switch (column.inputType()) {
            case "integer", "number" -> "1";
            case "boolean" -> "true";
            case "date" -> "'2026-01-01'";
            case "datetime" -> "'2026-01-01 00:00:00'";
            default -> "'sample'";
        };
    }
}
