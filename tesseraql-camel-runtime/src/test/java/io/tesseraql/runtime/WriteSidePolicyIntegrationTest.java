package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Write-side field policy end to end (docs/view-composition.md wave 4): an {@code input:} field
 * with {@code policy:} binds only for principals the policy permits — a failing principal's
 * value follows the route's readOnly behavior (reject by default) — and the derived form omits
 * the field for that principal, both from the same declaration.
 */
@Testcontainers
class WriteSidePolicyIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String JWT_SECRET = "dev-only-secret-change-me-in-production";

    static TesseraqlRuntime runtime;
    static Path appHome;

    @BeforeAll
    static void start() throws Exception {
        seedDatabase();
        appHome = prepareAppHome();
        runtime = TesseraqlRuntime.start(appHome, freePort());
    }

    @AfterAll
    static void stop() throws IOException {
        if (runtime != null) {
            runtime.close();
        }
        if (appHome != null) {
            deleteRecursively(appHome);
        }
    }

    @Test
    void aPermittedPrincipalWritesThePolicyGatedField() throws Exception {
        HttpResponse<String> response = post(token("hr-user", List.of("EMP", "HR")),
                "{\"note\":\"raise\",\"salary\":150}");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(salaryOf(1)).isEqualTo(150);
    }

    @Test
    void aDeniedPrincipalsValueIsRejected() throws Exception {
        HttpResponse<String> response = post(token("emp-user", List.of("EMP")),
                "{\"note\":\"sneaky\",\"salary\":999}");
        assertThat(response.statusCode()).isGreaterThanOrEqualTo(400);
        assertThat(salaryOf(1)).isNotEqualTo(999);
    }

    @Test
    void theDerivedFormOmitsTheFieldForADeniedPrincipalOnly() throws Exception {
        String hrForm = getForm(token("hr-user", List.of("EMP", "HR")));
        assertThat(hrForm).contains("name=\"salary\"").contains("name=\"note\"");

        String empForm = getForm(token("emp-user", List.of("EMP")));
        assertThat(empForm).doesNotContain("name=\"salary\"").contains("name=\"note\"");
    }

    private static HttpResponse<String> post(String bearer, String body) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + runtime.port() + "/api/employees/1/update"))
                        .header("Authorization", "Bearer " + bearer)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String getForm(String bearer) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + runtime.port() + "/employees/1/edit"))
                        .header("Authorization", "Bearer " + bearer)
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return response.body();
    }

    private static int salaryOf(int id) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "select salary from employees where id = " + id)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static String token(String sub, List<String> roles) throws Exception {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = enc.encodeToString(MAPPER.writeValueAsBytes(
                Map.of("sub", sub, "roles", roles)));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(JWT_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = enc.encodeToString(
                mac.doFinal((header + "." + payload).getBytes(StandardCharsets.US_ASCII)));
        return header + "." + payload + "." + signature;
    }

    private static void seedDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("create table employees (id serial primary key, "
                    + "name varchar(80) not null, note varchar(200), salary integer not null)");
            statement.execute("insert into employees (name, note, salary) values "
                    + "('alice', '', 100)");
        }
    }

    private static Path prepareAppHome() throws IOException {
        Path home = Files.createTempDirectory("tesseraql-write-policy-it");
        Files.createDirectories(home.resolve("config"));
        Files.writeString(home.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                  security:
                    jwt:
                      secret: %s
                      rolesClaim: roles
                    policies:
                      emp.read:
                        anyOf:
                          - role: EMP
                          - role: HR
                      hr.write:
                        anyOf:
                          - role: HR
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword(), JWT_SECRET));

        Path api = home.resolve("web/api/employees/{id}/update");
        Files.createDirectories(api);
        Files.writeString(api.resolve("post.yml"), """
                version: tesseraql/v1
                id: employees.update
                kind: route
                recipe: command-json
                security:
                  auth: bearer
                  policy: emp.read
                input:
                  id: { type: integer, writable: false }
                  note: { type: string, maxLength: 200 }
                  salary: { type: integer, min: 0, policy: hr.write }
                sql:
                  file: update.sql
                  mode: update
                  params:
                    id: params.id
                    note: params.note
                    salary: params.salary
                response:
                  json:
                    status: 200
                    body:
                      ok: "true"
                """);
        Files.writeString(api.resolve("update.sql"), """
                update employees
                   set note = /* note */ 'x',
                       salary = coalesce(/* salary */ 0, salary)
                 where id = /* id */ 1
                """);

        Path form = home.resolve("web/employees/{id}/edit");
        Files.createDirectories(form);
        Files.writeString(form.resolve("get.yml"), """
                version: tesseraql/v1
                id: employees.edit
                kind: route
                recipe: query-html
                security:
                  auth: bearer
                  policy: emp.read
                input:
                  id: { type: integer, writable: false }
                sql:
                  file: select.sql
                  mode: query
                  params:
                    id: params.id
                response:
                  html:
                    view: employees.edit.form
                """);
        Files.writeString(form.resolve("select.sql"),
                "select id, name, note, salary from employees where id = /* id */ 1\n");
        Files.writeString(form.resolve("edit.view.yml"), """
                version: tesseraql/v1
                id: employees.edit.form
                kind: view
                recipe: form
                title: Edit employee
                action: /api/employees/{id}/update
                """);
        return home;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> files = Files.walk(root)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException ex) {
                    throw new java.io.UncheckedIOException(ex);
                }
            });
        }
    }
}
