package io.tesseraql.cli;

import io.tesseraql.report.docs.AppDocGenerator;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestLoader;
import io.tesseraql.yaml.openapi.HtmxContractGenerator;
import io.tesseraql.yaml.openapi.OpenApiGenerator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code tesseraql generate --app <dir>}: writes the deterministic OpenAPI document, the htmx server
 * contract and the documentation-portal {@code docs/spec.json} from the app manifest — the
 * CLI-native form of the {@code tesseraql:generate} goal (design ch. 22.18). The Simple YAML routes
 * stay the source of truth; these derivations are byte-stable.
 */
@Command(name = "generate", description = "Generate OpenAPI, the htmx contract, and the docs spec.")
final class GenerateCommand implements Callable<Integer> {

    @Option(names = {"--app"}, required = true, description = "Path to the external app home.")
    Path app;

    @Option(names = {"--out"}, description = "Output directory (default: <app>/work/generated).")
    Path out;

    @Override
    public Integer call() throws Exception {
        AppManifest manifest = new ManifestLoader().load(app);
        Path target = out != null ? out : app.resolve("work").resolve("generated");
        Files.createDirectories(target);
        Path openapi = target.resolve("openapi.json");
        Files.writeString(openapi, new OpenApiGenerator().toJson(manifest));
        Path htmx = target.resolve("htmx-contract.json");
        Files.writeString(htmx, new HtmxContractGenerator().toJson(manifest));
        // The documentation-portal spec lives under docs/ so `tesseraql package` merges the whole
        // directory into the archive under the reserved .tesseraql/ prefix.
        Path docs = target.resolve("docs");
        Files.createDirectories(docs);
        Path spec = docs.resolve("spec.json");
        Files.writeString(spec, new AppDocGenerator().toJson(manifest));
        System.out.println("Generated " + openapi);
        System.out.println("Generated " + htmx);
        System.out.println("Generated " + spec);
        return 0;
    }
}
