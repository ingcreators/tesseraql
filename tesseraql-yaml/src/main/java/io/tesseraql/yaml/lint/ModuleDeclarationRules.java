package io.tesseraql.yaml.lint;

import static io.tesseraql.yaml.lint.LintFinding.Severity.WARNING;

import io.tesseraql.yaml.manifest.AppManifest;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What an application uses against what it declares (docs/module-channel.md decision 3).
 *
 * <p>Opt-in codecs reach a deployment one way: the application declares them in
 * {@code tesseraql.modules}, packaging carries the locked closure, and the host refuses to start
 * without it. An application built through a wrapper pom can also put a codec on its own build
 * classpath, where exports work locally and the declaration that a {@code .tqlapp} would carry
 * never gets written — so the package deploys, starts, and fails at the first export with
 * {@code TQL-LD-2801}. That is a long way from the mistake, so this says it at lint time.
 *
 * <p>A warning, not an error: the build classpath is a legitimate route for an application that
 * builds its own runtime image and never packages. The rule stays silent when the codec is
 * discoverable here — a wrapper-pom build lints with its own dependencies present — so it speaks
 * exactly when the format is used, undeclared, and unavailable.
 */
final class ModuleDeclarationRules implements LintRule {

    // A format is used whose codec the application neither declares nor carries.
    private static final String CODEC_NOT_DECLARED = "TQL-YAML-1408";

    /** Export formats that live in an opt-in module, and the coordinate that provides each. */
    private static final Map<String, String> MODULE_FORMATS = Map.of(
            "pdf", "io.tesseraql:tesseraql-pdf",
            "excel", "io.tesseraql:tesseraql-excel");

    @Override
    public void lint(LintContext context, AppManifest manifest, List<LintFinding> findings) {
        Map<String, String> usedFormats = usedFormats(manifest, context.appHome());
        if (usedFormats.isEmpty()) {
            return;
        }
        Set<String> declared = declaredModules(manifest);
        io.tesseraql.core.files.FileCodecs available = io.tesseraql.core.files.FileCodecs
                .discover();
        usedFormats.forEach((format, source) -> {
            String coordinate = MODULE_FORMATS.get(format);
            if (declared.contains(coordinate) || available.supports(format)) {
                return;
            }
            findings.add(new LintFinding(CODEC_NOT_DECLARED, WARNING, source,
                    "format: " + format + " needs the " + coordinate + " module, which this"
                            + " application neither declares under tesseraql.modules nor carries"
                            + " on the classpath — a package built from it would deploy and fail"
                            + " at the first export"));
        });
    }

    /** Each opt-in format the app exports, mapped to the first document that uses it. */
    private static Map<String, String> usedFormats(AppManifest manifest,
            java.nio.file.Path appHome) {
        Map<String, String> used = new LinkedHashMap<>();
        manifest.routes().forEach(route -> {
            io.tesseraql.yaml.model.ExportSpec export = route.definition().fileExport();
            if (export != null) {
                record(used, export.format(), relative(appHome, route.source()));
            }
        });
        manifest.jobs().forEach(job -> {
            if (job.definition().pipeline() == null) {
                return;
            }
            job.definition().pipeline().forEach(step -> {
                if (step.export() != null) {
                    record(used, step.export().format(), relative(appHome, job.source()));
                }
            });
        });
        return used;
    }

    private static String relative(java.nio.file.Path appHome, java.nio.file.Path source) {
        return appHome.relativize(source).toString().replace('\\', '/');
    }

    private static void record(Map<String, String> used, String format, String source) {
        if (format != null && MODULE_FORMATS.containsKey(format)) {
            used.putIfAbsent(format, source);
        }
    }

    /** The declared {@code tesseraql.modules} coordinates, without their optional version. */
    private static Set<String> declaredModules(AppManifest manifest) {
        Set<String> declared = new LinkedHashSet<>();
        if (manifest.config().navigate("tesseraql.modules") instanceof List<?> modules) {
            for (Object module : modules) {
                String coordinate = String.valueOf(module).trim();
                String[] parts = coordinate.split(":");
                if (parts.length >= 2) {
                    declared.add(parts[0] + ":" + parts[1]);
                }
            }
        }
        return declared;
    }
}
