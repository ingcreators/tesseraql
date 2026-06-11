package io.tesseraql.yaml.plugins;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.util.Signatures;
import io.tesseraql.yaml.config.AppConfig;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Loads plugin jars from the app's plugin directory with supply-chain guardrails (design ch. 47):
 * every jar must carry a detached Ed25519 signature ({@code <jar>.sig}, base64 over the jar bytes)
 * that verifies against one of the configured trusted keys, and each verified jar gets its own
 * isolated class loader so plugins cannot see each other's classes.
 *
 * <pre>
 * tesseraql:
 *   plugins:
 *     dir: plugins                 # *.jar + *.jar.sig, relative to the app home
 *     trustedKeys:
 *       - MCowBQYDK2Vw...          # Ed25519 public keys (base64 X.509 or PEM)
 *     requireSignature: true       # explicit false skips verification (development only)
 *     allowlist: [scim, saml]      # when present, only these extension names may install
 * </pre>
 *
 * <p>The allowlist applies to every discovered extension - plugin-dir and classpath alike - so a
 * jar that arrives on the classpath unnoticed cannot install itself past the configuration.
 */
public final class Plugins {

    private static final TqlErrorCode NO_TRUSTED_KEYS = new TqlErrorCode(TqlDomain.PLUGIN, 1301);
    private static final TqlErrorCode MISSING_SIGNATURE = new TqlErrorCode(TqlDomain.PLUGIN, 1302);
    private static final TqlErrorCode INVALID_SIGNATURE = new TqlErrorCode(TqlDomain.PLUGIN, 1303);

    private Plugins() {
    }

    /** A verified plugin jar and its isolated class loader. */
    public record PluginJar(String name, Path jar, URLClassLoader classLoader) {
    }

    /** Loads and verifies every jar in the plugin directory; empty when there is none. */
    public static List<PluginJar> load(AppConfig config, Path appHome) {
        Path dir = appHome.resolve(
                config.getString("tesseraql.plugins.dir").orElse("plugins"));
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        boolean requireSignature = config.getString("tesseraql.plugins.requireSignature")
                .map(Boolean::parseBoolean).orElse(true);
        List<String> trustedKeys = trustedKeys(config);
        List<PluginJar> plugins = new ArrayList<>();
        for (Path jar : jars(dir)) {
            if (requireSignature) {
                verify(jar, trustedKeys);
            }
            plugins.add(new PluginJar(pluginName(jar), jar, isolatedLoader(jar)));
        }
        return List.copyOf(plugins);
    }

    /** Whether an extension name may install under {@code tesseraql.plugins.allowlist}. */
    public static boolean allowed(AppConfig config, String extensionName) {
        Object allowlist = config.navigate("tesseraql.plugins.allowlist");
        if (!(allowlist instanceof List<?> list)) {
            return true;
        }
        return list.stream().map(String::valueOf).anyMatch(extensionName::equals);
    }

    private static void verify(Path jar, List<String> trustedKeys) {
        if (trustedKeys.isEmpty()) {
            throw new TqlException(NO_TRUSTED_KEYS, "Plugin jar " + jar.getFileName()
                    + " found but no tesseraql.plugins.trustedKeys are configured"
                    + " (set tesseraql.plugins.requireSignature: false to allow unsigned"
                    + " plugins in development)");
        }
        Path signatureFile = jar.resolveSibling(jar.getFileName() + ".sig");
        if (!Files.isRegularFile(signatureFile)) {
            throw new TqlException(MISSING_SIGNATURE, "Plugin jar " + jar.getFileName()
                    + " has no detached signature " + signatureFile.getFileName());
        }
        try {
            byte[] payload = Files.readAllBytes(jar);
            String signature = Files.readString(signatureFile).trim();
            boolean verified = trustedKeys.stream()
                    .anyMatch(key -> Signatures.verify(payload, signature, key));
            if (!verified) {
                throw new TqlException(INVALID_SIGNATURE, "Plugin jar " + jar.getFileName()
                        + " signature does not verify against any trusted key");
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static List<String> trustedKeys(AppConfig config) {
        Object keys = config.navigate("tesseraql.plugins.trustedKeys");
        if (!(keys instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(key -> config.resolve(String.valueOf(key))).toList();
    }

    private static List<Path> jars(Path dir) {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static String pluginName(Path jar) {
        String file = jar.getFileName().toString();
        return file.substring(0, file.length() - ".jar".length());
    }

    private static URLClassLoader isolatedLoader(Path jar) {
        try {
            return new URLClassLoader("tesseraql-plugin-" + pluginName(jar),
                    new URL[]{jar.toUri().toURL()}, Plugins.class.getClassLoader());
        } catch (MalformedURLException ex) {
            throw new UncheckedIOException(new IOException(ex));
        }
    }
}
