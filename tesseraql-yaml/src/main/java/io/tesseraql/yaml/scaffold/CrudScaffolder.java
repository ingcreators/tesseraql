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
 * post/redirect/get flow; non-generated key columns become required form fields — a composite
 * key scaffolds as nested path segments (docs/list-surface.md decision 4),</li>
 * <li>a numeric {@code version} column pairs an optimistic-locking predicate with
 * {@code expect: rowCount: 1} so a stale edit answers {@code 409 Conflict},</li>
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
    private final java.util.Set<String> catalogTables;

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
        this(securityDefaults, responseHeaderDefaults, java.util.Set.of());
    }

    /**
     * The variant that knows which tables the app's code catalogs read (docs/lookups.md,
     * decision 13), so a maintenance screen generated for one of them declares
     * {@code invalidates:} itself.
     *
     * <p>Only for those tables. An {@code invalidates:} on a table no catalog reads drops
     * nothing and is exactly what {@code TQL-FIELD-4620} warns about — a generator must not
     * emit what its own linter would flag.
     */
    public CrudScaffolder(SecurityDefaults securityDefaults,
            ResponseHeaderDefaults responseHeaderDefaults, java.util.Set<String> catalogTables) {
        this.securityDefaults = securityDefaults;
        this.responseHeaderDefaults = responseHeaderDefaults;
        this.catalogTables = catalogTables == null
                ? java.util.Set.of()
                : java.util.Set.copyOf(catalogTables);
    }

    /**
     * The {@code invalidates:} block for a table a catalog reads, or nothing. The write drops
     * the held names so the next screen shows what was just saved, instead of waiting out the
     * hold.
     */
    private String invalidatesBlock(Names names) {
        return catalogTables.contains(names.table())
                ? "\ninvalidates: [" + names.table() + "]\n"
                : "";
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
        List<TableSchema.Column> pks = table.primaryKeyColumns();
        if (pks.isEmpty()) {
            throw new TqlException(UNSUPPORTED_TABLE, "Table '" + table.name()
                    + "' needs a primary key to scaffold (tables without one are not"
                    + " supported)");
        }
        Names names = new Names(table, pks);
        List<ScaffoldedFile> files = new ArrayList<>();
        files.add(new ScaffoldedFile("domains/" + names.table() + ".yml",
                domainsFile(table, names)));
        if (!table.uniqueIndexes().isEmpty() || !table.foreignKeys().isEmpty()) {
            files.add(new ScaffoldedFile("rules/" + names.table() + ".yml",
                    rulesFile(table, names)));
            table.uniqueIndexes().forEach((index, column) -> files.add(new ScaffoldedFile(
                    "rules/" + names.table() + "-" + column.toLowerCase(Locale.ROOT)
                            .replace('_', '-') + "-free.sql",
                    uniqueRuleSql(names, column))));
            table.foreignKeys().forEach(fk -> files.add(new ScaffoldedFile(
                    "rules/" + names.table() + "-" + fk.column().toLowerCase(Locale.ROOT)
                            .replace('_', '-') + "-exists.sql",
                    fkRuleSql(fk))));
        }
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
        files.add(new ScaffoldedFile(names.detailDir() + "/get.yml", detailRoute(names)));
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
        SecuritySpec read = securityDefaults.resolve(base, null);
        SecuritySpec write = securityDefaults.resolve(base + "/create", null);
        return read != null && "browser".equals(read.auth())
                && write != null && "browser".equals(write.auth())
                && write.csrfEnforced("POST");
    }

    /**
     * Drops the security keys the app defaults reproduce — exactly {@code auth: browser} and
     * {@code csrf: required} — from a generated route document; {@code policy:} stays route-local.
     */
    private static ScaffoldedFile slimSecurity(ScaffoldedFile file) {
        if (!file.path().endsWith(".yml")) {
            return file;
        }
        return new ScaffoldedFile(file.path(), file.content()
                .replace("security:\n  auth: browser\n", "security:\n")
                .replace("  csrf: required\n", ""));
    }

    /** Derived, deterministic naming for one table. */
    private record Names(TableSchema schema, List<TableSchema.Column> pks) {

        /** The first key column — the sort default and the single-key convenience. */
        TableSchema.Column pk() {
            return pks.get(0);
        }

        /** The key column names, lowercased, in key-sequence order. */
        List<String> pkColumns() {
            return pks.stream().map(Names::columnName).toList();
        }

        /** The key field names — identical to the columns (docs/unicode-identifiers.md). */
        List<String> pkFields() {
            return pkColumns();
        }

        /** Whether the whole key is one auto-generated column — the RETURNING-capture shape. */
        boolean generatedKey() {
            return pks.size() == 1 && pk().autoGenerated();
        }

        /** The key as a path template suffix, e.g. {@code {order_id}/{line_no}}. */
        String keyPath() {
            return pkFields().stream().map(field -> "{" + field + "}")
                    .collect(java.util.stream.Collectors.joining("/"));
        }

        /** The route back up from the detail directory — one {@code ../} per key segment. */
        String upFromDetail() {
            return "../".repeat(pks.size());
        }

        /** The key as Thymeleaf row segments, e.g. {@code ${v.row['a']}/${v.row['b']}}. */
        String rowKeyPath() {
            return pkColumns().stream().map(column -> "${v.row['" + column + "']}")
                    .collect(java.util.stream.Collectors.joining("/"));
        }

        /**
         * The declared row key of the list view (docs/list-surface.md decision 2):
         * {@code key: id}, or {@code key: [a, b]} for a composite.
         */
        String keyYaml() {
            return pks.size() == 1
                    ? "key: " + pkColumn()
                    : "key: [" + String.join(", ", pkColumns()) + "]";
        }

        /** The lowercased table name: the URL segment and the test-suite file stem. */
        String table() {
            return schema.name().toLowerCase(Locale.ROOT);
        }

        /**
         * The route-id prefix — the table name verbatim (docs/unicode-identifiers.md): one
         * name from DDL to route id, no case conversion.
         */
        String entity() {
            return table();
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
            return dir() + "/" + keyPath();
        }

        /** The first key column name — where one representative column is enough. */
        String pkColumn() {
            return columnName(pk());
        }

        String pkField() {
            return pkColumn();
        }

        /** The first character-type data column, driving the list page's live search. */
        Optional<TableSchema.Column> searchColumn() {
            return schema.dataColumns().stream()
                    .filter(TableSchema.Column::isCharacter)
                    .findFirst();
        }

        static String label(String snake) {
            String words = snake.toLowerCase(Locale.ROOT).replace('_', ' ').trim();
            return words.isEmpty()
                    ? words
                    : Character.toUpperCase(words.charAt(0)) + words.substring(1);
        }

        /**
         * The YAML field name — the column name verbatim (docs/unicode-identifiers.md): the
         * field <em>is</em> the column, so binds, model keys, and JSON keys all carry it.
         */
        static String field(TableSchema.Column column) {
            return columnName(column);
        }

        static String columnName(TableSchema.Column column) {
            return column.name().toLowerCase(Locale.ROOT);
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

                sources:
                  main:
                    sql:
                      file: search.sql
                      mode: query
                      params:
                %s        sort: query.sort
                        dir: query.dir

                pagination:
                  size: 50
                  maxSize: 200
                  count: true

                response:
                  html:
                    view: %s
                %s""".formatted(names.table(), names.table(), names.entity(),
                names.searchColumn().isPresent()
                        ? "  q:\n    type: string\n    required: false\n    maxLength: 200\n"
                        : "",
                sortEnum(table, names), names.pkColumn(),
                names.searchColumn().isPresent() ? "        q: query.q\n" : "", names.entity(),
                cspHeaders()));
        return yml.toString();
    }

    /** The sortable-column allowlist: every primary-key column plus every data column. */
    private static String sortEnum(TableSchema table, Names names) {
        StringBuilder values = new StringBuilder(String.join(", ", names.pkColumns()));
        for (TableSchema.Column column : table.dataColumns()) {
            values.append(", ").append(Names.columnName(column));
        }
        return values.toString();
    }

    /** The list view: the grid page, sortable columns, live search, the per-row Open action. */
    private static String listView(TableSchema table, Names names) {
        StringBuilder yml = new StringBuilder();
        yml.append("""
                # Scaffolded list view for the %s table: renders through the tql/view/list-page
                # pattern (docs/declarative-views.md "The grid page") — sortable headers, a live
                # search box, row identity for return-to-the-list, and the header slot's New
                # button.
                version: tesseraql/v1
                id: %s
                kind: view
                recipe: list
                layout: page
                %s
                title: %s
                %scolumns:
                """.formatted(names.table(), names.entity(), names.keyYaml(), names.title(),
                names.searchColumn().isPresent() ? "search: q\n" : ""));
        for (String column : names.pkColumns()) {
            yml.append("  - name: ").append(column).append("\n    sortable: true\n");
        }
        for (TableSchema.Column column : table.dataColumns()) {
            yml.append("  - name: ").append(Names.columnName(column))
                    .append("\n    sortable: true\n");
        }
        yml.append("""
                  - name: %s
                    label: ""
                    text: Open
                    link: %s/%s
                slots:
                  header: frags.html::new-link
                """.formatted(names.pkColumn(), names.url(), names.keyPath()));
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
        listed.addAll(names.pks());
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
        sql.append("/*# order by t.{sort} {dir}");
        for (String column : names.pkColumns()) {
            sql.append(", t.").append(column);
        }
        sql.append(" */\n");
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
                <a th:fragment="new-link" class="hc-button" data-variant="primary" th:href="@{%s/new}">New</a>
                <a th:fragment="back-link" class="hc-button" data-variant="ghost" data-size="sm" th:href="@{%s}">&larr; %s</a>
                <form th:fragment="confirm-delete" id="%s-delete-form" method="post" th:action="@{|%s/%s/delete|}"
                      th:attr="hx-post=@{|%s/%s/delete|}" hx-trigger="hc:confirmed"
                      hx-target="#%s-delete-form-errors" hx-swap="innerHTML"
                      hx-disabled-elt="find button[type=submit]" hx-indicator="find .hc-spinner">
                  <input type="hidden" name="_csrf" th:value="${_csrf}">
                  <div id="%s-delete-form-errors"></div>
                %s  <span class="hc-action">
                    <button type="submit" class="hc-button" data-variant="error"
                            data-hc-confirm="Delete this record?" data-hc-confirm-title="Confirm delete"
                            data-hc-confirm-label="Delete" data-hc-confirm-variant="error">Delete</button>
                    <span class="hc-spinner htmx-indicator" aria-hidden="true"></span>
                  </span>
                </form>
                </html>
                """
                .formatted(names.table(), names.url(), names.url(), names.title(),
                        names.entity(), names.url(), names.rowKeyPath(), names.url(),
                        names.rowKeyPath(),
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
                    view: %s.new
                %s""".formatted(names.table(), names.entity(), names.entity(), cspHeaders());
    }

    /** The create form view: every field derives from the create route's input: block. */
    private static String newView(Names names) {
        return """
                # Scaffolded create view for the %s table: renders through the tql/view/form
                # pattern (docs/declarative-views.md); fields derive from the create route.
                version: tesseraql/v1
                id: %s.new
                kind: view
                recipe: form
                title: New
                action: %s/create
                slots:
                  header: ../frags.html::back-link
                """.formatted(names.table(), names.entity(), names.url());
    }

    private String createRoute(TableSchema table, Names names) {
        boolean generatedKey = names.generatedKey();
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
                  csrf: required
                """);
        route.append(validateBlock(table, names));
        if (generatedKey) {
            route.append("""

                    steps:
                      - id: record
                        sql:
                          file: insert.sql
                          mode: update
                          keys: [%s]
                    """.formatted(names.pkColumn()));
            route.append(paramsBlock("      ", formColumns(table, names)));
            route.append("""

                    response:
                      redirect:
                        location: %s/{steps.record.keys.%s}
                    """.formatted(names.url(), names.pkColumn()));
        } else {
            route.append("""

                    steps:
                      - id: main
                        sql:
                          file: insert.sql
                          mode: update
                    """);
            route.append(paramsBlock("      ", formColumns(table, names)));
            route.append("""

                    response:
                      redirect:
                        location: %s/%s
                    """.formatted(names.url(), names.pkFields().stream()
                    .map(field -> "{params." + field + "}")
                    .collect(java.util.stream.Collectors.joining("/"))));
        }
        route.append(invalidatesBlock(names));
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

    private String detailRoute(Names names) {
        // Every path parameter is declared as a typed input: raw path values are strings, and
        // the coerced params.* view is what binds cleanly against the typed key columns.
        return """
                # Scaffolded detail and edit page for the %s table.
                version: tesseraql/v1
                id: %s.detail
                kind: route
                recipe: query-html

                %ssecurity:
                  auth: browser
                  policy: app.read

                sources:
                  main:
                    sql:
                      file: select.sql
                      mode: query
                      params:
                %sresponse:
                  html:
                    view: %s.edit
                %s""".formatted(names.table(), names.entity(),
                inputBlock(names, names.pks()) + "\n", keyParams(names, "        "),
                names.entity(), cspHeaders());
    }

    /** One {@code field: params.field} line per key column at the given indent. */
    private static String keyParams(Names names, String indent) {
        StringBuilder params = new StringBuilder();
        for (String field : names.pkFields()) {
            params.append(indent).append(field).append(": params.").append(field).append('\n');
        }
        return params.toString();
    }

    /**
     * The key predicate, one 2-way-SQL bind per key column, and-joined across lines — the
     * WHERE body every by-key statement (select, update, delete) shares.
     */
    private static String keyWhere(Names names, String prefix) {
        StringBuilder where = new StringBuilder();
        List<TableSchema.Column> pks = names.pks();
        for (int i = 0; i < pks.size(); i++) {
            where.append(i == 0 ? "  " : "  and ").append(prefix)
                    .append(Names.columnName(pks.get(i))).append(" = /* ")
                    .append(Names.field(pks.get(i))).append(" */ ")
                    .append(dummy(pks.get(i))).append('\n');
        }
        return where.toString();
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
        sql.append("where\n").append(keyWhere(names, "t.")).append(";\n");
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
                recipe: form
                title: Edit
                action: %s/%s/update
                fields:
                """.formatted(names.table(), names.entity(), names.url(), names.keyPath()));
        for (TableSchema.Column column : table.dataColumns()) {
            yml.append("  - name: ").append(Names.field(column)).append('\n');
        }
        if (table.versionColumn().isPresent()) {
            yml.append("  - name: version\n    widget: hidden\n");
        }
        yml.append("""
                slots:
                  header: %sfrags.html::back-link
                  footer: %sfrags.html::confirm-delete
                """.formatted(names.upFromDetail(), names.upFromDetail()));
        return yml.toString();
    }

    private String updateRoute(TableSchema table, Names names) {
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
        // The key arrives as path parameters; declaring each as an input coerces it to the key
        // column's type, like every other bind (raw path/body values are strings). Assigned key
        // columns are already form columns; only generated ones need declaring here.
        List<TableSchema.Column> inputs = new ArrayList<>();
        for (TableSchema.Column pk : names.pks()) {
            if (pk.autoGenerated()) {
                inputs.add(pk);
            }
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
                  csrf: required
                """);
        route.append(validateBlock(table, names));
        route.append("""

                steps:
                  - id: main
                    sql:
                      file: update.sql
                      mode: update
                """);
        if (locked) {
            route.append("      expect:\n        rowCount: 1\n        onMismatch: conflict\n");
        }
        route.append("      params:\n").append(keyParams(names, "        "));
        for (TableSchema.Column column : formColumns(table, names)) {
            // An assigned key is a form column too, and it is already bound above — writing it
            // twice was a duplicate key the parser used to resolve last-one-wins.
            if (names.pkFields().contains(Names.field(column))) {
                continue;
            }
            route.append("        ").append(Names.field(column)).append(": params.")
                    .append(Names.field(column)).append('\n');
        }
        if (locked) {
            route.append("        version: params.version\n");
        }
        route.append("""

                response:
                  redirect:
                    location: %s/%s
                """.formatted(names.url(), names.pkFields().stream()
                .map(field -> "{path." + field + "}")
                .collect(java.util.stream.Collectors.joining("/"))));
        route.append(invalidatesBlock(names));
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
        sql.append("where\n").append(keyWhere(names, ""));
        if (locked) {
            sql.append("  and version = /* version */ 1\n");
        }
        return sql.toString();
    }

    private String deleteRoute(TableSchema table, Names names) {
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
        inputs.addAll(names.pks());
        table.versionColumn().ifPresent(inputs::add);
        route.append(inputBlock(names, inputs));
        route.append("""

                inputPolicy:
                  unknownFields: reject

                security:
                  auth: browser
                  policy: app.write
                  csrf: required

                steps:
                  - id: main
                    sql:
                      file: delete.sql
                      mode: update
                """);
        if (locked) {
            route.append("      expect:\n        rowCount: 1\n        onMismatch: conflict\n");
        }
        route.append("      params:\n").append(keyParams(names, "        "));
        if (locked) {
            route.append("        version: params.version\n");
        }
        route.append("""

                response:
                  redirect:
                    location: %s
                """.formatted(names.url()));
        route.append(invalidatesBlock(names));
        return route.toString();
    }

    private static String deleteSql(TableSchema table, Names names) {
        StringBuilder sql = new StringBuilder();
        sql.append("-- Scaffolded delete for the ").append(names.table()).append(" table.\n");
        sql.append("delete from ").append(names.table()).append("\nwhere\n")
                .append(keyWhere(names, ""));
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
                version: tesseraql/v1
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
                """.formatted(names.table(), names.detailDir()));
        for (TableSchema.Column pk : names.pks()) {
            suite.append("      ").append(Names.field(pk)).append(": ")
                    .append(pk.isIntegerLike() ? "-1" : "no-such-key").append('\n');
        }
        suite.append("""
                    expect:
                      rowCount: 0
                """);
        return suite.toString();
    }

    // ---------------------------------------------------------------- shared pieces

    /** The form-editable columns: the data columns, plus every non-generated key column. */
    private static List<TableSchema.Column> formColumns(TableSchema table, Names names) {
        List<TableSchema.Column> columns = new ArrayList<>();
        for (TableSchema.Column pk : names.pks()) {
            if (!pk.autoGenerated()) {
                columns.add(pk);
            }
        }
        columns.addAll(table.dataColumns());
        return columns;
    }

    /**
     * The table's shared validation rules (docs/validation-rule-sets.md): a pre-write
     * uniqueness check per single-column unique index, shared by create and update — update
     * excludes the row being updated. The constraint catalog stays alongside
     * (docs/field-domains.md): the rule gives the friendly 422 before the write, the catalog
     * keeps the post-write race honest.
     */
    private static String rulesFile(TableSchema table, Names names) {
        StringBuilder yml = new StringBuilder();
        yml.append(
                """
                        # Scaffolded validation rules for the %s table (tesseraql scaffold crud --table %s):
                        # declared once, referenced from validate: blocks with use: (docs/validation-rule-sets.md).
                        version: tesseraql/v1

                        rules:
                        """
                        .formatted(names.table(), names.table()));
        table.uniqueIndexes().forEach((index, column) -> {
            String field = column.toLowerCase(Locale.ROOT);
            String sql = names.table() + "-" + column.toLowerCase(Locale.ROOT).replace('_', '-')
                    + "-free.sql";
            yml.append("  ").append(names.table()).append('_').append(field)
                    .append("_is_free:\n");
            yml.append("    file: ").append(sql).append('\n');
            String fieldType = table.column(column)
                    .map(TableSchema.Column::inputType).orElse("string");
            yml.append("    binds: { ").append(field).append(": ").append(fieldType);
            if (names.pks().size() == 1) {
                yml.append(", excludeId: ").append(names.pk().inputType());
            } else {
                for (TableSchema.Column pk : names.pks()) {
                    yml.append(", exclude_").append(Names.field(pk)).append(": ")
                            .append(pk.inputType());
                }
            }
            yml.append(" }\n");
            yml.append("    code: duplicate\n");
        });
        table.foreignKeys().forEach(fk -> {
            String field = fk.column().toLowerCase(Locale.ROOT);
            String sql = names.table() + "-" + fk.column().toLowerCase(Locale.ROOT)
                    .replace('_', '-') + "-exists.sql";
            yml.append("  ").append(names.table()).append('_').append(field)
                    .append("_exists:\n");
            yml.append("    file: ").append(sql).append('\n');
            yml.append("    binds: { ").append(field).append(": ")
                    .append(table.column(fk.column())
                            .map(TableSchema.Column::inputType).orElse("string"))
                    .append(" }\n");
            yml.append("    code: unknown\n");
        });
        return yml.toString();
    }

    /**
     * The FK-existence rule's 2-way SQL: a returned row is the violation — the referenced row
     * is missing. The reference guards nullable columns with {@code when:}, so an absent
     * optional value never reaches this query. This is the hook where "exists" grows into
     * "exists and is active": one edit here instead of one per route.
     */
    private static String fkRuleSql(TableSchema.ForeignKey fk) {
        String field = fk.column().toLowerCase(Locale.ROOT);
        return """
                -- A returned row is a violation (docs/validation-rule-sets.md): the referenced
                -- %s row does not exist.
                select '%s' as field
                where not exists (select 1 from %s where %s = /* %s */0)
                """.formatted(fk.refTable().toLowerCase(Locale.ROOT), field,
                fk.refTable().toLowerCase(Locale.ROOT), fk.refColumn().toLowerCase(Locale.ROOT),
                field);
    }

    /**
     * The uniqueness rule's 2-way SQL: a returned row is the violation. The {@code excludeId}
     * bind excludes the row being updated via a conditional directive, so create — where the
     * generated key is absent and the bind is null — checks against every row, portably across
     * dialects (no null-typed bind ever reaches the database).
     */
    private static String uniqueRuleSql(Names names, String column) {
        if (names.pks().size() == 1) {
            return """
                    -- A returned row is a violation (docs/validation-rule-sets.md): the value is
                    -- already taken by another row. Shared by create (excludeId null) and update.
                    select '%s' as field
                    from %s
                    where %s = /* %s */'sample'
                    /*%%if excludeId != null */
                      and %s <> /* excludeId */0
                    /*%%end*/
                    """.formatted(column.toLowerCase(Locale.ROOT), names.table(),
                    column.toLowerCase(Locale.ROOT), column.toLowerCase(Locale.ROOT),
                    names.pkColumn());
        }
        // A composite key excludes the row being updated by the whole key tuple: the exclude_*
        // binds arrive together (all null on create), so guarding on the first is guarding all.
        StringBuilder exclusion = new StringBuilder();
        List<TableSchema.Column> pks = names.pks();
        for (int i = 0; i < pks.size(); i++) {
            if (i > 0) {
                exclusion.append(" and ");
            }
            exclusion.append(Names.columnName(pks.get(i))).append(" = /* exclude_")
                    .append(Names.field(pks.get(i))).append(" */").append(dummy(pks.get(i)));
        }
        return """
                -- A returned row is a violation (docs/validation-rule-sets.md): the value is
                -- already taken by another row. Shared by create (exclude_* null) and update.
                select '%s' as field
                from %s
                where %s = /* %s */'sample'
                /*%%if exclude_%s != null */
                  and not (%s)
                /*%%end*/
                """.formatted(column.toLowerCase(Locale.ROOT), names.table(),
                column.toLowerCase(Locale.ROOT), column.toLowerCase(Locale.ROOT),
                names.pkField(), exclusion);
    }

    /**
     * The validate: block referencing the shared uniqueness and FK-existence rules. Create and
     * update wire the same params — the contract requires every declared bind, and the rule's
     * SQL guards {@code excludeId} itself: on create the key param is null (generated keys) or
     * a value no existing row carries, so nothing is excluded; on update it is the row being
     * updated.
     */
    private static String validateBlock(TableSchema table, Names names) {
        if (table.uniqueIndexes().isEmpty() && table.foreignKeys().isEmpty()) {
            return "";
        }
        StringBuilder yml = new StringBuilder("\nvalidate:\n");
        table.foreignKeys().forEach(fk -> {
            String field = fk.column().toLowerCase(Locale.ROOT);
            boolean nullable = table.columns().stream()
                    .filter(column -> column.name().equalsIgnoreCase(fk.column()))
                    .anyMatch(TableSchema.Column::nullable);
            yml.append("  ").append(field).append("_exists:\n");
            yml.append("    use: ").append(names.table()).append('_').append(field)
                    .append("_exists\n");
            if (nullable) {
                yml.append("    when: params.").append(field).append(" != null\n");
            }
            yml.append("    params:\n");
            yml.append("      ").append(field).append(": params.").append(field).append('\n');
            yml.append("    field: ").append(field).append('\n');
        });
        table.uniqueIndexes().forEach((index, column) -> {
            String field = column.toLowerCase(Locale.ROOT);
            yml.append("  ").append(field).append("_is_free:\n");
            yml.append("    use: ").append(names.table()).append('_').append(field)
                    .append("_is_free\n");
            yml.append("    params:\n");
            yml.append("      ").append(field).append(": params.").append(field).append('\n');
            if (names.pks().size() == 1) {
                yml.append("      excludeId: params.").append(names.pkField()).append('\n');
            } else {
                for (String pkField : names.pkFields()) {
                    yml.append("      exclude_").append(pkField).append(": params.")
                            .append(pkField).append('\n');
                }
            }
            yml.append("    field: ").append(field).append('\n');
        });
        return yml.toString();
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
        columns.addAll(names.pks());
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
                        .append(column.toLowerCase(Locale.ROOT)).append('\n');
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
