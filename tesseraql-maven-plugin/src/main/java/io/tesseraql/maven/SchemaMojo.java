package io.tesseraql.maven;

import io.tesseraql.report.DriverManagerDataSource;
import io.tesseraql.report.docs.SchemaDoc;
import io.tesseraql.report.docs.SchemaGenerator;
import io.tesseraql.yaml.scaffold.CatalogSchema;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import javax.sql.DataSource;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Introspects the migrated database catalog and writes the documentation-portal schema overlay
 * (documentation portal v3): {@code schema.json} (every table and view, with columns, primary key,
 * foreign keys, and unique indexes per datasource) into the app home's reserved
 * {@code .tesseraql/docs/} namespace, where the runtime portal reads it.
 *
 * <p>Like the {@code report} goal the overlay is run-dependent — it reflects a live database — and is
 * deliberately <strong>not</strong> packaged into the reproducible {@code .tqlapp}:
 * {@code AppPackager} excludes the source {@code .tesseraql/} namespace, and this goal binds to
 * {@code integration-test} (after {@code migrate}, so the schema is fully applied). It needs only the
 * migrated database, not the test run, so it stays independent of the {@code report} goal.
 */
@Mojo(name = "schema", defaultPhase = LifecyclePhase.INTEGRATION_TEST, threadSafe = true)
public class SchemaMojo extends AbstractMojo {

    @Parameter(property = "tesseraql.appHome", required = true)
    private File appHome;

    @Parameter(property = "tesseraql.jdbcUrl", required = true)
    private String jdbcUrl;

    @Parameter(property = "tesseraql.username")
    private String username;

    @Parameter(property = "tesseraql.password")
    private String password;

    /** The datasource name the introspected catalog is keyed by in {@code schema.json}. */
    @Parameter(property = "tesseraql.datasource", defaultValue = "main")
    private String datasource;

    @Override
    public void execute() throws MojoExecutionException {
        DataSource dataSource = new DriverManagerDataSource(jdbcUrl, username, password);
        SchemaGenerator generator = new SchemaGenerator();
        SchemaDoc schema = generator.generate(Map.of(datasource, dataSource),
                Instant.now().toString());

        Path docsDir = appHome.toPath().resolve(".tesseraql").resolve("docs");
        try {
            Files.createDirectories(docsDir);
            Files.writeString(docsDir.resolve("schema.json"), generator.toJson(schema));
        } catch (IOException ex) {
            throw new MojoExecutionException("Failed to write schema overlay to " + docsDir, ex);
        }

        CatalogSchema catalog = schema.datasources().get(datasource);
        getLog().info(String.format("TesseraQL schema: introspected %d tables from datasource '%s'",
                catalog.tables().size(), datasource));
        getLog().info("Wrote " + docsDir.resolve("schema.json"));
    }
}
