package io.tesseraql.core.service;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The registry of named {@link ServiceProvider}s available to routes (design ch. 47). The runtime
 * and its extensions register providers before the Camel context starts; the
 * {@code tesseraql-service} component resolves them by name at request time.
 */
public final class ServiceProviders {

    private static final TqlErrorCode UNKNOWN = new TqlErrorCode(TqlDomain.YAML, 1207);
    private static final TqlErrorCode DUPLICATE = new TqlErrorCode(TqlDomain.YAML, 1208);

    private final Map<String, ServiceProvider> providers = new ConcurrentHashMap<>();

    /** Registers a provider under a unique name (e.g. {@code ops.overview}). */
    public ServiceProviders register(String name, ServiceProvider provider) {
        if (providers.putIfAbsent(name, provider) != null) {
            throw new TqlException(DUPLICATE, "Service provider already registered: " + name);
        }
        return this;
    }

    /** The provider for {@code name}, or a {@link TqlException} when unknown. */
    public ServiceProvider require(String name) {
        ServiceProvider provider = providers.get(name);
        if (provider == null) {
            throw new TqlException(UNKNOWN, "No service provider named '" + name + "'");
        }
        return provider;
    }
}
