package io.tesseraql.runtime;

import com.zaxxer.hikari.HikariDataSource;
import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.compiler.RouteCompiler;
import io.tesseraql.security.SecurityConfig;
import io.tesseraql.security.jwt.JwtAuthenticator;
import io.tesseraql.security.policy.PolicyEngine;
import io.tesseraql.security.session.SessionStore;
import io.tesseraql.operations.batch.JobExecution;
import io.tesseraql.operations.batch.JobExecutor;
import io.tesseraql.operations.batch.JobRepository;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.JobFile;
import io.tesseraql.yaml.manifest.ManifestLoader;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
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
    private final JobRepository jobRepository;
    private final JobExecutor jobExecutor;
    private final Map<String, JobFile> jobs;
    private final String appName;

    private TesseraqlRuntime(CamelContext camelContext, HikariDataSource mainDataSource, int port,
            JobRepository jobRepository, JobExecutor jobExecutor, Map<String, JobFile> jobs,
            String appName) {
        this.camelContext = camelContext;
        this.mainDataSource = mainDataSource;
        this.port = port;
        this.jobRepository = jobRepository;
        this.jobExecutor = jobExecutor;
        this.jobs = jobs;
        this.appName = appName;
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

        SecurityConfig security = SecurityConfigFactory.build(manifest.config());
        context.getRegistry().bind(TesseraqlProperties.POLICY_ENGINE_BEAN, new PolicyEngine(security));
        if (security.jwt() != null) {
            context.getRegistry().bind(
                    TesseraqlProperties.JWT_AUTHENTICATOR_BEAN, new JwtAuthenticator(security.jwt()));
        }
        context.getRegistry().bind(TesseraqlProperties.SESSION_STORE_BEAN, new SessionStore());

        VertxPlatformHttpServerConfiguration httpConfig = new VertxPlatformHttpServerConfiguration();
        httpConfig.setBindHost("0.0.0.0");
        httpConfig.setBindPort(port);

        JobRepository jobRepository = new JobRepository(dataSource);
        jobRepository.ensureSchema();
        JobExecutor jobExecutor = new JobExecutor(jobRepository);
        Map<String, JobFile> jobs = new LinkedHashMap<>();
        manifest.jobs().forEach(job -> jobs.put(job.definition().id(), job));
        String appName = manifest.config().getString("tesseraql.app.name").orElse("app");

        try {
            context.addService(new VertxPlatformHttpServer(httpConfig));
            context.addRoutes(new RouteCompiler().compile(manifest));
            context.start();
        } catch (Exception ex) {
            dataSource.close();
            throw new IllegalStateException("Failed to start TesseraQL runtime", ex);
        }
        LOG.info("TesseraQL runtime started on port {} for app {}", port, appHome);
        return new TesseraqlRuntime(context, dataSource, port, jobRepository, jobExecutor, jobs, appName);
    }

    /** Runs a batch job by id and returns its final execution record (design ch. 26). */
    public JobExecution runJob(String jobId, Map<String, Object> params) {
        JobFile jobFile = jobs.get(jobId);
        if (jobFile == null) {
            throw new IllegalArgumentException("Unknown job: " + jobId);
        }
        return jobExecutor.run(jobFile, mainDataSource, appName, params, "manual");
    }

    public JobRepository jobRepository() {
        return jobRepository;
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
