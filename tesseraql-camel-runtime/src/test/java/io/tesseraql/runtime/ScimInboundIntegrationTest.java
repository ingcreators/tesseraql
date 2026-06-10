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
    void createsGetsPatchesAndDeletesGroupViaScim() throws Exception {
        String body = """
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],
                 "displayName":"engineers","externalId":"grp-1",
                 "members":[{"value":"100"}]}
                """;
        HttpResponse<String> created = send("POST", "/scim/v2/Groups", body);
        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode group = MAPPER.readTree(created.body());
        String id = group.get("id").asText();
        assertThat(id).isNotBlank();
        assertThat(group.get("displayName").asText()).isEqualTo("engineers");
        assertThat(group.get("members").get(0).get("value").asText()).isEqualTo("100");

        // PATCH adds a member and removes the original via a value-filter path.
        HttpResponse<String> patched = send("PATCH", "/scim/v2/Groups/" + id, """
                {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                 "Operations":[
                   {"op":"add","path":"members","value":[{"value":"200"}]},
                   {"op":"remove","path":"members[value eq \\"100\\"]"}
                 ]}
                """);
        assertThat(patched.statusCode()).isEqualTo(200);
        JsonNode members = MAPPER.readTree(patched.body()).get("members");
        assertThat(members).hasSize(1);
        assertThat(members.get(0).get("value").asText()).isEqualTo("200");

        HttpResponse<String> list = send("GET", "/scim/v2/Groups", null);
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(list.body()).get("Resources")).isNotEmpty();

        assertThat(send("DELETE", "/scim/v2/Groups/" + id, null).statusCode()).isEqualTo(204);
        assertThat(send("GET", "/scim/v2/Groups/" + id, null).statusCode()).isEqualTo(404);
    }

    @Test
    void replaceRenamesGroupAndReconcilesMembersBothWays() throws Exception {
        String id = MAPPER.readTree(send("POST", "/scim/v2/Groups", """
                {"displayName":"reconcile","members":[{"value":"1"},{"value":"2"}]}
                """).body()).get("id").asText();

        // PUT keeps member 2, drops 1, and adds 3 -> membership reconciled in both directions.
        HttpResponse<String> replaced = send("PUT", "/scim/v2/Groups/" + id, """
                {"displayName":"reconciled","members":[{"value":"2"},{"value":"3"}]}
                """);
        assertThat(replaced.statusCode()).isEqualTo(200);
        JsonNode group = MAPPER.readTree(replaced.body());
        assertThat(group.get("displayName").asText()).isEqualTo("reconciled");
        List<String> members = new java.util.ArrayList<>();
        group.get("members").forEach(member -> members.add(member.get("value").asText()));
        assertThat(members).containsExactlyInAnyOrder("2", "3");

        // Replacing a missing group is a 404.
        assertThat(send("PUT", "/scim/v2/Groups/999999", "{\"displayName\":\"ghost\"}").statusCode())
                .isEqualTo(404);
    }

    @Test
    void duplicateGroupNameYieldsScim409() throws Exception {
        String body = "{\"displayName\":\"dupe-group\"}";
        assertThat(send("POST", "/scim/v2/Groups", body).statusCode()).isEqualTo(201);
        HttpResponse<String> conflict = send("POST", "/scim/v2/Groups", body);
        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(MAPPER.readTree(conflict.body()).get("scimType").asText()).isEqualTo("uniqueness");
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
            statement.execute("create table scim_groups (id serial primary key, "
                    + "display_name varchar(200) not null unique, external_id varchar(200))");
            statement.execute("create table scim_group_members (group_id int not null, "
                    + "member_id varchar(200) not null, primary key (group_id, member_id))");
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
        Files.writeString(scim.resolve("create-group.sql"), """
                insert into scim_groups (display_name, external_id)
                values (/* displayName */ 'g', /* externalId */ 'x')
                returning id, display_name as "displayName", external_id as "externalId"
                """);
        Files.writeString(scim.resolve("find-group.sql"), """
                select id, display_name as "displayName", external_id as "externalId"
                from scim_groups where id::text = /* id */ '0'
                """);
        Files.writeString(scim.resolve("list-groups.sql"), """
                select id, display_name as "displayName", external_id as "externalId"
                from scim_groups order by id
                limit /* count */ 100 offset (/* startIndex */ 1 - 1)
                """);
        Files.writeString(scim.resolve("replace-group.sql"), """
                update scim_groups set display_name = /* displayName */ 'g',
                       external_id = /* externalId */ 'x'
                where id::text = /* id */ '0'
                returning id, display_name as "displayName", external_id as "externalId"
                """);
        Files.writeString(scim.resolve("delete-group.sql"),
                "delete from scim_groups where id::text = /* id */ '0' returning id\n");
        Files.writeString(scim.resolve("list-members.sql"), """
                select member_id as "value" from scim_group_members
                where group_id = cast(/* groupId */ '0' as int) order by member_id
                """);
        Files.writeString(scim.resolve("add-member.sql"), """
                insert into scim_group_members (group_id, member_id)
                values (cast(/* groupId */ '0' as int), /* memberId */ 'm')
                on conflict do nothing
                """);
        Files.writeString(scim.resolve("remove-member.sql"), """
                delete from scim_group_members
                where group_id = cast(/* groupId */ '0' as int) and member_id = /* memberId */ 'm'
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
