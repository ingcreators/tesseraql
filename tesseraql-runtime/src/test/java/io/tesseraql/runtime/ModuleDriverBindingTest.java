package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Driver;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Decision 28's driver seam (docs/module-scope.md): a driver defined by the application's module
 * loader binds to the pool directly, so {@code DriverManager} — JVM-global, first-wins per URL —
 * leaves the load-bearing path and two applications can carry the same driver at different
 * versions. The stub driver is compiled at test time into a jar, because the property under test
 * is the <em>defining</em> loader: a class the test classpath already holds would be defined by
 * the parent and prove nothing.
 */
class ModuleDriverBindingTest {

    private static final String STUB_SOURCE = """
            package stubdrv;

            public class StubDriver implements java.sql.Driver {
                public java.sql.Connection connect(String url, java.util.Properties info) {
                    if (!acceptsURL(url)) {
                        return null;
                    }
                    return (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(
                            getClass().getClassLoader(),
                            new Class<?>[]{java.sql.Connection.class},
                            (proxy, method, args) -> switch (method.getName()) {
                                case "isValid", "getAutoCommit" -> true;
                                case "isClosed", "isReadOnly" -> false;
                                case "getTransactionIsolation" ->
                                    java.sql.Connection.TRANSACTION_READ_COMMITTED;
                                case "getNetworkTimeout", "getHoldability" -> 0;
                                case "toString" -> "stub-connection:" + info.getProperty("user");
                                case "hashCode" -> System.identityHashCode(proxy);
                                case "equals" -> proxy == args[0];
                                default -> {
                                    Class<?> r = method.getReturnType();
                                    if (r == boolean.class) yield false;
                                    if (r == int.class) yield 0;
                                    if (r == long.class) yield 0L;
                                    yield null;
                                }
                            });
                }
                public boolean acceptsURL(String url) {
                    return url != null && url.startsWith("jdbc:stub:");
                }
                public java.sql.DriverPropertyInfo[] getPropertyInfo(String u,
                        java.util.Properties p) {
                    return new java.sql.DriverPropertyInfo[0];
                }
                public int getMajorVersion() {
                    return %d;
                }
                public int getMinorVersion() {
                    return 0;
                }
                public boolean jdbcCompliant() {
                    return false;
                }
                public java.util.logging.Logger getParentLogger() {
                    return java.util.logging.Logger.getLogger("stubdrv");
                }
            }
            """;

    @Test
    void aModuleDefinedDriverIsSelectedAndConnects(@TempDir Path dir) throws Exception {
        ClassLoader loader = moduleLoader(dir.resolve("m1"), 1);

        Driver driver = DataSources.moduleDriver("jdbc:stub:mem", loader);
        assertThat(driver).isNotNull();
        // The defining loader is the module's, not the test classpath.
        assertThat(driver.getClass().getClassLoader()).isSameAs(loader);

        java.util.Properties properties = new java.util.Properties();
        properties.setProperty("user", "app");
        try (java.sql.Connection connection = new DriverBackedDataSource(driver,
                "jdbc:stub:mem", properties).getConnection()) {
            // The stub's connection carries the forwarded properties — what Hikari's own
            // DriverManager path would have passed.
            assertThat(connection.toString()).isEqualTo("stub-connection:app");
        }
    }

    /**
     * A driver merely <em>visible</em> through the parent (the base classpath's PostgreSQL)
     * never binds here: base drivers keep today's {@code DriverManager} path, and the module
     * seam engages only for classes the module loader itself defines.
     */
    @Test
    void aBaseClasspathDriverNeverBindsThroughTheModuleSeam(@TempDir Path dir) throws Exception {
        ClassLoader loader = moduleLoader(dir.resolve("m1"), 1);

        assertThat(DataSources.moduleDriver("jdbc:postgresql://db:5432/one", loader)).isNull();
    }

    /** Two loaders, one driver class name, two versions: each pool answers with its own. */
    @Test
    void twoApplicationsCarryTheSameDriverAtDifferentVersions(@TempDir Path dir)
            throws Exception {
        Driver one = DataSources.moduleDriver("jdbc:stub:a", moduleLoader(dir.resolve("m1"), 1));
        Driver two = DataSources.moduleDriver("jdbc:stub:b", moduleLoader(dir.resolve("m2"), 2));

        assertThat(one.getMajorVersion()).isEqualTo(1);
        assertThat(two.getMajorVersion()).isEqualTo(2);
        assertThat(one.getClass().getName()).isEqualTo(two.getClass().getName());
        assertThat(one.getClass()).isNotSameAs(two.getClass());
    }

    /** Compiles the stub driver at {@code version} into a jar and loads it as a module would be. */
    private static ClassLoader moduleLoader(Path dir, int version) throws Exception {
        Path sources = dir.resolve("src/stubdrv");
        Files.createDirectories(sources);
        Path source = sources.resolve("StubDriver.java");
        Files.writeString(source, STUB_SOURCE.formatted(version));
        Path classes = dir.resolve("classes");
        Files.createDirectories(classes);
        int rc = javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-d", classes.toString(), source.toString());
        assertThat(rc).isZero();

        Path jar = dir.resolve("driver.jar");
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(
                Files.newOutputStream(jar)); Stream<Path> files = Files.walk(classes)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                zip.putNextEntry(new java.util.zip.ZipEntry(
                        classes.relativize(file).toString().replace('\\', '/')));
                zip.write(Files.readAllBytes(file));
                zip.closeEntry();
            }
            zip.putNextEntry(new java.util.zip.ZipEntry("META-INF/services/java.sql.Driver"));
            zip.write("stubdrv.StubDriver\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return new URLClassLoader(new URL[]{jar.toUri().toURL()},
                ModuleDriverBindingTest.class.getClassLoader());
    }
}
