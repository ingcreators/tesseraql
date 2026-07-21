package io.tesseraql.cli.modules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The module→DriverManager bridge (docs/duckdb.md): a JDBC driver that exists only on the module
 * classloader — compiled into a temp directory here, exactly as invisible to the base classpath as
 * a {@code work/modules} jar — becomes reachable through {@link ModuleDrivers#register}.
 */
class ModuleDriversTest {

    private static final String DRIVER_SOURCE = """
            import java.sql.*;
            import java.util.Properties;
            import java.util.logging.Logger;

            public class FakeModuleDriver implements Driver {
                public Connection connect(String url, Properties info) throws SQLException {
                    if (!acceptsURL(url)) {
                        return null;
                    }
                    return (Connection) java.lang.reflect.Proxy.newProxyInstance(
                            getClass().getClassLoader(), new Class<?>[] {Connection.class},
                            (proxy, method, args) -> switch (method.getName()) {
                                case "isClosed" -> false;
                                case "close" -> null;
                                case "toString" -> "fake-module-connection";
                                default -> null;
                            });
                }

                public boolean acceptsURL(String url) {
                    return url != null && url.startsWith("jdbc:fakemodule:");
                }

                public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
                    return new DriverPropertyInfo[0];
                }

                public int getMajorVersion() {
                    return 1;
                }

                public int getMinorVersion() {
                    return 0;
                }

                public boolean jdbcCompliant() {
                    return false;
                }

                public Logger getParentLogger() {
                    return Logger.getGlobal();
                }
            }
            """;

    @Test
    void registersAModuleOnlyDriverWithDriverManager(@TempDir Path dir) throws Exception {
        Path source = dir.resolve("FakeModuleDriver.java");
        Files.writeString(source, DRIVER_SOURCE);
        int compiled = ToolProvider.getSystemJavaCompiler()
                .run(null, null, null, "-d", dir.toString(), source.toString());
        assertThat(compiled).isZero();
        Path services = dir.resolve("META-INF/services");
        Files.createDirectories(services);
        Files.writeString(services.resolve("java.sql.Driver"), "FakeModuleDriver\n");

        // Invisible before the bridge: the driver class lives only in the module directory.
        assertThatThrownBy(() -> DriverManager.getConnection("jdbc:fakemodule:x"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("No suitable driver");

        try (URLClassLoader moduleLoader = new URLClassLoader(
                new java.net.URL[]{dir.toUri().toURL()},
                ModuleDriversTest.class.getClassLoader())) {
            ModuleDrivers.register(moduleLoader);
            // Registration is idempotent per driver class.
            ModuleDrivers.register(moduleLoader);

            try (Connection connection = DriverManager.getConnection("jdbc:fakemodule:x")) {
                assertThat(connection.toString()).isEqualTo("fake-module-connection");
            }
        }
    }
}
