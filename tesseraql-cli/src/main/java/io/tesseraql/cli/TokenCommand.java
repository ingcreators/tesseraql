package io.tesseraql.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.manifest.ManifestLoader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
 * Obtains a bearer token, from a config on disk or from a running application.
 *
 * <p><b>{@code --app}</b> mints locally, signing with the app's configured HS256 secret
 * (docs/authentication.md) — the smoke-test loop's missing tool: exercising an API-shaped app used
 * to mean hand-assembling a JWT. The claim layout mirrors what the runtime verifies: roles under
 * the configured {@code rolesClaim}, permissions under {@code permissionsClaim}, custom claims
 * verbatim (a value that parses as JSON — e.g. {@code '["a","b"]'} — is embedded structurally,
 * anything else as a string). Development only, and structurally so: an app whose verification is
 * asymmetric (publicKey/jwksUri, no shared secret) has nothing this command could sign with, and
 * production deployments are expected to be exactly that or to inject the secret from the
 * environment — the command signs with whatever the resolved config exposes and says so on stderr.
 *
 * <p><b>{@code --url}</b> asks a running application instead: it signs in and exchanges in one
 * step (docs/stack-architecture.md Decision 20), which is the path for a deployment this machine
 * has an account on rather than the source of. Nothing is invented here — the server decides the
 * claims, the lifetime and whether it issues at all.
 *
 * <p>Either way the token alone goes to stdout, so the command pipes; everything else is stderr.
 */
