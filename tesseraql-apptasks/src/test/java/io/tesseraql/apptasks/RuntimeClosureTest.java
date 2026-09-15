package io.tesseraql.apptasks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The runtime closure ledger is read as the {@code dependency:list} output it is
 * (docs/module-channel.md decision 9), and anything that is not that output is refused rather
 * than skipped: an exclusion set that silently lost lines would resolve jars the runtime's loader
 * then shadows, which is the defect the ledger exists to end. This module's own test classpath
 * carries no runtime, so it is also where "no ledger" is proven to be a refusal and not an empty
 * set.
 */
class RuntimeClosureTest {

    private static final String LEDGER = """

            The following files have been resolved:
               com.google.zxing:core:jar:3.5.4:compile -- module com.google.zxing [auto]
               org.javassist:javassist:jar:3.29.0-GA:compile -- module javassist (auto)
               org.slf4j:slf4j-api:jar:2.0.18:compile -- module org.slf4j
               org.thymeleaf:thymeleaf:jar:3.1.5.RELEASE:compile -- module thymeleaf [auto]
               io.example:natives:jar:linux-amd64:1.0.0:runtime
               io.tesseraql:tesseraql-core:jar:0.17.0-SNAPSHOT:compile

            """;

    @Test
    void readsEveryEntryAsGroupArtifactAtItsVersion() {
        RuntimeClosure closure = RuntimeClosure.parse(LEDGER);

        assertThat(closure.artifacts()).containsExactly("com.google.zxing:core",
                "io.example:natives", "io.tesseraql:tesseraql-core", "org.javassist:javassist",
                "org.slf4j:slf4j-api", "org.thymeleaf:thymeleaf");
        assertThat(closure.carries("org.thymeleaf:thymeleaf")).isTrue();
        assertThat(closure.carries("commons-logging:commons-logging")).isFalse();
        assertThat(closure.version("org.thymeleaf:thymeleaf")).contains("3.1.5.RELEASE");
        assertThat(closure.version("io.example:natives"))
                .as("a classifier does not shift the version")
                .contains("1.0.0");
        assertThat(closure.version("org.javassist:javassist")).contains("3.29.0-GA");
    }

    @Test
    void refusesWhatIsNotAResolvedCoordinate() {
        assertThatThrownBy(() -> RuntimeClosure.parse("""
                The following files have been resolved:
                   org.slf4j:slf4j-api:jar:2.0.18:compile
                The following files have NOT been resolved:
                   org.thymeleaf:thymeleaf:jar:3.1.5.RELEASE:compile
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside its resolved section")
                .hasMessageContaining("org.thymeleaf:thymeleaf");
        assertThatThrownBy(() -> RuntimeClosure.parse("""
                The following files have been resolved:
                   org.slf4j:slf4j-api 2.0.18
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not a group:artifact:type[:classifier]:version:scope");
        assertThatThrownBy(() -> RuntimeClosure.parse("""
                The following files have been resolved:
                   none
                """))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> RuntimeClosure.parse("The following files have been resolved:\n"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("names no artifact");
    }

    @Test
    void aClasspathWithoutTheLedgerIsRefusedNotEmpty() {
        assertThat(RuntimeClosureTest.class.getClassLoader().getResource(RuntimeClosure.RESOURCE))
                .as("this module's test classpath carries no tesseraql-runtime")
                .isNull();

        assertThatThrownBy(RuntimeClosure::fromClasspath)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(RuntimeClosure.RESOURCE)
                .hasMessageContaining("-pl tesseraql-runtime -am install");
    }

    @Test
    void readsTheLedgerOutOfARuntimeJarAndRefusesAJarWithoutOne(@TempDir Path dir)
            throws Exception {
        Path withLedger = dir.resolve("tesseraql-runtime-1.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(withLedger))) {
            jar.putNextEntry(new JarEntry(RuntimeClosure.RESOURCE));
            jar.write(LEDGER.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        Path without = dir.resolve("tesseraql-runtime-0.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(without))) {
            jar.putNextEntry(new JarEntry("META-INF/MANIFEST.MF"));
            jar.closeEntry();
        }

        assertThat(RuntimeClosure.fromJar(withLedger).artifacts())
                .contains("org.thymeleaf:thymeleaf");
        assertThatThrownBy(() -> RuntimeClosure.fromJar(without))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("carries no " + RuntimeClosure.RESOURCE);
    }
}
