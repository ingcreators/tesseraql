package io.tesseraql.operations.app;

import io.tesseraql.yaml.SimpleYamlParser;
import io.tesseraql.yaml.config.AppConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * The stack's own settings — {@code tesseraql-stack.yml} in the directory {@code --stack} names
 * (docs/stack-architecture.md Decision 22).
 *
 * <p>The file is always {@code <stack dir>/tesseraql-stack.yml} and nothing names it separately:
 * the directory is the only place the file can be, so a second source would be a second answer to
 * one question. It is loaded through the configuration machinery applications already use, so
 * {@code ${secret.…}} and {@code ${ENV_VAR:default}} resolve in it exactly as they do in
 * {@code config/tesseraql.yml}.
 *
 * <p>What belongs here is Decision 16's rule on the operator's surface: a setting whose divergence
 * — between applications, or between development and production — fails silently. The framework
 * datasource (divergence presents as "signing in does not carry"), the external origin (MCP's
 * {@code resource} must match what the user typed character for character), and the token issuer's
 * triple. The gateway's port, HTTP/2 and trusted proxies stay flags, because a wrong value fails
 * loudly at bind or handshake.
 *
 * <p>The file is also the stack's <b>marker</b>: {@code tesseraql new} generates it beside the
 * application it creates, and discovery's one-level step up keys on its presence
 * (docs/cli-surface.md Decision 9). A marker needs no content to mark, so absence of any given
 * setting is ordinary — every check downstream is keyed on what the stack <em>supplies</em>,
 * never on whether the file exists.
 */
public final class StackSettings {

    /** The file's name, which is also the stack's marker for discovery. */
    public static final String FILE_NAME = "tesseraql-stack.yml";

    private static final StackSettings EMPTY = new StackSettings(new AppConfig(Map.of()));

    private final AppConfig config;

    private StackSettings(AppConfig config) {
        this.config = config;
    }

    /** The settings the stack at {@code stackDir} declares; empty when there is no file. */
    public static StackSettings load(Path stackDir) {
        Path file = stackDir.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            return EMPTY;
        }
        // The file new generates is all guidance comments — a marker, which needs no content to
        // mark — and the parser refuses a document with no content. Semantically empty IS the
        // ordinary case, so it loads as empty rather than erroring.
        String yaml;
        try {
            yaml = Files.readString(file);
        } catch (java.io.IOException unreadable) {
            throw new java.io.UncheckedIOException(unreadable);
        }
        boolean onlyComments = yaml.lines()
                .allMatch(line -> line.isBlank() || line.stripLeading().startsWith("#"));
        if (onlyComments) {
            return EMPTY;
        }
        return new StackSettings(new AppConfig(new SimpleYamlParser().parseTree(file)));
    }

    /**
     * The connection the stack's framework state rides — one sign-in across the stack.
     *
     * <p>A coordinate, not a name: an application's {@code tesseraql.framework.datasource} names
     * an entry in its own registry, while the stack declares the connection itself, so no
     * reserved datasource name has to be invented and no application's registry gains an entry
     * it did not declare (Decision 22).
     */
    public Optional<Coordinate> frameworkDatasource() {
        return config.getString("framework.datasource.jdbcUrl")
                .map(jdbcUrl -> new Coordinate(jdbcUrl,
                        config.getString("framework.datasource.username").orElse(null),
                        config.getString("framework.datasource.password").orElse(null)));
    }

    /**
     * The origin this stack is reached at from outside — what an MCP client or the authorization
     * server's metadata must echo character for character. A host behind an ingress cannot know
     * it, which is why it lives here and is never defaulted by {@code host}.
     */
    public Optional<String> externalOrigin() {
        return config.getString("externalOrigin");
    }

    /**
     * The application the origin root redirects to, by name — {@code root.redirect}
     * (docs/stack-architecture.md Decision 24). Absent means the default target, the stack's
     * portal. Validated against the stack's membership at gateway start, not here: this class
     * reads the file and does not know what the stack holds.
     */
    public Optional<String> rootRedirect() {
        return config.getString("root.redirect");
    }

    /** The whole file, for settings later slices read (the token issuer's {@code security.jwt.*}). */
    public AppConfig config() {
        return config;
    }

    /**
     * The stack's {@code security:} subtree — the token issuer's {@code security.jwt.*} and
     * {@code security.token.enabled} — or {@code null} when the stack supplies none.
     *
     * <p>The host grafts it onto the <em>surface runtime's</em> configuration (as
     * {@code tesseraql.security.*}), because the surface is where the stack's own authenticated
     * endpoints live: the origin token page and exchange, and the deploy endpoint
     * (docs/stack-shells.md, the deploy surface). Members keep their own declared JWT
     * configuration — unifying the stack's issuer across members is the authorization server's
     * slice, not this key's.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> surfaceSecurity() {
        Object security = config.navigate("security");
        return security instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    /** A JDBC connection declared by the stack: url, and credentials when not carried in it. */
    public record Coordinate(String jdbcUrl, String username, String password) {
    }
}
