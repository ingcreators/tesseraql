package io.tesseraql.yaml.scaffold;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Generates the {@code tesseraql new <app>} skeleton (roadmap Phase 23): a runnable app home with
 * config, a Flyway migration following the Phase 18 write conventions (identity key, version
 * column, audit columns, a unique index), a home page, a starter JSON search route, and a smoke
 * suite exercising both branches of its 2-way SQL.
 *
 * <p>The skeleton's {@code items} table is deliberately the shape
 * {@code tesseraql scaffold crud --table items} consumes, so the two commands compose into a
 * working CRUD slice out of the box. Skeleton files are user-owned from the first write — unlike
 * CRUD scaffolds they carry no regeneration checksum.
 */
public final class AppScaffolder {

    private static final TqlErrorCode INVALID_TARGET = new TqlErrorCode(TqlDomain.APP, 5203);
    private static final Pattern APP_NAME = Pattern.compile("[a-z][a-z0-9-]{0,63}");

    /** Generates the skeleton file set for {@code appName} (paths relative to the app home). */
    public List<ScaffoldedFile> scaffold(String appName) {
        if (appName == null || !APP_NAME.matcher(appName).matches()) {
            throw new TqlException(INVALID_TARGET, "App name must match [a-z][a-z0-9-]{0,63}: '"
                    + appName + "'");
        }
        String dbName = appName.replace('-', '_');
        return List.of(
                new ScaffoldedFile("config/application.yml",
                        APPLICATION_YML.replace("__APP_DB__", dbName)),
                new ScaffoldedFile("config/tesseraql.yml",
                        TESSERAQL_YML.replace("__APP_NAME__", appName)),
                new ScaffoldedFile("db/migration/V1__create_items.sql", MIGRATION_SQL),
                new ScaffoldedFile("templates/nav.html", NAV_HTML),
                new ScaffoldedFile("web/get.yml", HOME_ROUTE_YML),
                new ScaffoldedFile("web/index.html",
                        HOME_PAGE_HTML.replace("__APP_NAME__", appName)),
                new ScaffoldedFile("web/api/items/get.yml", SEARCH_ROUTE_YML),
                new ScaffoldedFile("web/api/items/search.sql", SEARCH_SQL),
                new ScaffoldedFile("tests/smoke-test.yml", SMOKE_TEST_YML),
                new ScaffoldedFile(".gitignore", GITIGNORE));
    }

