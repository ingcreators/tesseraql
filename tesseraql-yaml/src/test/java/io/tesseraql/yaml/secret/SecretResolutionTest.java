package io.tesseraql.yaml.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.config.AppConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecretResolutionTest {

    @TempDir
    Path dir;

    private static SecretResolver fake(String provider, Map<String, String> values) {
        return new SecretResolver() {
            @Override
            public String provider() {
                return provider;
            }

            @Override
            public String resolve(String name) {
                return values.get(name);
            }
        };
    }

    private static AppConfig config(Map<String, Object> root, SecretResolvers secrets) {
        return new AppConfig(root, name -> null, secrets);
    }

    @Test
    void resolvesSecretPlaceholdersThroughProviders() {
        SecretResolvers secrets = SecretResolvers.of(fake("vault", Map.of("db/password", "s3cr3t")));
        AppConfig config = config(Map.of(
                "db", Map.of("password", "${secret.vault.db/password}")), secrets);

        assertThat(config.getString("db.password")).contains("s3cr3t");
    }

    @Test
    void secretValuesAreLiteralNotReexpanded() {
        // A secret containing ${...} must never be expanded again (injection guard).
        SecretResolvers secrets = SecretResolvers.of(fake("vault", Map.of("k", "${db.other}")));
        AppConfig config = config(Map.of("x", "${secret.vault.k}", "db", Map.of("other", "boom")),
                secrets);

        assertThat(config.getString("x")).contains("${db.other}");
    }

    @Test
    void missingSecretUsesFallbackOrFails() {
        SecretResolvers secrets = SecretResolvers.of(fake("vault", Map.of()));
        AppConfig withFallback = config(Map.of("x", "${secret.vault.nope:default-value}"), secrets);
        assertThat(withFallback.getString("x")).contains("default-value");

        AppConfig without = config(Map.of("x", "${secret.vault.nope}"), secrets);
        assertThatThrownBy(() -> without.getString("x"))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("Cannot resolve secret");
    }

    @Test
    void unknownProviderFailsLoudly() {
        AppConfig config = config(Map.of("x", "${secret.typo.name}"), SecretResolvers.of());

        assertThatThrownBy(() -> config.getString("x"))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("Unknown secret provider 'typo'");
    }

    @Test
    void fileProviderReadsTrimmedSecretFilesConfinedToDirectory() throws Exception {
        Files.writeString(dir.resolve("db_password"), "  hunter2\n");
        FileSecretResolver resolver = new FileSecretResolver(dir);

        assertThat(resolver.resolve("db_password")).isEqualTo("hunter2");
        assertThat(resolver.resolve("missing")).isNull();
        // Traversal and nested paths are rejected, not resolved.
        assertThat(resolver.resolve("../etc/passwd")).isNull();
        assertThat(resolver.resolve("sub/secret")).isNull();
    }

    @Test
    void discoverIncludesBuiltinsAndServiceLoaderProviders() {
        SecretResolvers secrets = SecretResolvers.discover();

        // env/file built-ins respond (with null for unknown names) instead of failing.
        assertThat(secrets.resolve("env.TQL_NO_SUCH_ENV_VAR")).isNull();
        // The test-registered provider (META-INF/services) is discovered.
        assertThat(secrets.resolve("test.fixed")).isEqualTo("from-test-provider");
    }
}
