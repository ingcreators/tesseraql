package io.tesseraql.yaml.secret;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * The registry of {@link SecretResolver} providers backing {@code ${secret.<provider>.<name>}}
 * placeholders (design ch. 41): the built-in {@code env} and {@code file} providers plus any
 * discovered via {@link ServiceLoader} (a discovered provider may override a built-in id).
 */
public final class SecretResolvers {

    private static final TqlErrorCode UNKNOWN_PROVIDER = new TqlErrorCode(TqlDomain.YAML, 1209);

    private final Map<String, SecretResolver> providers;

    private SecretResolvers(Map<String, SecretResolver> providers) {
        this.providers = Map.copyOf(providers);
    }

    /** Built-ins plus every ServiceLoader-discovered provider. */
    public static SecretResolvers discover() {
        Map<String, SecretResolver> providers = new LinkedHashMap<>();
        providers.put("env", new EnvSecretResolver());
        providers.put("file", new FileSecretResolver());
        for (SecretResolver resolver : ServiceLoader.load(SecretResolver.class)) {
            providers.put(resolver.provider(), resolver);
        }
        return new SecretResolvers(providers);
    }

    /** A registry of exactly the given providers (used in tests and embedded setups). */
    public static SecretResolvers of(SecretResolver... resolvers) {
        Map<String, SecretResolver> providers = new LinkedHashMap<>();
        for (SecretResolver resolver : resolvers) {
            providers.put(resolver.provider(), resolver);
        }
        return new SecretResolvers(providers);
    }

    /**
     * Resolves {@code providerAndName} ({@code env.DB_PASSWORD}, {@code file.db_password}):
     * {@code null} when the provider has no such secret; unknown providers fail loudly so a typo
     * never silently falls back to a default.
     */
    public String resolve(String providerAndName) {
        int dot = providerAndName.indexOf('.');
        String provider = dot < 0 ? providerAndName : providerAndName.substring(0, dot);
        String name = dot < 0 ? "" : providerAndName.substring(dot + 1);
        SecretResolver resolver = providers.get(provider);
        if (resolver == null) {
            throw new TqlException(UNKNOWN_PROVIDER,
                    "Unknown secret provider '" + provider + "' in ${secret." + providerAndName + "}");
        }
        return name.isBlank() ? null : resolver.resolve(name);
    }
}
