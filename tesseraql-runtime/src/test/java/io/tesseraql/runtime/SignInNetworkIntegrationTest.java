package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.identity.DefaultIdentityPack;
import io.tesseraql.security.password.Pbkdf2PasswordEncoder;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The deployment sign-in allow-list (docs/access-governance.md structural decision 8, layer A):
 * {@code tesseraql.security.network.allow} refuses a sign-in from an unlisted network before a
 * session exists.
 *
 * <p>Two runtimes over one store, because the interesting assertion is the difference: the same
 * credentials from the same loopback client are refused by one and admitted by the other, so
 * what is being measured is the configured list and not the password.
 */
@Testcontainers
class SignInNetworkIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static final List<Path> HOMES = new ArrayList<>();

    static TesseraqlRuntime elsewhereOnly;
    static TesseraqlRuntime loopbackAllowed;

    @BeforeAll
    static void start() throws Exception {
        seedDatabase();
        elsewhereOnly = TesseraqlRuntime.start(appHome("10.0.0.0/8"), 0);
        loopbackAllowed = TesseraqlRuntime.start(appHome("10.0.0.0/8, 127.0.0.0/8"), 0);
    }

    @AfterAll
    static void stop() throws IOException {
        for (TesseraqlRuntime runtime : List.of(elsewhereOnly, loopbackAllowed)) {
            if (runtime != null) {
                runtime.close();
            }
        }
        for (Path home : HOMES) {
            deleteRecursively(home);
        }
    }

    @Test
    void aSignInFromAnUnlistedNetworkIsRefusedWithTheRightCredentials() throws Exception {
        HttpResponse<String> login = login(elsewhereOnly, "s3cret");

        assertThat(login.statusCode()).as("a refusal, not a challenge").isEqualTo(403);
        assertThat(login.body()).contains("TQL-SEC-4149");
        assertThat(login.headers().firstValue("Set-Cookie")).as("no session was established")
                .isEmpty();
    }

    @Test
    void theSameCredentialsFromAListedNetworkStillSignIn() throws Exception {
        HttpResponse<String> login = login(loopbackAllowed, "s3cret");

        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(login.headers().firstValue("Set-Cookie").orElse(""))
                .contains("tesseraql_sid=");
    }

    /**
     * The network check runs after the credential is proven, so a refusal from outside says
     * nothing about whether the password was right.
     */
    @Test
    void aWrongPasswordFromAnUnlistedNetworkIsStillAnInvalidCredential() throws Exception {
        assertThat(login(elsewhereOnly, "wrong").statusCode()).isEqualTo(401);
    }

    private static HttpResponse<String> login(TesseraqlRuntime runtime, String password)
            throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + runtime.port() + "/_tesseraql/login"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"loginId\":\"admin\",\"password\":\"" + password + "\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static void seedDatabase() throws Exception {
        String hash = new Pbkdf2PasswordEncoder().encode("s3cret");
        String params = new Pbkdf2PasswordEncoder().defaultParams();
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            for (String ddl : DefaultIdentityPack.schema("postgres").split(";")) {
                if (!ddl.isBlank()) {
                    statement.execute(ddl);
                }
            }
            statement.execute("insert into tql_users (user_id, login_id, display_name, status,"
                    + " password_hash, password_algo, password_params)"
                    + " values ('u1','admin','Administrator','ACTIVE','" + hash + "','pbkdf2','"
                    + params + "')");
        }
    }

    private static Path appHome(String allow) throws IOException {
        Path source = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        Path target = Files.createTempDirectory("tesseraql-signin-network-it");
        HOMES.add(target);
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
                  security:
                    network:
                      allow: "%s"
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword(), allow));
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

}
