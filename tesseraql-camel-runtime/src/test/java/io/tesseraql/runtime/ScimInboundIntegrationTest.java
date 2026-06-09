package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test for SCIM inbound provisioning (design ch. 10.15): create, fetch, and list users
 * through the SCIM contract SQL, with a bearer principal authorized by the scim.manage policy.
 */
@Testcontainers
class ScimInboundIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
    void createsFetchesAndListsUsersViaScim() throws Exception {
        String body = """
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                 "userName":"asmith","externalId":"ext-1",
                 "name":{"givenName":"Anne","familyName":"Smith"},
                 "emails":[{"value":"anne@example.com","primary":true}],
                 "active":true}
                """;
        HttpResponse<String> created = send("POST", "/scim/v2/Users", body);
        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode createdUser = MAPPER.readTree(created.body());
        String id = createdUser.get("id").asText();
        assertThat(id).isNotBlank();
        assertThat(createdUser.get("userName").asText()).isEqualTo("asmith");

        HttpResponse<String> fetched = send("GET", "/scim/v2/Users/" + id, null);
        assertThat(fetched.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(fetched.body()).get("emails").get(0).get("value").asText())
                .isEqualTo("anne@example.com");

        HttpResponse<String> list = send("GET", "/scim/v2/Users", null);
        assertThat(list.statusCode()).isEqualTo(200);
        JsonNode listJson = MAPPER.readTree(list.body());
        assertThat(listJson.get("schemas").get(0).asText())
                .isEqualTo("urn:ietf:params:scim:api:messages:2.0:ListResponse");
        assertThat(listJson.get("Resources")).isNotEmpty();
    }

    @Test
    void duplicateUserNameYieldsScim409() throws Exception {
        String body = """
                {"userName":"dupe","name":{"givenName":"D","familyName":"U"}}
                """;
        assertThat(send("POST", "/scim/v2/Users", body).statusCode()).isEqualTo(201);
        HttpResponse<String> conflict = send("POST", "/scim/v2/Users", body);
        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(MAPPER.readTree(conflict.body()).get("scimType").asText()).isEqualTo("uniqueness");
    }

    @Test
    void replaceUpdatesUserAndDeleteRemovesIt() throws Exception {
        String id = MAPPER.readTree(send("POST", "/scim/v2/Users",
                "{\"userName\":\"rdel\",\"name\":{\"givenName\":\"R\",\"familyName\":\"D\"}}").body())
                .get("id").asText();

        HttpResponse<String> replaced = send("PUT", "/scim/v2/Users/" + id,
                "{\"userName\":\"rdel\",\"name\":{\"givenName\":\"Renamed\",\"familyName\":\"D\"},"
                        + "\"active\":false}");
        assertThat(replaced.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(replaced.body()).get("name").get("givenName").asText())
                .isEqualTo("Renamed");

        assertThat(send("DELETE", "/scim/v2/Users/" + id, null).statusCode()).isEqualTo(204);
        assertThat(send("GET", "/scim/v2/Users/" + id, null).statusCode()).isEqualTo(404);
        assertThat(send("DELETE", "/scim/v2/Users/" + id, null).statusCode()).isEqualTo(404);
    }

    @Test
    void patchDeactivatesAndRenamesUser() throws Exception {
        String id = MAPPER.readTree(send("POST", "/scim/v2/Users",
                "{\"userName\":\"patchme\",\"name\":{\"givenName\":\"Pat\",\"familyName\":\"Ch\"},"
                        + "\"active\":true}").body()).get("id").asText();

        HttpResponse<String> patched = send("PATCH", "/scim/v2/Users/" + id, """
                {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                 "Operations":[
                   {"op":"replace","path":"name.givenName","value":"Patricia"},
                   {"op":"replace","value":{"active":false}}
                 ]}
                """);
        assertThat(patched.statusCode()).isEqualTo(200);
        JsonNode user = MAPPER.readTree(patched.body());
        assertThat(user.get("name").get("givenName").asText()).isEqualTo("Patricia");
        assertThat(user.get("active").asBoolean()).isFalse();

        // The change is persisted (re-fetch reflects it).
        JsonNode refetched = MAPPER.readTree(send("GET", "/scim/v2/Users/" + id, null).body());
        assertThat(refetched.get("name").get("givenName").asText()).isEqualTo("Patricia");
    }

    @Test
    void filterByUserNameEqReturnsMatch() throws Exception {
        send("POST", "/scim/v2/Users", "{\"userName\":\"filterme\"}");

        String encoded = java.net.URLEncoder.encode("userName eq \"filterme\"", StandardCharsets.UTF_8);
        HttpResponse<String> list = send("GET", "/scim/v2/Users?filter=" + encoded, null);
        assertThat(list.statusCode()).isEqualTo(200);
        JsonNode json = MAPPER.readTree(list.body());
        assertThat(json.get("totalResults").asInt()).isEqualTo(1);
        assertThat(json.get("Resources").get(0).get("userName").asText()).isEqualTo("filterme");

        String missing = java.net.URLEncoder.encode("userName eq \"nobody\"", StandardCharsets.UTF_8);
        assertThat(MAPPER.readTree(send("GET", "/scim/v2/Users?filter=" + missing, null).body())
                .get("totalResults").asInt()).isZero();
    }

    @Test
    void requiresAuthentication() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + runtime.port() + "/scim/v2/Users")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(401);
    }

    private static HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + runtime.port() + path))
                .header("Authorization", "Bearer " + token());
        switch (method) {
            case "POST" -> request.header("Content-Type", "application/scim+json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            case "PUT" -> request.header("Content-Type", "application/scim+json")
                    .PUT(HttpRequest.BodyPublishers.ofString(body));
            case "PATCH" -> request.header("Content-Type", "application/scim+json")
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(body));
            case "DELETE" -> request.DELETE();
            default -> request.GET();
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String token() throws Exception {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = enc.encodeToString(
                MAPPER.writeValueAsBytes(Map.of("sub", "idp", "roles", List.of("SCIM"))));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(
                "dev-only-secret-change-me-in-production".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = enc.encodeToString(
                mac.doFinal((header + "." + payload).getBytes(StandardCharsets.US_ASCII)));
        return header + "." + payload + "." + signature;
    }

    private static void seedDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("create table scim_users (id serial primary key, "
                    + "user_name varchar(200) not null unique, given_name varchar(200), "
                    + "family_name varchar(200), email varchar(320), active boolean default true, "
                    + "external_id varchar(200))");
        }
    }

    private static Path prepareAppHome() throws IOException {
        Path source = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        Path target = Files.createTempDirectory("tesseraql-scim-it");
        try (Stream<Path> files = Files.walk(source)) {
            files.forEach(path -> copy(source, target, path));
        }
        Files.writeString(target.resolve("config/application.yml"), """
                server:
                  port: 0

                db:
                  main:
                    url: %s
                    username: %s
                    password: %s

                tesseraql:
                  scim:
                    enabled: true
                    users:
                      create: scim/create-user.sql
                      findById: scim/find-user.sql
                      list: scim/list-users.sql
                      replace: scim/replace-user.sql
                      delete: scim/delete-user.sql
                      findByUserName: scim/find-user-by-name.sql
                  security:
                    policies:
                      scim.manage:
                        anyOf:
                          - role: SCIM
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));

        Path scim = target.resolve("scim");
        Files.createDirectories(scim);
        Files.writeString(scim.resolve("create-user.sql"), """
                insert into scim_users (user_name, given_name, family_name, email, active, external_id)
                values (/* userName */ 'u', /* givenName */ 'g', /* familyName */ 'f',
                        /* email */ 'e', /* active */ true, /* externalId */ 'x')
                returning id, user_name as "userName", given_name as "givenName",
                          family_name as "familyName", email as "email", active as "active",
                          external_id as "externalId"
                """);
        Files.writeString(scim.resolve("find-user.sql"), """
                select id, user_name as "userName", given_name as "givenName",
                       family_name as "familyName", email as "email", active as "active",
                       external_id as "externalId"
                from scim_users where id::text = /* id */ '0'
                """);
        Files.writeString(scim.resolve("list-users.sql"), """
                select id, user_name as "userName", given_name as "givenName",
                       family_name as "familyName", email as "email", active as "active",
                       external_id as "externalId"
                from scim_users order by id
                limit /* count */ 100 offset (/* startIndex */ 1 - 1)
                """);
        Files.writeString(scim.resolve("replace-user.sql"), """
                update scim_users set user_name = /* userName */ 'u', given_name = /* givenName */ 'g',
                       family_name = /* familyName */ 'f', email = /* email */ 'e',
                       active = /* active */ true, external_id = /* externalId */ 'x'
                where id::text = /* id */ '0'
                returning id, user_name as "userName", given_name as "givenName",
                          family_name as "familyName", email as "email", active as "active",
                          external_id as "externalId"
                """);
        Files.writeString(scim.resolve("delete-user.sql"),
                "delete from scim_users where id::text = /* id */ '0' returning id\n");
        Files.writeString(scim.resolve("find-user-by-name.sql"), """
                select id, user_name as "userName", given_name as "givenName",
                       family_name as "familyName", email as "email", active as "active",
                       external_id as "externalId"
                from scim_users where user_name = /* userName */ 'u'
                """);
        return target;
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
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
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
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            });
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
