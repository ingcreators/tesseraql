package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.compiler.ext.ExtensionContext;
import io.tesseraql.compiler.ext.RuntimeExtension;
import io.tesseraql.yaml.config.AppConfig;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Where a runtime extension is discovered from (docs/module-scope.md structural decision 2): the
 * classpath, the application's own module loader, and signature-verified plugin jars — three
 * sources, deduplicated by implementation class.
 *
 * <p>The module loader was the missing one. An extension declared in {@code tesseraql.modules} was
 * resolved onto that application's loader and then never looked for there, so the module channel
 * could not deliver an extension at all.
 */
class RuntimeExtensionsLoaderTest {

    /** A loader that publishes one service file and delegates class loading to its parent. */
    private static final class ModuleLoader extends ClassLoader {

        private final URL serviceFile;

        ModuleLoader(URL serviceFile) {
            super(RuntimeExtensionsLoaderTest.class.getClassLoader());
            this.serviceFile = serviceFile;
        }

        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            if (("META-INF/services/" + RuntimeExtension.class.getName()).equals(name)) {
                return Collections.enumeration(List.of(serviceFile));
            }
            return super.getResources(name);
        }
    }

    /** An extension only the module loader can announce. */
    public static final class ModuleExtension implements RuntimeExtension {

        @Override
        public String name() {
            return "module-only";
        }

        @Override
        public boolean enabled(AppConfig config) {
            return true;
        }

        @Override
        public void install(ExtensionContext context) {
            throw new UnsupportedOperationException("discovery is what is under test");
        }
    }

    @Test
    void anExtensionOnTheModuleLoaderIsDiscovered(@TempDir Path dir) throws IOException {
        List<RuntimeExtension> found = RuntimeExtensions.discover(new AppConfig(Map.of()), dir,
                moduleLoader(dir));

        assertThat(found).extracting(RuntimeExtension::name).contains("module-only");
    }

    /** The same set without the loader is the gap this closes. */
    @Test
    void theSameExtensionIsInvisibleWithoutTheLoader(@TempDir Path dir) {
        List<RuntimeExtension> found = RuntimeExtensions.discover(new AppConfig(Map.of()), dir);

        assertThat(found).extracting(RuntimeExtension::name).doesNotContain("module-only");
    }

    /**
     * The allowlist governs every source alike: an extension that arrives on the module loader is
     * gated exactly as a classpath or plugin one is.
     */
    @Test
    void theAllowlistGatesTheModuleLoaderToo(@TempDir Path dir) throws IOException {
        AppConfig locked = new AppConfig(Map.of("tesseraql",
                Map.of("plugins", Map.of("allowlist", List.of("something-else")))));

        List<RuntimeExtension> found = RuntimeExtensions.discover(locked, dir, moduleLoader(dir));

        assertThat(found).extracting(RuntimeExtension::name).doesNotContain("module-only");
    }

    private static ClassLoader moduleLoader(Path dir) throws IOException {
        Path services = dir.resolve("services");
        Files.createDirectories(services);
        Path file = services.resolve(RuntimeExtension.class.getName());
        Files.writeString(file, ModuleExtension.class.getName() + "\n");
        return new ModuleLoader(file.toUri().toURL());
    }
}
