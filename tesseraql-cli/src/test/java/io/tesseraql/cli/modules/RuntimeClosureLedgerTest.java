package io.tesseraql.cli.modules;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.apptasks.RuntimeClosure;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The ledger the runtime's build wrote is the runtime's closure and nothing else
 * (docs/module-channel.md decision 9). Read here from the classpath, exactly as the resolver
 * reads it, and checked in both directions against the classpath this process runs on: every
 * third-party line names an artifact whose jar is present — a ledger that named a dependency
 * the runtime does not have would exclude a jar a module needs — and the ledger names neither
 * what only the developer CLI carries (picocli, the resolver stack) nor what only a module
 * carries ({@code commons-logging}, which PDFBox brings and the host does not have).
 *
 * <p>By artifact, not by version: the version a closure carries is mediated by the graph it is
 * resolved in, so the CLI's {@code commons-codec} is 1.21.0 where the runtime's own resolution
 * says 1.19.0, and the host's {@code org.jetbrains:annotations} is 13.0 to the runtime's 17.0.0.
 * The exclusion ignores versions for the same reason, and the ledger's are the runtime's own.
 */
class RuntimeClosureLedgerTest {

    @Test
    void theLedgerIsTheRuntimesClosureAndNotThisProcessClasspath() {
        RuntimeClosure closure = RuntimeClosure.fromClasspath();

        assertThat(closure.artifacts())
                .contains("org.thymeleaf:thymeleaf", "org.slf4j:slf4j-api",
                        "io.tesseraql:tesseraql-core", "org.postgresql:postgresql")
                .doesNotContain("info.picocli:picocli",
                        "org.jboss.shrinkwrap.resolver:shrinkwrap-resolver-api",
                        "io.zonky.test:embedded-postgres", "org.slf4j:jcl-over-slf4j",
                        "commons-logging:commons-logging", "org.duckdb:duckdb_jdbc",
                        "io.tesseraql:tesseraql-pdf", "io.tesseraql:tesseraql-studio");

        List<String> classpath = List.of(System.getProperty("java.class.path")
                .split(File.pathSeparator));
        for (String artifact : closure.artifacts()) {
            if (artifact.startsWith("io.tesseraql:")) {
                continue; // reactor siblings are target/classes directories, not jars
            }
            String jar = artifact.substring(artifact.indexOf(':') + 1) + "-";
            assertThat(classpath)
                    .as("the ledger names %s, so %s<version>.jar must be on this process's"
                            + " classpath", artifact, jar)
                    .anySatisfy(entry -> assertThat(Path.of(entry).getFileName().toString())
                            .matches(java.util.regex.Pattern.quote(jar) + "\\d.*\\.jar"));
        }
    }
}
