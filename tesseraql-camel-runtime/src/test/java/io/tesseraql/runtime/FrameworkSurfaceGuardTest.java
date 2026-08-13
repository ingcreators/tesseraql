package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.apache.camel.model.ModelCamelContext;
import org.apache.camel.model.ProcessorDefinition;
import org.apache.camel.model.RouteDefinition;
import org.apache.camel.model.ToDefinition;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Every framework HTTP route is authenticated, or says why it is not
 * (docs/framework-surface-parity.md, slice 5).
 *
 * <p>The audit behind that document found two framework routes shipped without the gate their
 * siblings had — the Studio reload endpoint and its compile-failure stub. Neither was caught in
 * review because an absence looks like nothing: nobody reviews a step that isn't there. This
 * inverts it. The context is started with every framework surface mounted, each route is read off
 * the model, and one that neither authenticates nor appears in {@link FrameworkSurfaces} fails the
 * build.
 *
 * <p>The fixture enables Studio, metrics, MCP and SCIM deliberately. A guard that runs against a
 * default app would check a handful of routes, pass, and read as if it had checked the surface.
 */
@Testcontainers
class FrameworkSurfaceGuardTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    /** Path prefixes that make a mounted route the framework's rather than the app's. */
    private static final List<String> FRAMEWORK_PATHS = List.of("/_tesseraql", "/scim/v2",
            "/assets");

    /** Families the fixture must actually mount, or the guard is checking less than it claims. */
    private static final List<String> FAMILIES = List.of("ops.", "studio.", "scim.", "system.",
            "tql.", "mcp.");

    static TesseraqlRuntime runtime;
    static Path appHome;

    @BeforeAll
    static void start() throws Exception {
        appHome = Files.createTempDirectory("surface-guard");
        Path source = Path.of("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        try (Stream<Path> files = Files.walk(source)) {
            files.forEach(path -> copy(source, appHome, path));
        }
        Files.createDirectories(appHome.resolve("assets"));
        Files.writeString(appHome.resolve("assets/app.css"), "body{}\n");
        Files.createDirectories(appHome.resolve("mcp"));
        Files.writeString(appHome.resolve("mcp/list.yml"), """
                version: tesseraql/v1
                id: guard.tool
                kind: tool
                recipe: query-json
                description: a tool, so the MCP endpoint mounts
                security:
                  policy: scim.manage
                sources:
                  main:
                    sql:
                      file: list.sql
                response:
                  json:
                    body:
                      data: main.rows
                """);
        Files.writeString(appHome.resolve("mcp/list.sql"), "select 1 as one\n");
        Path scim = appHome.resolve("scim");
        Files.createDirectories(scim);
        for (String name : List.of("create-user", "find-user", "list-users", "replace-user",
                "delete-user", "find-user-by-name", "count-users", "create-group", "find-group",
                "list-groups", "replace-group", "delete-group", "list-members", "add-member",
                "remove-member", "count-groups")) {
            // The guard reads mounted routes; the contracts only have to load.
            Files.writeString(scim.resolve(name + ".sql"), "select 1 as id\n");
        }
        Files.writeString(appHome.resolve("config/application.yml"), """
                server:
                  port: 0

                db:
                  main:
                    url: %s
                    username: %s
                    password: %s

                tesseraql:
                  studio:
                    enabled: true
                  metrics:
                    enabled: true
                  mcp:
                    enabled: true
                  scim:
                    enabled: true
                    users:
                      create: scim/create-user.sql
                      findById: scim/find-user.sql
                      list: scim/list-users.sql
                      replace: scim/replace-user.sql
                      delete: scim/delete-user.sql
                      findByUserName: scim/find-user-by-name.sql
                      count: scim/count-users.sql
                    groups:
                      enabled: true
                      create: scim/create-group.sql
                      findById: scim/find-group.sql
                      list: scim/list-groups.sql
                      replace: scim/replace-group.sql
                      delete: scim/delete-group.sql
                      listMembers: scim/list-members.sql
                      addMember: scim/add-member.sql
                      removeMember: scim/remove-member.sql
                      count: scim/count-groups.sql
                  security:
                    policies:
                      scim.manage:
                        anyOf:
                          - role: SCIM
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
        runtime = TesseraqlRuntime.start(appHome, freePort());
    }

    @AfterAll
    static void stop() throws Exception {
        if (runtime != null) {
            runtime.close();
        }
        if (appHome != null) {
            try (Stream<Path> files = Files.walk(appHome)) {
                files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    @Test
    void everyFrameworkHttpRouteAuthenticatesOrSaysWhyNot() {
        List<String> unexplained = new ArrayList<>();
        for (RouteDefinition route : frameworkRoutes()) {
            if (authSteps(route).stream().anyMatch(step -> step.startsWith("authenticate"))) {
                continue;
            }
            if (!FrameworkSurfaces.exempt(route.getRouteId())) {
                unexplained.add(route.getRouteId());
            }
        }

        assertThat(unexplained)
                .as("framework HTTP routes with no authenticate step and no entry in "
                        + "FrameworkSurfaces — gate it, or record why it is open")
                .isEmpty();
    }

    /**
     * The registry describes routes that exist.
     *
     * <p>An entry for a route nobody mounts any more is how a registry rots into scenery: it reads
     * as coverage while covering nothing, and the next reader trusts it.
     */
    @Test
    void everyRegisteredExemptionMatchesAMountedRoute() {
        Set<String> mounted = new LinkedHashSet<>();
        frameworkRoutes().forEach(route -> mounted.add(route.getRouteId()));

        List<String> stale = new ArrayList<>();
        FrameworkSurfaces.PUBLIC_BY_DESIGN.keySet().forEach(id -> {
            if (!mounted.contains(id)) {
                stale.add(id);
            }
        });
        FrameworkSurfaces.PROCESSOR_ENFORCED.keySet().forEach(id -> {
            if (!mounted.contains(id)) {
                stale.add(id);
            }
        });

        assertThat(stale).as("FrameworkSurfaces entries for routes this fixture does not mount")
                .isEmpty();
    }

    /**
     * The honesty probe, the same one the config-key registry carries.
     *
     * <p>Without it the guard passes loudest when it checks least: a fixture that quietly failed to
     * enable Studio or SCIM would find no unauthenticated routes among the handful left and report
     * a clean surface.
     */
    @Test
    void theFixtureActuallyMountsTheWholeSurface() {
        List<RouteDefinition> routes = frameworkRoutes();
        Set<String> ids = new LinkedHashSet<>();
        routes.forEach(route -> ids.add(route.getRouteId()));

        for (String family : FAMILIES) {
            assertThat(ids).as("family '%s' must be mounted for this guard to mean anything",
                    family).anyMatch(id -> id.startsWith(family));
        }
        // Measured at 173 when this landed. The floor is deliberately below that: it catches a
        // fixture that stops mounting a whole surface, without failing on every route added.
        assertThat(routes.size()).as("framework HTTP routes mounted").isGreaterThan(150);
    }

    /** Framework-mounted HTTP routes: the app's own routes are the compiler's contract, not this. */
    private static List<RouteDefinition> frameworkRoutes() {
        ModelCamelContext model = runtime.camelContext().getCamelContextExtension()
                .getContextPlugin(ModelCamelContext.class);
        List<RouteDefinition> framework = new ArrayList<>();
        for (RouteDefinition route : model.getRouteDefinitions()) {
            if (route.getInput() == null) {
                continue;
            }
            String uri = route.getInput().getEndpointUri();
            if (!uri.startsWith("rest://") && !uri.startsWith("platform-http:")) {
                continue;
            }
            String path = java.net.URLDecoder.decode(uri, java.nio.charset.StandardCharsets.UTF_8);
            if (FRAMEWORK_PATHS.stream().anyMatch(prefix -> path.contains(":" + prefix)
                    || path.contains("/" + prefix.substring(1)))) {
                framework.add(route);
            }
        }
        return framework;
    }

    private static List<String> authSteps(RouteDefinition route) {
        List<String> found = new ArrayList<>();
        collectAuth(route.getOutputs(), found);
        return found;
    }

    private static void collectAuth(List<ProcessorDefinition<?>> outputs, List<String> found) {
        for (ProcessorDefinition<?> output : outputs) {
            if (output instanceof ToDefinition to && to.getEndpointUri() != null
                    && to.getEndpointUri().startsWith("tesseraql-auth:")) {
                found.add(to.getEndpointUri().substring("tesseraql-auth:".length()));
            }
            collectAuth(output.getOutputs(), found);
        }
    }

    private static void copy(Path source, Path target, Path path) {
        try {
            Path destination = target.resolve(source.relativize(path).toString());
            if (Files.isDirectory(path)) {
                Files.createDirectories(destination);
            } else {
                Files.createDirectories(destination.getParent());
                Files.copy(path, destination);
            }
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }

    private static int freePort() throws Exception {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