@Command(name = "token", description = "Obtain a bearer token: mint one from an app's HS256 secret (--app), or sign in to a running application and exchange (--url).")
public final class TokenCommand implements Callable<Integer> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Option(names = {"--app"}, description = "Path to the app home; mints locally from its config.")
    Path app;

    @Option(names = {"--url"}, paramLabel = "<base-url>", description = "Base URL of a running "
            + "application; signs in and exchanges the session for a token. Include the base path "
            + "if the application has one.")
    String url;

    @Option(names = {
            "--env"}, paramLabel = "<profile>", description = "Environment profile (also TESSERAQL_ENV).")
    String envProfile;

    @Option(names = {"--sub"}, description = "Subject claim (default dev).")
    String subject = "dev";

    @Option(names = {"--login"}, description = "With --app, the loginId claim (default: the "
            + "subject). With --url, the login id to sign in as; required there.")
    String login;

    @Option(names = {"--password"}, description = "With --url: the password. Omit to be prompted, "
            + "or set TESSERAQL_PASSWORD — passing it here puts a credential in the process list "
            + "and the shell history.")
    String password;

    @Option(names = {
            "--tenant"}, paramLabel = "<id>", description = "With --url: the tenant to sign in to, for a multi-tenant realm.")
    String tenant;

    @Option(names = {"--otp"}, paramLabel = "<code>", description = "With --url: the TOTP code, "
            + "or a recovery code, when the account has an authenticator enrolled.")
    String otp;

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
        if ((app == null) == (url == null)) {
            System.err.println("Choose one: --app <path> mints locally from a config on disk, "
                    + "--url <base-url> signs in to a running application and exchanges.");
            return 2;
        }
        return url != null ? exchange() : mintLocally();
    }

    /**
     * Signs in and exchanges the session for a token, in one step.
     *
     * <p>The two calls are one flow rather than two commands because the session is useless in
     * between: it lives in a cookie this process throws away, and the exchange endpoint wants the
     * CSRF token that arrives with it. Nothing is stored — no cookie jar, no token cache. A token
     * that outlives the command would be a credential on disk nobody asked for.
     */
    private Integer exchange() throws Exception {
        // Every option below belongs to local minting: the server decides the claims and the
        // lifetime here, so accepting them silently would hand back a token that ignores them.
        String misplaced = String.join(", ", localOnlyOptionsGiven());
        if (!misplaced.isEmpty()) {
            System.err.println("--url takes its claims and lifetime from the application, so "
                    + misplaced + " cannot apply. The application decides what a token carries.");
            return 2;
        }
        if (login == null || login.isBlank()) {
            System.err.println("--url needs --login <id> to sign in as.");
            return 2;
        }
        String secret = resolvePassword();
        if (secret == null) {
            System.err.println("No password: pass --password, set TESSERAQL_PASSWORD, or run "
                    + "where a terminal can prompt.");
            return 2;
        }

        String base = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        // Redirects are not followed: a 3xx here means the base URL is wrong, or something in
        // front rewrote the call. Following it would drop the cookie the next request needs and
        // fail one step later, where the cause is no longer visible.
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .build();

        Map<String, Object> credentials = new LinkedHashMap<>();
        credentials.put("loginId", login);
        credentials.put("password", secret);
        if (tenant != null) {
            credentials.put("tenantId", tenant);
        }
        if (otp != null) {
            credentials.put("otp", otp);
        }
        HttpResponse<String> session = send(client, base + "/_tesseraql/login",
                MAPPER.writeValueAsString(credentials), null, null);
        if (session.statusCode() == 429) {
            System.err.println("Too many attempts; retry after "
                    + session.headers().firstValue("Retry-After").orElse("a while") + "s.");
            return 1;
        }
        if (session.statusCode() / 100 == 3) {
            System.err.println("Sign-in redirected to "
                    + session.headers().firstValue("Location").orElse("somewhere else")
                    + " — check --url, including the base path if the application has one.");
            return 1;
        }
        if (session.statusCode() != 200) {
            System.err.println("Sign-in failed (HTTP " + session.statusCode() + "): "
                    + summarize(session.body()));
            return 1;
        }
        String cookie = sessionCookie(session);
        if (cookie == null) {
            System.err.println("Signed in, but the response carried no session cookie — "
                    + base + " may not be a TesseraQL application.");
            return 1;
        }
        String csrf = MAPPER.readTree(session.body()).path("csrfToken").asText(null);
        if (csrf == null || csrf.isBlank()) {
            System.err.println("Signed in, but the response carried no csrfToken. The exchange "
                    + "endpoint requires it, and this application is older than the field.");
            return 1;
        }

        HttpResponse<String> minted = send(client, base + "/_tesseraql/token", "{}", cookie, csrf);
        if (minted.statusCode() == 404) {
            System.err.println("This application does not issue tokens: set "
                    + "tesseraql.security.token.enabled to true in its configuration.");
            return 1;
        }
        if (minted.statusCode() != 200) {
            System.err.println("Exchange failed (HTTP " + minted.statusCode() + "): "
                    + summarize(minted.body()));
            return 1;
        }
        var answer = MAPPER.readTree(minted.body());
        System.err.println("Bearer token for " + login + " at " + base + ", expiring "
                + answer.path("expiresAt").asText() + " - the application chose the claims and "
                + "the lifetime, and cannot revoke it before it expires.");
        System.out.println(answer.path("token").asText());
        return 0;
    }

    /** The options that only mean something when minting locally, as the user spelled them. */
    private List<String> localOnlyOptionsGiven() {
        List<String> given = new ArrayList<>();
        if (!"dev".equals(subject)) {
            given.add("--sub");
        }
        if (!roles.isEmpty()) {
            given.add("--role");
        }
        if (!permissions.isEmpty()) {
            given.add("--permission");
        }
        if (!claims.isEmpty()) {
            given.add("--claim");
        }
        if (!"24h".equals(ttl)) {
            given.add("--ttl");
        }
        if (envProfile != null) {
            given.add("--env");
        }
        return given;
    }

    /** {@code --password}, then {@code TESSERAQL_PASSWORD}, then a terminal prompt. */
    private String resolvePassword() {
        if (password != null && !password.isEmpty()) {
            return password;
        }
        String fromEnvironment = System.getenv("TESSERAQL_PASSWORD");
        if (fromEnvironment != null && !fromEnvironment.isEmpty()) {
            return fromEnvironment;
        }
        java.io.Console console = System.console();
        if (console == null) {
            return null;
        }
        char[] typed = console.readPassword("Password for %s: ", login);
        return typed == null || typed.length == 0 ? null : new String(typed);
    }

    private static HttpResponse<String> send(HttpClient client, String uri, String body,
            String cookie, String csrf) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(uri))
                .header("Content-Type", "application/json")
                .timeout(java.time.Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (cookie != null) {
            request.header("Cookie", cookie);
        }
        if (csrf != null) {
            request.header("X-CSRF-Token", csrf);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * The {@code name=value} pair from the login response's {@code Set-Cookie}.
     *
     * <p>Read rather than assumed: the session cookie's name is configurable, so hard-coding
     * {@code tesseraql_sid} would work everywhere it was tested and nowhere it was renamed.
     */
    private static String sessionCookie(HttpResponse<String> response) {
        for (String header : response.headers().allValues("Set-Cookie")) {
            String pair = header.split(";", 2)[0].trim();
            if (pair.contains("=") && !pair.endsWith("=")) {
                return pair;
            }
        }
        return null;
    }

    /** A server answer, trimmed to one line — the envelope's code is the part worth showing. */
    private static String summarize(String body) {
        if (body == null || body.isBlank()) {
            return "no answer body";
        }
        String collapsed = body.strip().replaceAll("\\s+", " ");
        return collapsed.length() > 200 ? collapsed.substring(0, 200) + "…" : collapsed;
    }

    private Integer mintLocally() throws Exception {
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
