package io.tesseraql.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliModulesTest {

    @Test
    void namesNoJarsWhenThereIsNoModulesDirectory() {
        // jars() is pinned directly: its null and missing-directory guards have no production
        // caller of their own, and classLoaderOver returns at its urls.isEmpty() check before
        // reaching them when the list is empty.
        assertThat(CliModules.jars(null)).isEmpty();
        assertThat(CliModules.jars(new File("no-such-dir"))).isEmpty();
    }

    @Test
    void returnsTheParentWhenNoDirectoryHoldsJars() {
        ClassLoader parent = getClass().getClassLoader();

        assertThat(CliModules.classLoaderOver(List.of(), parent)).isSameAs(parent);
        assertThat(CliModules.classLoaderOver(List.of(new File("no-such-dir")), parent))
                .isSameAs(parent);
    }

    @Test
    void buildsAChildLoaderOverTheJarsInTheDirectory(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("a.jar"), "");
        Files.writeString(dir.resolve("b.jar"), "");
        Files.writeString(dir.resolve("notes.txt"), "");

        ClassLoader parent = getClass().getClassLoader();
        ClassLoader loader = CliModules.classLoaderOver(List.of(dir.toFile()), parent);

        assertThat(loader).isInstanceOf(URLClassLoader.class);
        assertThat(loader.getParent()).isSameAs(parent);
        assertThat(((URLClassLoader) loader).getURLs()).extracting(java.net.URL::getPath)
                .anySatisfy(path -> assertThat(path).endsWith("/a.jar"))
                .anySatisfy(path -> assertThat(path).endsWith("/b.jar"))
                .noneMatch(path -> path.endsWith(".txt"));
    }

    @Test
    void composesEveryDirectoryThatHoldsJars(@TempDir Path cache, @TempDir Path explicit)
            throws Exception {
        Files.writeString(cache.resolve("resolved.jar"), "");
        Files.writeString(explicit.resolve("extra.jar"), "");

        ClassLoader parent = getClass().getClassLoader();
        ClassLoader loader = CliModules.classLoaderOver(
                List.of(cache.toFile(), explicit.toFile()), parent);

        // Order is load-bearing: the resolved cache first, an explicit --modules directory
        // second, so a developer's override shadows nothing it was not asked to.
        assertThat(((URLClassLoader) loader).getURLs()).extracting(java.net.URL::getPath)
                .anySatisfy(path -> assertThat(path).endsWith("/resolved.jar"))
                .anySatisfy(path -> assertThat(path).endsWith("/extra.jar"));
    }

    @Test
    void returnsTheParentWhenTheDirectoryHasNoJars(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("readme.txt"), "");
        ClassLoader parent = getClass().getClassLoader();

        assertThat(CliModules.classLoaderOver(List.of(dir.toFile()), parent)).isSameAs(parent);
    }
}
