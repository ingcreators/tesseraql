package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.compiler.ext.RuntimeExtension;
import io.tesseraql.core.util.Signatures;
import io.tesseraql.yaml.config.AppConfig;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeExtensionsTest {

    private static final String EXTENSION_CLASS = "io.tesseraql.runtime.TestPluginExtension";

    @TempDir
    Path appHome;

    private final Signatures.GeneratedKeyPair keys = Signatures.generateKeyPair();

    @BeforeEach
    void packagePluginJar() throws IOException {
        Files.createDirectories(appHome.resolve("plugins"));
        Path jar = appHome.resolve("plugins/test-plugin.jar");
        String classResource = EXTENSION_CLASS.replace('.', '/') + ".class";
        try (OutputStream out = Files.newOutputStream(jar);
                JarOutputStream entries = new JarOutputStream(out);
                InputStream compiled = getClass().getResourceAsStream("/" + classResource)) {
            entries.putNextEntry(new JarEntry(classResource));
            entries.write(compiled.readAllBytes());
            entries.closeEntry();
            entries.putNextEntry(new JarEntry(
                    "META-INF/services/io.tesseraql.compiler.ext.RuntimeExtension"));
            entries.write(EXTENSION_CLASS.getBytes(StandardCharsets.UTF_8));
            entries.closeEntry();
        }
        Files.writeString(appHome.resolve("plugins/test-plugin.jar.sig"),
                Signatures.sign(Files.readAllBytes(jar), keys.privateKey()));
    }

    private AppConfig config(Map<String, Object> plugins) {
        return new AppConfig(Map.of("tesseraql", Map.of("plugins", plugins)), name -> null);
    }

    @Test
    void discoversExtensionsFromVerifiedPluginJars() {
        AppConfig config = config(Map.of("trustedKeys", List.of(keys.publicKey())));
        List<RuntimeExtension> extensions = RuntimeExtensions.discover(config, appHome);
        assertThat(extensions).extracting(RuntimeExtension::name).contains("test-plugin");
    }

    @Test
    void allowlistExcludesUnlistedExtensions() {
        AppConfig config = config(Map.of(
                "trustedKeys", List.of(keys.publicKey()),
                "allowlist", List.of("something-else")));
        List<RuntimeExtension> extensions = RuntimeExtensions.discover(config, appHome);
        assertThat(extensions).extracting(RuntimeExtension::name).doesNotContain("test-plugin");
    }

    @Test
    void withoutPluginDirectoryOnlyClasspathExtensionsRemain() {
        AppConfig config = new AppConfig(Map.of(), name -> null);
        List<RuntimeExtension> extensions = RuntimeExtensions.discover(config,
                appHome.resolve("nowhere"));
        assertThat(extensions).extracting(RuntimeExtension::name).doesNotContain("test-plugin");
    }
}
