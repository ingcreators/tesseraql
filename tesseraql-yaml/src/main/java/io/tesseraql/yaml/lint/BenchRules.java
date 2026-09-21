package io.tesseraql.yaml.lint;

import static io.tesseraql.yaml.lint.LintFinding.Severity.ERROR;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.SimpleYamlParser;
import io.tesseraql.yaml.bench.BenchScenario;
import io.tesseraql.yaml.bench.BenchScenarios;
import io.tesseraql.yaml.manifest.AppManifest;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Bench scenarios under {@code bench/} (docs/deployment-maturity.md decision 8): lint-checked like
 * a suite, so a run refuses at the build rather than after thirty seconds of load against the
 * wrong route. The checks themselves live in {@link BenchScenarios}, which the verb shares.
 */
final class BenchRules implements LintRule {

    @Override
    public void lint(LintContext context, AppManifest manifest, List<LintFinding> findings) {
        Path appHome = context.appHome();
        SimpleYamlParser parser = new SimpleYamlParser();
        for (Path document : LintSupport.documents(appHome, "bench")) {
            // A scenario parses into an ignoreUnknown record, so a `concurency:` would be
            // silently the default without this.
            UnknownKeyRules.lintUnknownKeys(context, appHome, document, BenchScenario.class,
                    Set.of(), findings);
            String source = appHome.relativize(document).toString().replace('\\', '/');
            BenchScenario scenario;
            try {
                scenario = parser.parseBench(document);
            } catch (TqlException malformed) {
                findings.add(new LintFinding(malformed.code().toString(), ERROR, source,
                        malformed.getMessage()));
                continue;
            }
            for (BenchScenarios.Problem problem : BenchScenarios.validate(scenario,
                    manifest.routes())) {
                findings.add(new LintFinding(problem.code(), ERROR, source, problem.message()));
            }
        }
    }
}
