package io.tesseraql.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliModulesTest {

    @Test
    void returnsTheParentWhenThereIsNoModulesDirectory() {
        ClassLoader parent = getClass().getClassLoader();

        assertThat(CliModules.classLoader(null, parent)).isSameAs(parent);
        assertThat(CliModules.classLoader(new File("no-such-dir"), parent)).isSameAs(parent);
    }

    @Test
    void buildsAChildLoaderOverTheJarsInTheDirectory(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("a.jar"), "");
        Files.writeString(dir.resolve("b.jar"), "");
        Files.writeString(dir.resolve("notes.txt"), "");

        ClassLoader parent = getClass().getClassLoader();
        ClassLoader loader = CliModules.classLoader(dir.toFile(), parent);

        assertThat(loader).isInstanceOf(URLClassLoader.class);
        assertThat(loader.getParent()).isSameAs(parent);
        assertThat(((URLClassLoader) loader).getURLs()).extracting(java.net.URL::getPath)
                .anySatisfy(path -> assertThat(path).endsWith("/a.jar"))
                .anySatisfy(path -> assertThat(path).endsWith("/b.jar"))
                .noneMatch(path -> path.endsWith(".txt"));
    }

    @Test
    void returnsTheParentWhenTheDirectoryHasNoJars(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("readme.txt"), "");
        ClassLoader parent = getClass().getClassLoader();

        assertThat(CliModules.classLoader(dir.toFile(), parent)).isSameAs(parent);
    }
}
