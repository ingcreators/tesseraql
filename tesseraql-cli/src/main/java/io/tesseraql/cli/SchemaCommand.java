package io.tesseraql.cli;

import io.tesseraql.report.DriverManagerDataSource;
import io.tesseraql.report.docs.SchemaDoc;
import io.tesseraql.report.docs.SchemaGenerator;
import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.manifest.ManifestLoader;
import io.tesseraql.yaml.scaffold.CatalogSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * {@code tesseraql schema --app <dir>}: introspects the migrated database catalog and writes the
 * documentation-portal schema overlay {@code .tesseraql/docs/schema.json} (documentation portal v3)
 * — the CLI-native form of the {@code tesseraql:schema} goal. Like the report overlay it is
 * run-dependent and is not packaged into the reproducible {@code .tqlapp}.
 */
@Command(name = "schema", description = "Introspect the database and write the schema overlay.")
final class SchemaCommand implements Callable<Integer> {

    @Option(names = {"--app"}, required = true, description = "Path to the external app home.")
    Path app;

    @Mixin
    CliDatasource datasource;

    @Option(names = {
            "--datasource"}, description = "Datasource name the catalog is keyed by (default: main).")
    String datasourceName = "main";

    @Override
    public Integer call() throws Exception {
        AppConfig config = new ManifestLoader().load(app).config();
        DriverManagerDataSource dataSource = datasource.resolve(config);
        SchemaGenerator generator = new SchemaGenerator();
        SchemaDoc schema = generator.generate(Map.of(datasourceName, dataSource),
                Instant.now().toString());

        Path docsDir = app.resolve(".tesseraql").resolve("docs");
        Files.createDirectories(docsDir);
        Files.writeString(docsDir.resolve("schema.json"), generator.toJson(schema));

        CatalogSchema catalog = schema.datasources().get(datasourceName);
        System.out.println(String.format(
                "TesseraQL schema: introspected %d tables from datasource '%s'",
                catalog.tables().size(), datasourceName));
        System.out.println("Wrote " + docsDir.resolve("schema.json"));
        return 0;
    }
}
