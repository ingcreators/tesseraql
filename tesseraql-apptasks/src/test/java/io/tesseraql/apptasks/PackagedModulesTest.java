package io.tesseraql.apptasks;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A lock that names an artifact the runtime carries was written by a resolver without the
 * runtime's closure ledger (docs/module-channel.md decision 9), and both packaging routes refuse
 * it with one sentence — naming the artifacts and the command that rewrites the lock — before
 * either fetches or resolves anything.
 */
class PackagedModulesTest {

    private static final RuntimeClosure RUNTIME = RuntimeClosure.parse("""
            The following files have been resolved:
               org.slf4j:slf4j-api:jar:2.0.18:compile -- module org.slf4j
               org.thymeleaf:thymeleaf:jar:3.1.5.RELEASE:compile -- module thymeleaf [auto]
            """);

    @Test
    void aLockNamingWhatTheRuntimeCarriesIsRefusedNamingIt(@TempDir Path dir) throws Exception {
        Path appHome = dir.resolve("app");
        Files.createDirectories(appHome);
        Path lock = appHome.resolve("modules.lock");
        Files.writeString(lock, """
                {"artifacts":[
                  {"coordinate":"org.apache.pdfbox:pdfbox:3.0.8","sha256":"aa"},
                  {"coordinate":"org.slf4j:slf4j-api:2.0.18","sha256":"bb"},
                  {"coordinate":"org.thymeleaf:thymeleaf:3.1.5.RELEASE","sha256":"cc"}]}
                """);

        assertThatThrownBy(() -> PackagedModules.requireNothingTheRuntimeCarries(appHome, lock,
                RUNTIME))
                .isInstanceOf(io.tesseraql.core.error.TqlException.class)
                .hasMessageContaining("TQL-APP-4219")
                .hasMessageContaining("2 artifact(s) the runtime already carries")
                .hasMessageContaining(
                        "org.slf4j:slf4j-api:2.0.18, org.thymeleaf:thymeleaf:3.1.5.RELEASE")
                .hasMessageNotContaining("pdfbox")
                .hasMessageContaining("tesseraql modules resolve --app " + appHome);

        // The same lock without them passes; the version the lock pins does not matter.
        Files.writeString(lock, """
                {"artifacts":[{"coordinate":"org.apache.pdfbox:pdfbox:3.0.8","sha256":"aa"}]}
                """);
        PackagedModules.requireNothingTheRuntimeCarries(appHome, lock, RUNTIME);
    }
}
