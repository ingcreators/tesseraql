package io.tesseraql.cli.modules;

import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Makes JDBC drivers that arrive as module jars visible to {@link DriverManager}. DriverManager
 * only hands a driver to callers that can see its class through their own classloader chain, and
 * module jars live on a child classloader the base classpath cannot see — so every pool built from
 * a plain {@code jdbcUrl} (Hikari in the runtime, {@code DriverManagerDataSource} in the authoring
 * tools) would miss them. Wrapping each module driver in a {@link DriverShim} closes the gap: the
 * shim's class lives on the base classpath, so DriverManager's visibility check passes, and the
 * shim delegates to the module-loaded driver instance.
 */
public final class ModuleDrivers {

    private static final Set<String> REGISTERED = ConcurrentHashMap.newKeySet();

    private ModuleDrivers() {
    }

    /**
     * Registers every JDBC driver the module classloader provides beyond the base classpath.
     * Idempotent per driver class; a provider that fails to load is skipped so one broken module
     * jar cannot take down the others.
     */
    public static void register(ClassLoader moduleLoader) {
        ClassLoader base = ModuleDrivers.class.getClassLoader();
        if (moduleLoader == null || moduleLoader == base) {
            return;
        }
        for (ServiceLoader.Provider<Driver> provider : (Iterable<ServiceLoader.Provider<Driver>>) ServiceLoader
                .load(Driver.class, moduleLoader).stream()::iterator) {
            try {
                Driver driver = provider.get();
                if (driver.getClass().getClassLoader() == base
                        || !REGISTERED.add(driver.getClass().getName())) {
                    continue;
                }
                DriverManager.registerDriver(new DriverShim(driver));
            } catch (SQLException | RuntimeException | ServiceConfigurationError skipped) {
                // a driver the module set cannot load is simply not registered
            }
        }
    }
}
