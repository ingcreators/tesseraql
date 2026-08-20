package io.tesseraql.yaml.blob;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.blob.BlobStore;
import io.tesseraql.yaml.config.AppConfig;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Where a blob-store provider is discovered from (docs/module-scope.md structural decision 2).
 *
 * <p>An application declares its object store in {@code tesseraql.modules}, and those jars load on
 * that application's own classloader. Discovery read the thread context classloader instead, which
 * in a hosted runtime is the process classpath — so the module channel could not serve a blob store
 * at all, and an application that declared one was told its provider was missing. The loader is an
 * argument now, and these tests pin both directions.
 */
class BlobStoresLoaderTest {

    /**
     * A loader that publishes one service file and delegates class loading to its parent — enough
     * for {@link java.util.ServiceLoader} to find a provider that is invisible to the classpath,
     * without compiling a jar into the test tree.
     */
    private static final class ModuleLoader extends ClassLoader {

        private final URL serviceFile;

        ModuleLoader(URL serviceFile) {
            super(BlobStoresLoaderTest.class.getClassLoader());
            this.serviceFile = serviceFile;
        }

        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            if (("META-INF/services/" + BlobStoreProvider.class.getName()).equals(name)) {
                return Collections.enumeration(java.util.List.of(serviceFile));
            }
            return super.getResources(name);
        }
    }

    /** A provider only the module loader can announce. */
    public static final class ModuleProvider implements BlobStoreProvider {

        @Override
        public String provider() {
            return "module-only";
        }

        @Override
        public BlobStore create(AppConfig config, Path appHome) {
            return new io.tesseraql.core.blob.FileBlobStore(appHome);
        }
    }

    @Test
    void aProviderOnTheModuleLoaderIsFound(@TempDir Path dir) throws IOException {
        BlobStore store = BlobStores.create(configFor("module-only"), dir, moduleLoader(dir));

        assertThat(store).isInstanceOf(io.tesseraql.core.blob.FileBlobStore.class);
    }

    /** The same configuration without the loader is the failure this fixes. */
    @Test
    void theSameProviderIsInvisibleWithoutTheLoader(@TempDir Path dir) {
        assertThatThrownBy(() -> BlobStores.create(configFor("module-only"), dir))
                .isInstanceOf(io.tesseraql.core.error.TqlException.class)
                .hasMessageContaining("TQL-YAML-1108");
    }

    /** The default store needs no provider at all, with or without a loader. */
    @Test
    void theFileStoreIsStillTheDefault(@TempDir Path dir) {
        assertThat(BlobStores.create(new AppConfig(Map.of()), dir, null))
                .isInstanceOf(io.tesseraql.core.blob.FileBlobStore.class);
    }

    private static AppConfig configFor(String provider) {
        return new AppConfig(Map.of("tesseraql",
                Map.of("object-storage", Map.of("provider", provider))));
    }

    private static ClassLoader moduleLoader(Path dir) throws IOException {
        Path services = dir.resolve("services");
        Files.createDirectories(services);
        Path file = services.resolve(BlobStoreProvider.class.getName());
        Files.writeString(file, ModuleProvider.class.getName() + "\n");
        return new ModuleLoader(file.toUri().toURL());
    }
}