    /**
     * Writes the skeleton into {@code targetDir}, which must not exist yet or be an empty
     * directory — {@code new} never writes into an app that already has content.
     */
    public void writeNew(Path targetDir, List<ScaffoldedFile> files) {
        Path target = targetDir.toAbsolutePath().normalize();
        if (Files.exists(target) && (!Files.isDirectory(target) || !isEmpty(target))) {
            throw new TqlException(INVALID_TARGET,
                    "Target exists and is not an empty directory: " + target);
        }
        try {
            for (ScaffoldedFile file : files) {
                Path destination = target.resolve(file.path()).normalize();
                Files.createDirectories(destination.getParent());
                Files.writeString(destination, file.content());
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static boolean isEmpty(Path directory) {
        try (var entries = Files.list(directory)) {
            return entries.findAny().isEmpty();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static final String APPLICATION_YML = """
            server:
              port: 8080

            db:
              main:
                url: jdbc:postgresql://localhost:5432/__APP_DB__
                username: ${DB_USER:__APP_DB__}
                password: ${DB_PASSWORD:__APP_DB__}
                maximumPoolSize: 10
            """;

    private static final String TESSERAQL_YML = """
            tesseraql:
              app:
                name: __APP_NAME__
                home: ${TESSERAQL_APP_HOME:.}
                work: ${TESSERAQL_WORK_HOME:${TESSERAQL_APP_HOME}/work}

              runtime:
                engine: camel
                profile: ${TESSERAQL_PROFILE:local}

              java:
                baseline: 21
                compatibility:
                  - 25

              datasources:
                main:
                  type: hikari
                  jdbcUrl: ${db.main.url}
                  username: ${db.main.username}
                  password: ${db.main.password}

              identity:
                defaultRealm: local
                realms:
                  local:
                    type: managed
                    datasource: main

              camel:
                components:
                  allowed:
                    - direct
                    - platform-http
                    - timer
                    - quartz
                    - file
                    - log
                    - tesseraql-sql
                    - tesseraql-auth
                    - tesseraql-html
                  denied:
                    - exec
                    - script
                    - groovy
                    - class

              security:
                defaults:
                  api:
                    auth: bearer
                  htmx:
                    auth: browser
                    csrf: auto

                jwt:
                  secret: ${JWT_SECRET:dev-only-secret-change-me-in-production}
                  rolesClaim: roles
                  permissionsClaim: permissions
                  tenantClaim: tenant_id

                # The starter policies every scaffolded route references; rename or split them
                # per domain as the app grows (e.g. items.read / items.write).
                policies:
                  app.read:
                    anyOf:
                      - role: APP_READ
                      - permission: app:read
                  app.write:
                    anyOf:
                      - role: APP_WRITE
                      - permission: app:write
            """;

    private static final String MIGRATION_SQL = """
            -- Starter table (tesseraql new): the Phase 18 write conventions — identity key,
            -- optimistic-locking version column, audit columns, and a named unique index the
            -- scaffolder maps to a field-level constraint error.
            create table items (
              id bigint generated always as identity primary key,
              name varchar(200) not null,
              quantity integer not null,
              unit_price numeric(12, 2),
              due_date date,
              active boolean not null,
              note varchar(1000),
              version bigint not null,
              created_by varchar(200) not null,
              created_at timestamp not null,
              updated_by varchar(200) not null,
              updated_at timestamp not null
            );

            create unique index uq_items_name on items (name);

            insert into items (name, quantity, unit_price, due_date, active, note, version,
                               created_by, created_at, updated_by, updated_at)
            values ('First item', 1, 9.99, date '2026-01-01', true, 'Seeded by tesseraql new', 1,
                    'system', current_timestamp, 'system', current_timestamp);
            """;

    private static final String NAV_HTML = """
            <!-- App-wide sidebar navigation (design ch. 4), referenced from pages as
                 ~{templates/nav.html :: app-nav}. Add an hc-item entry per page. -->
            <th:block xmlns:th="http://www.thymeleaf.org" th:fragment="app-nav">
              <a class="hc-item" href="/">Home</a>
            </th:block>
            """;

    private static final String HOME_ROUTE_YML = """
            version: tesseraql/v1
            id: app.home
            kind: route
            recipe: page

            response:
              html:
                template: index.html
                headers:
                  Content-Security-Policy: "default-src 'self'; style-src 'self' 'unsafe-inline'; frame-ancestors 'none'"
                  X-Content-Type-Options: nosniff
                  X-Frame-Options: DENY
                  Referrer-Policy: no-referrer
            """;

    private static final String HOME_PAGE_HTML = """
            <!DOCTYPE html>
            <!-- Starter home page (tesseraql new): the framework hc-shell layout with the shared
                 app navigation. -->
            <html xmlns:th="http://www.thymeleaf.org"
                  th:replace="~{tql/shell :: shell('__APP_NAME__', ~{templates/nav.html :: app-nav}, ~{}, ~{:: #page-content})}">
            <div id="page-content" class="hc-stack">
            <section class="hc-card">
              <h2>Welcome to __APP_NAME__</h2>
              <p class="hc-field__message">This app was generated by <code>tesseraql new</code>.</p>
              <ul>
                <li>Serve it: <code>tesseraql serve --app .</code></li>
                <li>Scaffold a UI for the starter table: <code>tesseraql scaffold crud --app . --table items</code></li>
                <li>Run the declarative suites: <code>mvn tesseraql:test -Dtesseraql.appHome=.</code></li>
              </ul>
            </section>
            </div>
            </html>
            """;

    private static final String SEARCH_ROUTE_YML = """
            version: tesseraql/v1
            id: items.search
            kind: route
            recipe: query-json

            input:
              q:
                type: string
                required: false
                maxLength: 200

              limit:
                type: integer
                default: 50
                min: 1
                max: 200

              offset:
                type: integer
                default: 0
                min: 0

            security:
              auth: bearer
              policy: app.read

            sql:
              file: search.sql
              mode: query
              params:
                q: query.q
                limit: query.limit
                offset: query.offset

            response:
              json:
                status: 200
                body:
                  data: sql.rows
                  meta:
                    count: sql.rowCount
                    limit: params.limit
                    offset: params.offset
            """;

    private static final String SEARCH_SQL = """
            -- Starter search (tesseraql new); runnable as-is in a plain SQL tool, and the smoke
            -- suite exercises both branches.
            select
              i.id,
              i.name,
              i.quantity,
              i.due_date
            from
              items i
            where
              1 = 1
            /*%if q != null && q != "" */
              and i.name like /* q */ 'First item'
            /*%end*/
            order by
              i.id
            limit /* limit */ 50
            offset /* offset */ 0
            ;
            """;

    private static final String SMOKE_TEST_YML = """
            # Starter smoke suite (tesseraql new): proves the migration, the seeded row, and both
            # branches of the search SQL (design ch. 13).
            tests:
              - name: the items search returns the seeded row
                sql:
                  file: web/api/items/search.sql
                params:
                  q: ""
                  limit: 50
                  offset: 0
                expect:
                  rowCount: 1
                  rows:
                    - name: First item

              - name: the items search filters by exact name
                sql:
                  file: web/api/items/search.sql
                params:
                  q: First item
                  limit: 50
                  offset: 0
                expect:
                  rowCount: 1
            """;

    private static final String GITIGNORE = """
            # Runtime scratch (drafts, spools, mounted apps); never committed (design ch. 4).
            work/
            """;
}
