package io.tesseraql.yaml.plugins;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.util.Signatures;
import io.tesseraql.yaml.config.AppConfig;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginsTest {

    @TempDir
    Path appHome;

    private final Signatures.GeneratedKeyPair keys = Signatures.generateKeyPair();

    @BeforeEach
    void createPluginDir() throws IOException {
        Files.createDirectories(appHome.resolve("plugins"));
    }

    private AppConfig config(Map<String, Object> plugins) {
        return new AppConfig(Map.of("tesseraql", Map.of("plugins", plugins)), name -> null);
    }

    /** Writes a jar holding one marker resource, optionally signing it with the test key. */
    private Path jar(String name, String marker, boolean signed) throws IOException {
        Path jar = appHome.resolve("plugins/" + name + ".jar");
        try (OutputStream out = Files.newOutputStream(jar);
                JarOutputStream entries = new JarOutputStream(out)) {
            entries.putNextEntry(new JarEntry(marker));
            entries.write("marker".getBytes(StandardCharsets.UTF_8));
            entries.closeEntry();
        }
        if (signed) {
            Files.writeString(jar.resolveSibling(name + ".jar.sig"),
                    Signatures.sign(Files.readAllBytes(jar), keys.privateKey()));
        }
        return jar;
    }

    @Test
    void signedJarsLoadInIsolatedClassLoaders() throws Exception {
        jar("alpha", "alpha-marker.txt", true);
        jar("beta", "beta-marker.txt", true);
        AppConfig config = config(Map.of("trustedKeys", List.of(keys.publicKey())));

        List<Plugins.PluginJar> plugins = Plugins.load(config, appHome);

        assertThat(plugins).extracting(Plugins.PluginJar::name).containsExactly("alpha", "beta");
        // Each plugin sees its own resources but not its sibling's: isolated loaders.
        assertThat(plugins.get(0).classLoader().getResource("alpha-marker.txt")).isNotNull();
        assertThat(plugins.get(0).classLoader().getResource("beta-marker.txt")).isNull();
        assertThat(plugins.get(1).classLoader().getResource("beta-marker.txt")).isNotNull();
    }

    @Test
    void tamperedJarIsRejected() throws Exception {
        Path jar = jar("alpha", "alpha-marker.txt", true);
        Files.write(jar, new byte[] {0}, StandardOpenOption.APPEND);
        AppConfig config = config(Map.of("trustedKeys", List.of(keys.publicKey())));

        assertThatThrownBy(() -> Plugins.load(config, appHome))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-PLUGIN-1303")
                .hasMessageContaining("does not verify");
    }

    @Test
    void unsignedJarIsRejectedAndUntrustedKeyDoesNotVerify() throws Exception {
        Path unsigned = jar("alpha", "alpha-marker.txt", false);
        AppConfig trusted = config(Map.of("trustedKeys", List.of(keys.publicKey())));
        assertThatThrownBy(() -> Plugins.load(trusted, appHome))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-PLUGIN-1302");
        Files.delete(unsigned);

        jar("beta", "beta-marker.txt", true);
        AppConfig otherKey = config(Map.of("trustedKeys",
                List.of(Signatures.generateKeyPair().publicKey())));
        assertThatThrownBy(() -> Plugins.load(otherKey, appHome))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-PLUGIN-1303");
    }

    @Test
    void jarsWithoutTrustedKeysFailLoudlyUnlessSignatureCheckIsDisabled() throws Exception {
        jar("alpha", "alpha-marker.txt", false);

        assertThatThrownBy(() -> Plugins.load(config(Map.of()), appHome))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-PLUGIN-1301")
                .hasMessageContaining("no tesseraql.plugins.trustedKeys");

        AppConfig dev = config(Map.of("requireSignature", "false"));
        assertThat(Plugins.load(dev, appHome)).hasSize(1);
    }

    @Test
    void missingPluginDirectoryMeansNoPlugins() {
        AppConfig config = new AppConfig(Map.of(), name -> null);
        assertThat(Plugins.load(config, appHome.resolve("nowhere"))).isEmpty();
    }

    @Test
    void allowlistGatesExtensionNamesWhenConfigured() {
        AppConfig open = config(Map.of());
        assertThat(Plugins.allowed(open, "scim")).isTrue();

        AppConfig gated = config(Map.of("allowlist", List.of("scim")));
        assertThat(Plugins.allowed(gated, "scim")).isTrue();
        assertThat(Plugins.allowed(gated, "saml")).isFalse();
    }
}
