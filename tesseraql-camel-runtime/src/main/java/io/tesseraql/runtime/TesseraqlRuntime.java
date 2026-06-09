package io.tesseraql.runtime;

import com.zaxxer.hikari.HikariDataSource;
import io.tesseraql.compiler.RouteCompiler;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestLoader;
import java.nio.file.Path;
import org.apache.camel.CamelContext;
import org.apache.camel.component.platform.http.vertx.VertxPlatformHttpServer;
import org.apache.camel.component.platform.http.vertx.VertxPlatformHttpServerConfiguration;
import org.apache.camel.impl.DefaultCamelContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Camel Main based TesseraQL runtime (design ch. 19.2).
 *
 * <p>Loads an external app home, wires datasources and the embedded HTTP server, compiles the
 * Simple YAML routes into Camel routes, and starts the context. The {@code tesseraql-sql} component
 * is discovered from the classpath service descriptor.
 */
public final class TesseraqlRuntime implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(TesseraqlRuntime.class);

    private final CamelContext camelContext;
    private final HikariDataSource mainDataSource;
    private final int port;

    private TesseraqlRuntime(CamelContext camelContext, HikariDataSource mainDataSource, int port) {
        this.camelContext = camelContext;
        this.mainDataSource = mainDataSource;
        this.port = port;
    }

    /** Starts the runtime against {@code appHome}, using the configured {@code server.port}. */
    public static TesseraqlRuntime start(Path appHome) {
        AppManifest manifest = new ManifestLoader().load(appHome);
        int port = manifest.config().getString("server.port").map(Integer::parseInt).orElse(8080);
        return start(appHome, manifest, port);
    }

    /** Starts the runtime against {@code appHome} on an explicit port (used by tests). */
    public static TesseraqlRuntime start(Path appHome, int port) {
        return start(appHome, new ManifestLoader().load(appHome), port);
    }

    private static TesseraqlRuntime start(Path appHome, AppManifest manifest, int port) {
        DefaultCamelContext context = new DefaultCamelContext();
        HikariDataSource dataSource = DataSources.create(manifest.config(), "main");
        context.getRegistry().bind("main", dataSource);

        VertxPlatformHttpServerConfiguration httpConfig = new VertxPlatformHttpServerConfiguration();
        httpConfig.setBindHost("0.0.0.0");
        httpConfig.setBindPort(port);

        try {
            context.addService(new VertxPlatformHttpServer(httpConfig));
            context.addRoutes(new RouteCompiler().compile(manifest));
            context.start();
        } catch (Exception ex) {
            dataSource.close();
            throw new IllegalStateException("Failed to start TesseraQL runtime", ex);
        }
        LOG.info("TesseraQL runtime started on port {} for app {}", port, appHome);
        return new TesseraqlRuntime(context, dataSource, port);
    }

    public int port() {
        return port;
    }

    public CamelContext camelContext() {
        return camelContext;
    }

    @Override
    public void close() {
        try {
            camelContext.stop();
        } finally {
            mainDataSource.close();
        }
    }
}
