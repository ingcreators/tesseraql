package io.tesseraql.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * The dev-token minting loop (docs/getting-started.md): the token verifies against the
 * app's configured secret, roles land under the configured rolesClaim, JSON-looking claim
 * values embed structurally, and an asymmetric-only app has nothing to sign with.
 */
class TokenCommandTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void mintsAVerifiableTokenWithConfiguredClaimNames(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: token-test
                  security:
                    jwt:
                      secret: unit-test-secret
                      audience: https://app.example.com
                      rolesClaim: groups
                """);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream stdout = System.out;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        int exit;
        try {
            exit = new CommandLine(new TokenCommand()).execute("--app", dir.toString(),
                    "--sub", "aoki", "--role", "SUPPLIER", "--claim", "partner=P-100",
                    "--claim", "departments=[\"engineering\",\"sales\"]", "--ttl", "30m");
        } finally {
            System.setOut(stdout);
        }
        assertThat(exit).isZero();

        String token = out.toString(StandardCharsets.UTF_8).trim();
        String[] parts = token.split("\\.");
        assertThat(parts).hasSize(3);

        // The signature verifies against the configured secret.
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("unit-test-secret".getBytes(StandardCharsets.UTF_8),
                "HmacSHA256"));
        String expected = Base64.getUrlEncoder().withoutPadding().encodeToString(
                mac.doFinal((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII)));
        assertThat(parts[2]).isEqualTo(expected);

        JsonNode payload = MAPPER.readTree(Base64.getUrlDecoder().decode(parts[1]));
        assertThat(payload.get("sub").asText()).isEqualTo("aoki");
        assertThat(payload.get("loginId").asText()).isEqualTo("aoki");
        // Roles land under the CONFIGURED claim name, not a hardcoded one.
        assertThat(payload.get("groups").get(0).asText()).isEqualTo("SUPPLIER");
        assertThat(payload.get("partner").asText()).isEqualTo("P-100");
        assertThat(payload.get("departments").isArray()).isTrue();
        assertThat(payload.get("departments").get(1).asText()).isEqualTo("sales");
        assertThat(payload.get("exp").asLong())
                .isBetween(System.currentTimeMillis() / 1000 + 1500,
                        System.currentTimeMillis() / 1000 + 1900);
    }

    @Test
    void anAsymmetricOnlyAppHasNothingToSignWith(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: token-test
                  security:
                    jwt:
                      jwksUri: https://idp.example.com/jwks
                      audience: https://app.example.com
                """);

        int exit = new CommandLine(new TokenCommand())
                .execute("--app", dir.toString(), "--role", "ADMIN");

        assertThat(exit).isEqualTo(1);
    }

    @Test
    void ttlUnitsParse() {
        assertThat(TokenCommand.ttlSeconds("30m")).isEqualTo(1800);
        assertThat(TokenCommand.ttlSeconds("12h")).isEqualTo(43200);
        assertThat(TokenCommand.ttlSeconds("7d")).isEqualTo(604800);
        assertThat(TokenCommand.ttlSeconds("90")).isEqualTo(90);
        assertThatThrownBy(() -> TokenCommand.ttlSeconds("0h"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
