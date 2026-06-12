package io.tesseraql.yaml.scaffold;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
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
 * blocks (edit detection then leaves those files alone).
 */
public final class CrudScaffolder {

    private static final TqlErrorCode UNSUPPORTED_TABLE = new TqlErrorCode(TqlDomain.APP, 5203);
    private static final String CSP_HEADERS = """
                headers:
                  Content-Security-Policy: "default-src 'self'; style-src 'self' 'unsafe-inline'; frame-ancestors 'none'"
                  X-Content-Type-Options: nosniff
                  X-Frame-Options: DENY
                  Referrer-Policy: no-referrer
            """;

    /** Generates the CRUD file set for the table (paths relative to the app home). */
    public List<ScaffoldedFile> scaffold(TableSchema table) {
        TableSchema.Column pk = table.primaryKeyColumn().orElseThrow(() -> new TqlException(
                UNSUPPORTED_TABLE, "Table '" + table.name() + "' needs a single-column primary"
                        + " key to scaffold (composite or missing keys are not supported)"));
        Names names = new Names(table, pk);
        List<ScaffoldedFile> files = new ArrayList<>();
        files.add(new ScaffoldedFile(names.dir() + "/get.yml", listRoute(names)));
        files.add(new ScaffoldedFile(names.dir() + "/index.html", listPage(table, names)));
        files.add(new ScaffoldedFile(names.dir() + "/fragments/table/get.yml",
                tableRoute(table, names)));
        files.add(new ScaffoldedFile(names.dir() + "/fragments/table/search.sql",
                searchSql(table, names)));
        files.add(new ScaffoldedFile(names.dir() + "/fragments/table/table.html",
                tableFragment(table, names)));
        files.add(new ScaffoldedFile(names.dir() + "/new/get.yml", newRoute(names)));
        files.add(new ScaffoldedFile(names.dir() + "/new/new.html", newPage(table, names)));
        files.add(new ScaffoldedFile(names.dir() + "/create/post.yml",
                createRoute(table, names)));
        files.add(new ScaffoldedFile(names.dir() + "/create/insert.sql",
                insertSql(table, names)));
        files.add(new ScaffoldedFile(names.detailDir() + "/get.yml", detailRoute(table, names)));
        files.add(new ScaffoldedFile(names.detailDir() + "/select.sql", selectSql(table, names)));
        files.add(new ScaffoldedFile(names.detailDir() + "/edit.html", editPage(table, names)));
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
        return List.copyOf(files);
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

    private static String listRoute(Names names) {
        return """
                # Scaffolded list page for the %s table (tesseraql scaffold crud --table %s).
                version: tesseraql/v1
                id: %s.page
                kind: route
                recipe: page

                response:
                  html:
                    template: index.html
                %s""".formatted(names.table(), names.table(), names.entity(), CSP_HEADERS);
    }

    private static String listPage(TableSchema table, Names names) {
        StringBuilder page = new StringBuilder();
        page.append(
                """
                        <!DOCTYPE html>
                        <!-- Scaffolded list page for the %s table: the hc-shell layout with live search
                             over the server-rendered table fragment (docs/hypermedia-ui.md). -->
                        <html xmlns:th="http://www.thymeleaf.org"
                              th:replace="~{tql/shell :: shell('%s', ~{templates/nav.html :: app-nav}, ~{}, ~{:: #page-content})}">
                        <div id="page-content" class="hc-stack">
                        <section class="hc-card">
                          <div class="hc-cluster">
                            <h2>%s</h2>
                            <span class="hc-spacer"></span>
                            <a class="hc-button" data-variant="primary" href="%s/new">New</a>
                          </div>
                        """
                        .formatted(names.table(), names.title(), names.title(), names.url()));
        if (names.searchColumn().isPresent()) {
            page.append(
                    """
                              <input class="hc-input" type="search" name="q" placeholder="Search by %s..."
                                     hx-get="%s/fragments/table" hx-trigger="input changed delay:300ms, search"
                                     hx-target="#%s-table-area" hx-swap="innerHTML">
                            """
                            .formatted(Names.label(Names.columnName(names.searchColumn().get()))
                                    .toLowerCase(Locale.ROOT), names.url(), names.table()));
        }
        page.append(
                """
                          <div id="%s-table-area" hx-get="%s/fragments/table" hx-trigger="load" hx-swap="innerHTML">
                            <p class="hc-field__message">Loading...</p>
                          </div>
                        </section>
                        </div>
                        </html>
                        """
                        .formatted(names.table(), names.url()));
        return page.toString();
    }

    private static String tableRoute(TableSchema table, Names names) {
        StringBuilder route = new StringBuilder();
        route.append("""
                # Scaffolded table fragment for the %s table; the list page swaps it in via htmx.
                version: tesseraql/v1
                id: %s.table
                kind: route
                recipe: query-html
                """.formatted(names.table(), names.entity()));
        names.searchColumn().ifPresent(search -> {
            route.append("\ninput:\n  q:\n    type: string\n    required: false\n");
            if (search.size() > 0) {
                route.append("    maxLength: ").append(search.size()).append('\n');
            }
        });
        route.append("""

                security:
                  auth: browser
                  policy: app.read

                sql:
                  file: search.sql
                  mode: query
                """);
        if (names.searchColumn().isPresent()) {
            route.append("  params:\n    q: query.q\n");
        }
        route.append("""

                response:
                  html:
                    status: 200
                    template: table.html
                    model:
                      rows: sql.rows
                      count: sql.rowCount
                """);
        return route.toString();
    }

    private static String searchSql(TableSchema table, Names names) {
        StringBuilder sql = new StringBuilder();
        sql.append("-- Scaffolded search for the ").append(names.table())
                .append(" table; runnable as-is in a plain SQL tool.\n");
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
        sql.append("order by\n  t.").append(names.pkColumn()).append("\nlimit 50\n;\n");
        return sql.toString();
    }

    private static String tableFragment(TableSchema table, Names names) {
        List<TableSchema.Column> listed = new ArrayList<>();
        listed.add(names.pk());
        listed.addAll(table.dataColumns());
        StringBuilder html = new StringBuilder();
        html.append("""
                <!-- Scaffolded table fragment for the %s table: partial markup swapped into
                     the list page (the fragments URL convention, design ch. 4). -->
                <div id="%s-table" class="hc-card">
                  <table class="hc-table">
                    <thead>
                      <tr>
                """.formatted(names.table(), names.table()));
        for (TableSchema.Column column : listed) {
            html.append("        <th>").append(Names.label(Names.columnName(column)))
                    .append("</th>\n");
        }
        html.append("        <th></th>\n      </tr>\n    </thead>\n    <tbody>\n");
        html.append("      <tr th:each=\"r : ${rows}\">\n");
        for (TableSchema.Column column : listed) {
            html.append("        <td th:text=\"${r.").append(Names.columnName(column))
                    .append("}\"></td>\n");
        }
        html.append("""
                        <td><a class="hc-button" data-size="sm" th:href="|%s/${r.%s}|">Open</a></td>
                      </tr>
                      <tr th:if="${#lists.isEmpty(rows)}">
                        <td colspan="%d">No rows</td>
                      </tr>
                    </tbody>
                  </table>
                </div>
                """.formatted(names.url(), names.pkColumn(), listed.size() + 1));
        return html.toString();
    }

    // ---------------------------------------------------------------- create

    private static String newRoute(Names names) {
        return """
                # Scaffolded create form page for the %s table.
                version: tesseraql/v1
                id: %s.new
                kind: route
                recipe: page

                response:
                  html:
                    template: new.html
                %s""".formatted(names.table(), names.entity(), CSP_HEADERS);
    }

    private static String newPage(TableSchema table, Names names) {
        StringBuilder html = new StringBuilder();
        html.append(
                """
                        <!DOCTYPE html>
                        <!-- Scaffolded create form for the %s table: a plain form post with the
                             post/redirect/get flow (docs/hypermedia-ui.md). -->
                        <html xmlns:th="http://www.thymeleaf.org"
                              th:replace="~{tql/shell :: shell('%s', ~{templates/nav.html :: app-nav}, ~{:: #page-header}, ~{:: #page-content})}">
                        <th:block id="page-header">
                          <span class="hc-spacer"></span>
                          <div class="hc-cluster">
                            <a class="hc-button" data-variant="ghost" data-size="sm" href="%s">&larr; %s</a>
                          </div>
                        </th:block>
                        <div id="page-content" class="hc-stack">
                        <section class="hc-card">
                          <h2>New</h2>
                          <form method="post" action="%s/create">
                            <div class="hc-stack">
                        """
                        .formatted(names.table(), names.title(), names.url(), names.title(),
                                names.url()));
        for (TableSchema.Column column : formColumns(table, names)) {
            html.append(formField(column, null));
        }
        html.append(
                """
                              <div class="hc-cluster">
                                <button type="submit" class="hc-button" data-variant="primary">Create</button>
                              </div>
                            </div>
                          </form>
                        </section>
                        </div>
                        </html>
                        """
                        .stripIndent());
        return html.toString();
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
        route.append(inputBlock(formColumns(table, names)));
        route.append("""

                inputPolicy:
                  unknownFields: reject

                security:
                  auth: browser
                  policy: app.write
                """);
        route.append(constraintsBlock(table));
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

    private static String detailRoute(TableSchema table, Names names) {
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
                    status: 200
                    template: edit.html
                    model:
                      rows: sql.rows
                %s""".formatted(names.table(), names.entity(),
                inputBlock(List.of(names.pk())) + "\n", names.pkField(), names.pkField(),
                CSP_HEADERS);
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

    private static String editPage(TableSchema table, Names names) {
        StringBuilder html = new StringBuilder();
        html.append(
                """
                        <!DOCTYPE html>
                        <!-- Scaffolded edit page for the %s table: plain form posts with the
                             post/redirect/get flow; a stale version answers 409 Conflict (Phase 18). -->
                        <html xmlns:th="http://www.thymeleaf.org"
                              th:replace="~{tql/shell :: shell('%s', ~{templates/nav.html :: app-nav}, ~{:: #page-header}, ~{:: #page-content})}">
                        <th:block id="page-header">
                          <span class="hc-spacer"></span>
                          <div class="hc-cluster">
                            <a class="hc-button" data-variant="ghost" data-size="sm" href="%s">&larr; %s</a>
                          </div>
                        </th:block>
                        <div id="page-content" class="hc-stack">
                        <section class="hc-card" th:if="${#lists.isEmpty(rows)}">
                          <div class="hc-empty"><p class="hc-empty__title">Not found.</p></div>
                        </section>
                        <th:block th:each="r : ${rows}">
                        <section class="hc-card">
                          <h2>Edit</h2>
                          <form method="post" th:action="|%s/${r.%s}/update|">
                            <div class="hc-stack">
                        """
                        .formatted(names.table(), names.title(), names.url(), names.title(),
                                names.url(), names.pkColumn()));
        for (TableSchema.Column column : formColumns(table, names)) {
            html.append(formField(column, "r." + Names.columnName(column)));
        }
        if (table.versionColumn().isPresent()) {
            html.append("      <input type=\"hidden\" name=\"version\""
                    + " th:value=\"${r.version}\">\n");
        }
        html.append("""
                      <div class="hc-cluster">
                        <button type="submit" class="hc-button" data-variant="primary">Save</button>
                      </div>
                    </div>
                  </form>
                </section>
                <section class="hc-card">
                  <h2>Delete</h2>
                """.stripIndent());
        html.append("  <form method=\"post\" th:action=\"|").append(names.url())
                .append("/${r.").append(names.pkColumn()).append("}/delete|\">\n");
        if (table.versionColumn().isPresent()) {
            html.append("    <input type=\"hidden\" name=\"version\""
                    + " th:value=\"${r.version}\">\n");
        }
        html.append(
                """
                            <button type="submit" class="hc-button" data-variant="error"
                                    data-hc-confirm="Delete this record?" data-hc-confirm-title="Confirm delete"
                                    data-hc-confirm-ok="Delete" data-hc-confirm-variant="error">Delete</button>
                          </form>
                        </section>
                        </th:block>
                        </div>
                        </html>
                        """
                        .stripIndent());
        return html.toString();
    }

    // ---------------------------------------------------------------- update / delete

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
        route.append(inputBlock(inputs));
        route.append("""

                inputPolicy:
                  unknownFields: reject

                security:
                  auth: browser
                  policy: app.write
                """);
        route.append(constraintsBlock(table));
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
        route.append(inputBlock(inputs));
        route.append("""

                inputPolicy:
                  unknownFields: reject

                security:
                  auth: browser
                  policy: app.write

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
        suite.append("""
                  - name: the %s search runs without a filter
                    sql:
                      file: %s/fragments/table/search.sql
                    params: {}
                """.formatted(names.table(), names.dir()));
        names.searchColumn().ifPresent(search -> suite.append("""

                  - name: the %s search filters by %s
                    sql:
                      file: %s/fragments/table/search.sql
                    params:
                      q: no-such-row
                    expect:
                      rowCount: 0
                """.formatted(names.table(), Names.columnName(search), names.dir())));
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

    private static String inputBlock(List<TableSchema.Column> columns) {
        StringBuilder input = new StringBuilder("input:\n");
        for (TableSchema.Column column : columns) {
            input.append("  ").append(Names.field(column)).append(":\n");
            input.append("    type: ").append(column.inputType()).append('\n');
            if (!column.nullable()) {
                input.append("    required: true\n");
            }
            if (column.isCharacter() && column.size() > 0) {
                input.append("    maxLength: ").append(column.size()).append('\n');
            }
            switch (column.inputType()) {
                case "date" -> input.append("    format: yyyy-MM-dd\n");
                case "datetime" -> input.append("    format: \"yyyy-MM-dd'T'HH:mm\"\n");
                default -> {
                }
            }
        }
        return input.toString();
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

    /** Maps single-column unique indexes to field-level constraint errors (Phase 18). */
    private static String constraintsBlock(TableSchema table) {
        if (table.uniqueIndexes().isEmpty()) {
            return "";
        }
        StringBuilder errors = new StringBuilder("\nerrors:\n  constraints:\n");
        table.uniqueIndexes().forEach((index, column) -> {
            errors.append("    ").append(index.toLowerCase(Locale.ROOT)).append(":\n");
            errors.append("      field: ")
                    .append(Names.camel(column.toLowerCase(Locale.ROOT))).append('\n');
        });
        return errors.toString();
    }

    /**
     * One hc-field stanza (the kit's label + control + message composition). {@code valueExpr}
     * binds the edit form's current values; {@code null} renders the empty create form. Dates ride
     * the blessed hc-datepicker skin over the native input (hc 0.1.1, adoption issue #219).
     */
    private static String formField(TableSchema.Column column, String valueExpr) {
        String field = Names.field(column);
        String id = Names.htmlId(column);
        String label = Names.label(Names.columnName(column));
        StringBuilder html = new StringBuilder();
        html.append("      <div class=\"hc-field\">\n");
        html.append("        <label class=\"hc-field__label\" for=\"").append(id).append("\">")
                .append(label).append("</label>\n");
        if ("boolean".equals(column.inputType())) {
            html.append("        <input type=\"hidden\" name=\"").append(field)
                    .append("\" value=\"false\">\n");
            html.append("        <input class=\"hc-checkbox\" id=\"").append(id)
                    .append("\" type=\"checkbox\" name=\"").append(field)
                    .append("\" value=\"true\"");
            if (valueExpr != null) {
                html.append(" th:checked=\"${").append(valueExpr).append("}\"");
            }
            html.append(">\n");
        } else {
            html.append("        <input class=\"").append(cssClass(column)).append("\" id=\"")
                    .append(id).append("\" type=\"").append(htmlType(column))
                    .append("\" name=\"").append(field).append('"');
            if ("number".equals(column.inputType())) {
                html.append(" step=\"any\"");
            }
            if (column.isCharacter() && column.size() > 0) {
                html.append(" maxlength=\"").append(column.size()).append('"');
            }
            if (!column.nullable()) {
                html.append(" required");
            }
            if (valueExpr != null) {
                html.append(" th:value=\"${").append(valueExpr).append("}\"");
            }
            html.append(">\n");
        }
        html.append("      </div>\n");
        return html.toString();
    }

    private static String cssClass(TableSchema.Column column) {
        return switch (column.inputType()) {
            case "date", "datetime" -> "hc-datepicker";
            default -> "hc-input";
        };
    }

    private static String htmlType(TableSchema.Column column) {
        return switch (column.inputType()) {
            case "integer", "number" -> "number";
            case "date" -> "date";
            case "datetime" -> "datetime-local";
            default -> "text";
        };
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
