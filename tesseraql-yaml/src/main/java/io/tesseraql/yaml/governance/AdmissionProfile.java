package io.tesseraql.yaml.governance;

import io.tesseraql.yaml.lint.AppLinter;
import io.tesseraql.yaml.lint.LintFinding;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestLoader;
import io.tesseraql.yaml.manifest.RouteFile;
import io.tesseraql.yaml.model.ResponseSpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The marketplace admission profile (roadmap Phase 47, realizing Phase 37's admission gate):
 * the machine-checkable bar a shared app must clear before anyone else runs it. It composes
 * the primitives that already exist — {@link AppLinter} and {@link GovernanceGate} — and adds
 * the marketplace-specific constraints the roadmap names: declarative-only (the framework is
 * the sandbox), deny-by-default policies actually defined, egress bounded, and CSP intact on
 * every HTML surface. Pure file reads; deterministic; fit for CI and the publish pipeline.
 */
public final class AdmissionProfile {

    /**
     * TQL-ADM-4705: a governance violation — a review-worthy route with no valid approval —
     * fails marketplace admission.
     */
    private static final String GOVERNANCE_VIOLATION = "TQL-ADM-4705";

    /** TQL-ADM-4706: every linter error in the app tree is a marketplace admission failure. */
    private static final String LINT_ERROR = "TQL-ADM-4706";

    private AdmissionProfile() {
    }

    /** One admission failure: a {@code TQL-ADM-47xx} code, the subject, and the reason. */
    public record Finding(String code, String subject, String reason) {
    }

    /** The full admission outcome; {@code admitted()} is true only with zero failures. */
    public record Report(List<Finding> failures, List<Finding> notes,
            List<LintFinding> lintErrors,
            List<GovernanceGate.Violation> governanceViolations) {

        public boolean admitted() {
            return failures.isEmpty();
        }
    }

    /** Runs the profile over an app tree. */
    public static Report check(Path appHome) {
        List<Finding> failures = new ArrayList<>();

        // 1. The linter's errors are admission failures wholesale (TQL-ADM-4706), and the
        // deny-by-default warning — a route referencing a policy the config does not define
        // (TQL-SEC-4030) — is PROMOTED to a failure: "another environment defines it" is an
        // acceptable answer inside one team, not for an app strangers will run.
        List<LintFinding> findings = new AppLinter().lint(appHome);
        List<LintFinding> lintErrors = findings.stream().filter(LintFinding::isError).toList();
        for (LintFinding finding : lintErrors) {
            failures.add(new Finding(LINT_ERROR, finding.location(),
                    finding.code() + ": " + finding.message()));
        }
        findings.stream()
                .filter(finding -> "TQL-SEC-4030".equals(finding.code()))
                .forEach(finding -> failures.add(new Finding("TQL-ADM-4702",
                        finding.location(),
                        "deny-by-default requires the policy to be DEFINED here: "
                                + finding.message())));

        AppManifest manifest = new ManifestLoader().load(appHome);

        // 2. Governance: unapproved review-worthy surfaces fail; beyond the gate, any
        // 'advanced' (unauthenticated write) or 'extended' (Java service binding) mode fails
        // the declarative-only constraint outright — the framework is the sandbox only when
        // every behavior is interpreted from the documents.
        GovernanceGate.Report governance = new GovernanceGate(manifest).check(manifest);
        for (GovernanceGate.Violation violation : governance.violations()) {
            failures.add(new Finding(GOVERNANCE_VIOLATION, violation.routeId(),
                    violation.reason()));
        }
        for (RouteGovernance.Assessment assessment : governance.assessments()) {
            if ("advanced".equals(assessment.mode())) {
                failures.add(new Finding("TQL-ADM-4701", assessment.routeId(),
                        "unauthenticated write surface (mode advanced): "
                                + String.join(", ", assessment.riskFactors())));
            } else if ("extended".equals(assessment.mode())) {
                failures.add(new Finding("TQL-ADM-4701", assessment.routeId(),
                        "binds a runtime service provider (mode extended); marketplace apps"
                                + " are declarative-only"));
            }
        }

        // 3. Declarative-only, jar edition: no plugin jars may ride along.
        Path plugins = appHome.resolve(
                manifest.config().getString("tesseraql.plugins.dir").orElse("plugins"));
        if (Files.isDirectory(plugins)) {
            try (Stream<Path> jars = Files.list(plugins)) {
                if (jars.anyMatch(jar -> jar.getFileName().toString().endsWith(".jar"))) {
                    failures.add(new Finding("TQL-ADM-4701", relative(appHome, plugins),
                            "plugin jars are not admissible; marketplace apps are"
                                    + " declarative-only"));
                }
            } catch (IOException ignored) {
                // an unreadable plugins dir cannot prove jars absent
                failures.add(new Finding("TQL-ADM-4701", relative(appHome, plugins),
                        "plugins directory is unreadable"));
            }
        }

        // 4. Egress must be bounded: a lone "*" is not an allow-list.
        for (String key : new String[]{"tesseraql.http.outbound.allowedHosts",
                "tesseraql.connectors.poll.allowedHosts"}) {
            Object hosts = manifest.config().navigate(key);
            if (hosts instanceof List<?> list && list.stream()
                    .anyMatch(host -> "*".equals(String.valueOf(host).trim()))) {
                failures.add(new Finding("TQL-ADM-4703", key,
                        "a bare '*' egress entry is unbounded; list hosts or *.domain"
                                + " wildcards"));
            }
        }

        // 5. CSP intact on every HTML PAGE the app serves. Fragment routes (the documented
        // /fragments/ URL convention, design ch. 4.2) are exempt: a fragment swaps into a
        // page whose own CSP already governs the document. The app-wide default response
        // headers (docs/route-defaults.md) count: the compiler merges them under every HTML
        // response, so a page passes when either layer sends CSP — unless the route
        // suppresses the default with `unset`, which re-exposes it here.
        boolean appWideCsp = io.tesseraql.yaml.config.ResponseHeaderDefaults
                .from(manifest.config()).headers().keySet().stream()
                .anyMatch("Content-Security-Policy"::equalsIgnoreCase);
        for (RouteFile route : manifest.routes()) {
            ResponseSpec response = route.definition().response();
            ResponseSpec.HtmlResponse html = response == null ? null : response.html();
            if (html == null || route.urlPath().contains("/fragments/")) {
                continue;
            }
            String declared = html.headers() == null
                    ? null
                    : html.headers().entrySet().stream()
                            .filter(e -> "Content-Security-Policy".equalsIgnoreCase(e.getKey()))
                            .map(e -> String.valueOf(e.getValue()))
                            .findFirst().orElse(null);
            boolean suppressed = io.tesseraql.yaml.config.ResponseHeaderDefaults.UNSET
                    .equals(declared);
            boolean hasCsp = !suppressed && (declared != null || appWideCsp);
            if (!hasCsp) {
                failures.add(new Finding("TQL-ADM-4704",
                        relative(appHome, route.source()),
                        "HTML response without a Content-Security-Policy header"));
            }
        }

        failures.sort(java.util.Comparator.comparing(Finding::code)
                .thenComparing(Finding::subject));
        // Informational NOTEs (never failures): where the analytics engine talks beyond the
        // app tree — write-mode attaches, remote lakes with their endpoints, ad-hoc remote
        // prefixes (docs/duckdb.md). Marketplace review and deployment egress control read
        // these; they carry no TQL- code because they are not errors.
        List<Finding> notes = new ArrayList<>();
        if (manifest.config().navigate("tesseraql.datasources") instanceof Map<?, ?> sources) {
            for (Object nameKey : sources.keySet()) {
                String datasource = String.valueOf(nameKey);
                String duck = "tesseraql.datasources." + datasource + ".duckdb.";
                if (manifest.config().navigate(duck + "attach") instanceof List<?> attaches) {
                    for (Object entry : attaches) {
                        if (entry instanceof Map<?, ?> attach
                                && "readwrite".equals(String.valueOf(attach.get("mode")))) {
                            notes.add(new Finding("NOTE-ATTACH-READWRITE", datasource,
                                    "attaches '" + attach.get("datasource")
                                            + "' in readwrite mode"));
                        }
                    }
                }
                String lakeData = manifest.config().getString(duck + "lake.data").orElse("");
                if (lakeData.startsWith("s3://")) {
                    notes.add(new Finding("NOTE-REMOTE-LAKE", datasource,
                            "lake data on " + lakeData
                                    + manifest.config().getString(duck + "lake.endpoint")
                                            .map(e -> " via endpoint " + e).orElse("")));
                }
                if (manifest.config().navigate(duck + "remotes") instanceof Map<?, ?> remotes) {
                    for (Object remoteName : remotes.keySet()) {
                        notes.add(new Finding("NOTE-REMOTE-READ", datasource,
                                "${remote." + remoteName + "} reads "
                                        + manifest.config().getString(duck + "remotes."
                                                + remoteName + ".url").orElse("?")
                                        + manifest.config().getString(duck + "remotes."
                                                + remoteName + ".endpoint")
                                                .map(e -> " via endpoint " + e).orElse("")));
                    }
                }
            }
        }
        return new Report(failures, notes, lintErrors, governance.violations());
    }

    private static String relative(Path appHome, Path path) {
        return appHome.toAbsolutePath().normalize()
                .relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }
}
