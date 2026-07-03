package io.tesseraql.yaml.scaffold;

import io.tesseraql.core.TesseraqlVersion;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
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
                new ScaffoldedFile("config/menu.yml", MENU_YML),
                new ScaffoldedFile("web/get.yml", HOME_ROUTE_YML),
                new ScaffoldedFile("web/index.html",
                        HOME_PAGE_HTML.replace("__APP_NAME__", appName)),
                new ScaffoldedFile("web/api/items/get.yml", SEARCH_ROUTE_YML),
                new ScaffoldedFile("web/api/items/search.sql", SEARCH_SQL),
                new ScaffoldedFile("tests/smoke-test.yml", SMOKE_TEST_YML),
                new ScaffoldedFile(".gitignore", GITIGNORE),
                // Maven/CI surface: a thin wrapper POM plus the Maven Wrapper, so the Maven path
                // needs only a JDK. The interactive CLI loop needs none of this (app-layout.md).
                new ScaffoldedFile("pom.xml",
                        WRAPPER_POM_XML.replace("__APP_NAME__", appName)
                                .replace("__APP_DB__", dbName)
                                .replace("__TQL_VERSION__", TesseraqlVersion.current())),
                new ScaffoldedFile("mvnw", resource("mvnw")),
                new ScaffoldedFile("mvnw.cmd", resource("mvnw.cmd")),
                new ScaffoldedFile(".mvn/wrapper/maven-wrapper.properties",
                        resource("maven-wrapper.properties")),
                // A local PostgreSQL for development (Docker optional, native PostgreSQL works too).
                new ScaffoldedFile("compose.yaml", COMPOSE_YAML.replace("__APP_DB__", dbName)),
                new ScaffoldedFile("README.md",
                        README_MD.replace("__APP_NAME__", appName).replace("__APP_DB__", dbName)));
    }

    /** Reads a bundled scaffold resource (the Maven Wrapper scripts) verbatim. */
    private static String resource(String name) {
        try (var in = AppScaffolder.class.getResourceAsStream("/scaffold/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Missing scaffold resource: /scaffold/" + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
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
                // Shell scripts (the mvnw wrapper) must be runnable; the .cmd/.properties are not.
                if (file.content().startsWith("#!")) {
                    makeExecutable(destination);
                }
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** Adds the executable bit on POSIX filesystems; a no-op on non-POSIX ones (e.g. Windows). */
    private static void makeExecutable(Path file) {
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
            perms.add(PosixFilePermission.OWNER_EXECUTE);
            perms.add(PosixFilePermission.GROUP_EXECUTE);
            perms.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(file, perms);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX filesystem: the wrapper still runs via `sh mvnw`.
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

              studio:
                # Studio is a privileged developer surface at /_tesseraql/studio. Enabled for local
                # development; in production set TESSERAQL_STUDIO_ENABLED=false (or, to keep it
                # visible but inert, TESSERAQL_STUDIO_READONLY=true).
                enabled: ${TESSERAQL_STUDIO_ENABLED:true}
                readOnly: ${TESSERAQL_STUDIO_READONLY:false}

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

    private static final String MENU_YML = """
            # The app's sidebar menu (config/menu.yml): rendered server-side into the shell nav
            # slot and editable in Studio. Declarative view pages navigate through it.
            menu:
              - label: Home
                href: /
              - label: Items
                href: /items
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

            # Maven build output (the Maven/CI path).
            target/
            """;

    private static final String WRAPPER_POM_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!-- Thin wrapper POM (tesseraql new): imports the TesseraQL BOM and binds the Maven
                 plugin so the CI / Maven surface needs only a JDK (via ./mvnw). The interactive CLI
                 loop needs none of this; see README.md. -->
            <project xmlns="http://maven.apache.org/POM/4.0.0"
                     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                     xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
              <modelVersion>4.0.0</modelVersion>

              <groupId>com.example</groupId>
              <artifactId>__APP_NAME__</artifactId>
              <version>0.1.0-SNAPSHOT</version>
              <packaging>pom</packaging>

              <properties>
                <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                <!-- The TesseraQL version you build against (matches the CLI you installed). -->
                <tesseraql.version>__TQL_VERSION__</tesseraql.version>
              </properties>

              <dependencyManagement>
                <dependencies>
                  <dependency>
                    <groupId>io.tesseraql</groupId>
                    <artifactId>tesseraql-bom</artifactId>
                    <version>${tesseraql.version}</version>
                    <type>pom</type>
                    <scope>import</scope>
                  </dependency>
                </dependencies>
              </dependencyManagement>

              <build>
                <plugins>
                  <plugin>
                    <groupId>io.tesseraql</groupId>
                    <artifactId>tesseraql-maven-plugin</artifactId>
                    <version>${tesseraql.version}</version>
                    <!-- The app home is this directory; the plugin operates on it in place. -->
                    <configuration>
                      <appHome>${project.basedir}</appHome>
                    </configuration>
                    <executions>
                      <!-- `./mvnw verify` lints and runs the governance gate (no database needed).
                           Run the database goals explicitly, e.g.
                           ./mvnw tesseraql:migrate tesseraql:test \\
                               -Dtesseraql.jdbcUrl=jdbc:postgresql://localhost:5432/__APP_DB__ -->
                      <execution>
                        <id>tesseraql-verify</id>
                        <phase>verify</phase>
                        <goals>
                          <goal>lint</goal>
                          <goal>governance</goal>
                        </goals>
                      </execution>
                    </executions>
                  </plugin>
                </plugins>
              </build>
            </project>
            """;

    private static final String COMPOSE_YAML = """
            # Local PostgreSQL for development (tesseraql new). Docker is optional — a natively
            # installed PostgreSQL works too; point DB_USER / DB_PASSWORD (or config/application.yml)
            # at it. Start this one with: docker compose up -d
            services:
              db:
                image: postgres:16-alpine
                environment:
                  POSTGRES_DB: __APP_DB__
                  POSTGRES_USER: ${DB_USER:-__APP_DB__}
                  POSTGRES_PASSWORD: ${DB_PASSWORD:-__APP_DB__}
                ports:
                  - "5432:5432"
                volumes:
                  - tesseraql-pgdata:/var/lib/postgresql/data

            volumes:
              tesseraql-pgdata:
            """;

    private static final String README_MD = """
            # __APP_NAME__

            A [TesseraQL](https://github.com/ingcreators/tesseraql) application — SQL-first
            hypermedia and integration. You work in this directory (2-way SQL, YAML routes,
            templates); the framework is installed tooling and resolved Maven artifacts, so you do
            not clone the framework monorepo.

            ## Prerequisites

            - A reachable PostgreSQL (Docker optional). Bring up a local one with
              `docker compose up -d`, or point `DB_USER` / `DB_PASSWORD` (or
              `config/application.yml`) at an existing server.
            - **CLI path:** the `tesseraql` CLI. **Maven path:** a JDK (the bundled `./mvnw` fetches
              Maven itself).

            ## CLI path (interactive dev loop)

            ```sh
            tesseraql serve --app .          # auto-applies db/migration; Studio at /_tesseraql/studio
            tesseraql scaffold crud --app . --table items
            tesseraql lint --app .
            tesseraql test --app .
            tesseraql package --app .        # build a .tqlapp under work/
            ```

            ## Maven path (CI / lifecycle)

            ```sh
            ./mvnw verify                    # lint + governance gate (no database)
            ./mvnw tesseraql:migrate tesseraql:test \\
                -Dtesseraql.jdbcUrl=jdbc:postgresql://localhost:5432/__APP_DB__
            ```

            ## Layout

            See the [application layout](https://github.com/ingcreators/tesseraql/blob/main/docs/app-layout.md):
            `config/`, `web/` (the directory tree mirrors the URL space), `db/migration/`,
            `templates/`, `tests/`.
            """;
}
