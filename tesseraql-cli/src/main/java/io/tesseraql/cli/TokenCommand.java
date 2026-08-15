package io.tesseraql.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.manifest.ManifestLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Mints a development bearer token signed with the app's configured HS256 secret
 * (docs/authentication.md) — the smoke-test loop's missing tool: exercising an API-shaped
 * app used to mean hand-assembling a JWT. The claim layout mirrors what the runtime
 * verifies: roles under the configured {@code rolesClaim}, permissions under
 * {@code permissionsClaim}, custom claims verbatim (a value that parses as JSON — e.g.
 * {@code '["a","b"]'} — is embedded structurally, anything else as a string).
 *
 * <p>Development only, and structurally so: an app whose verification is asymmetric
 * (publicKey/jwksUri, no shared secret) has nothing this command could sign with, and
 * production deployments are expected to be exactly that or to inject the secret from the
 * environment — the command signs with whatever the resolved config exposes and says so
 * on stderr.
 */
@Command(name = "token", description = "Mint a development bearer token signed with the app's HS256 secret.")
public final class TokenCommand implements Callable<Integer> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Option(names = {"--app"}, required = true, description = "Path to the app home.")
    Path app;

    @Option(names = {
            "--env"}, paramLabel = "<profile>", description = "Environment profile (also TESSERAQL_ENV).")
    String envProfile;

    @Option(names = {"--sub"}, description = "Subject claim (default dev).")
    String subject = "dev";

    @Option(names = {"--login"}, description = "loginId claim (default: the subject).")
    String login;

    @Option(names = {
            "--role"}, paramLabel = "<role>", description = "Role (repeatable); lands under the configured rolesClaim.")
    List<String> roles = new ArrayList<>();

    @Option(names = {
            "--permission"}, paramLabel = "<permission>", description = "Permission (repeatable); lands under the configured permissionsClaim.")
    List<String> permissions = new ArrayList<>();

    @Option(names = {"--claim"}, paramLabel = "<name=value>", description = "Custom claim "
            + "(repeatable). A value that parses as JSON ('[\"a\",\"b\"]', '7', 'true') is "
            + "embedded structurally; anything else is a string.")
    Map<String, String> claims = new LinkedHashMap<>();

    @Option(names = {
            "--ttl"}, paramLabel = "<duration>", description = "Lifetime, e.g. 30m, 12h, 7d (default 24h).")
    String ttl = "24h";

    @Override
    public Integer call() throws Exception {
        if (envProfile != null) {
            System.setProperty("tesseraql.env", envProfile);
        }
        AppConfig config = new ManifestLoader().load(app).config();
        String secret = config.getString("tesseraql.security.jwt.secret").orElse(null);
        if (secret == null || secret.isBlank()) {
            System.err.println("No tesseraql.security.jwt.secret in the resolved config - "
                    + "this app verifies asymmetrically (publicKey/jwksUri) or has no JWT "
                    + "auth; there is nothing to sign with.");
            return 1;
        }
        String rolesClaim = config.getString("tesseraql.security.jwt.rolesClaim")
                .orElse("roles");
        String permissionsClaim = config.getString("tesseraql.security.jwt.permissionsClaim")
                .orElse("permissions");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sub", subject);
        payload.put("loginId", login == null ? subject : login);
        if (!roles.isEmpty()) {
            payload.put(rolesClaim, roles);
        }
        if (!permissions.isEmpty()) {
            payload.put(permissionsClaim, permissions);
        }
        claims.forEach((name, value) -> payload.put(name, parseValue(value)));
        payload.put("exp", Instant.now().plusSeconds(ttlSeconds(ttl)).getEpochSecond());
        // The app now refuses a token that is not addressed to it (docs/audit-hardening.md
        // Decision 1), so a token minted here has to carry the audience that app declares — an
        // aud-less token would be signed correctly and rejected on arrival, which is a confusing
        // way to spend an afternoon. One declared audience emits the string form, several emit
        // the array; an explicit --claim aud=... still wins, since it was applied above.
        if (!payload.containsKey("aud")) {
            List<String> audience = declaredAudience(config);
            if (audience.size() == 1) {
                payload.put("aud", audience.get(0));
            } else if (!audience.isEmpty()) {
                payload.put("aud", audience);
            }
        }

        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder.encodeToString(
                "{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String body = encoder.encodeToString(MAPPER.writeValueAsBytes(payload));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = encoder.encodeToString(
                mac.doFinal((header + "." + body).getBytes(StandardCharsets.US_ASCII)));

        System.err.println("Dev token (" + ttl + ", " + payload.keySet()
                + "): signed with the app's configured HS256 secret - development use only.");
        System.out.println(header + "." + body + "." + signature);
        return 0;
    }

    /** The audiences the app declares, written either as one string or as a list. */
    private static List<String> declaredAudience(AppConfig config) {
        Object declared = config.navigate("tesseraql.security.jwt.audience");
        if (declared instanceof List<?> list) {
            List<String> result = new ArrayList<>(list.size());
            list.forEach(element -> result.add(String.valueOf(element)));
            return result;
        }
        if (declared instanceof String single && !single.isBlank()) {
            return List.of(single.trim());
        }
        return List.of();
    }

    /** A claim value that parses as JSON embeds structurally; anything else is a string. */
    private static Object parseValue(String value) {
        String trimmed = value.trim();
        if (trimmed.startsWith("[") || trimmed.startsWith("{") || "true".equals(trimmed)
                || "false".equals(trimmed) || trimmed.matches("-?\\d+(\\.\\d+)?")) {
            try {
                return MAPPER.readValue(trimmed, Object.class);
            } catch (com.fasterxml.jackson.core.JacksonException notJson) {
                return value;
            }
        }
        return value;
    }

    /** Parses {@code 30m}/{@code 12h}/{@code 7d} (bare numbers are seconds). */
    static long ttlSeconds(String ttl) {
        String trimmed = ttl.trim().toLowerCase(java.util.Locale.ROOT);
        long unit = switch (trimmed.charAt(trimmed.length() - 1)) {
            case 'm' -> 60;
            case 'h' -> 3600;
            case 'd' -> 86400;
            default -> 1;
        };
        String number = Character.isDigit(trimmed.charAt(trimmed.length() - 1))
                ? trimmed
                : trimmed.substring(0, trimmed.length() - 1);
        long value = Long.parseLong(number);
        if (value <= 0) {
            throw new IllegalArgumentException("ttl must be positive: " + ttl);
        }
        return value * unit;
    }
}
