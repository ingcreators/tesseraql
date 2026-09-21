package io.tesseraql.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The chart's arithmetic and contract, read off the committed rendering
 * (docs/deployment-maturity.md decisions 3 to 6) — the container-free half of what
 * {@code kubernetes.yml} proves with helm on the runner, so a `verify` on any machine catches a
 * rendering that drifted from the values, a grace below the bound, or a probe number that moved
 * away from the page that documents it.
 */
class KubernetesChartLedgerTest {

    private static final Path REPO = Path.of("..");
    private static final Path VALUES = REPO.resolve("deploy/helm/tesseraql/values.yaml");
    private static final Path CHART = REPO.resolve("deploy/helm/tesseraql/Chart.yaml");
    private static final Path RENDERED = REPO.resolve("deploy/kubernetes/tesseraql.yaml");
    private static final Path RELEASE = REPO.resolve(".github/workflows/release.yml");
    private static final Path WORKFLOW = REPO.resolve(".github/workflows/kubernetes.yml");
    private static final Path PAGE = REPO.resolve("docs/kubernetes.md");

    @Test
    void theGraceIsTheDeclaredBoundPlusTheMargin() throws IOException {
        int bound = number(VALUES, "^shutdownTimeoutSeconds:\\s*(\\d+)");
        int grace = number(RENDERED, "^\\s*terminationGracePeriodSeconds:\\s*(\\d+)");

        assertThat(grace).as("the grace is the drain bound plus fifteen seconds for the close")
                .isEqualTo(bound + 15);
        assertThat(Files.readString(RENDERED)).as("no preStop sleep spends the grace")
                .doesNotContain("preStop:");
    }

    @Test
    void theRollingContractIsSurgeOneNeverBelowTheCount() throws IOException {
        String rendered = Files.readString(RENDERED);

        assertThat(rendered).contains("maxSurge: 1").contains("maxUnavailable: 0")
                .contains("kind: PodDisruptionBudget").contains("minAvailable: 1")
                .contains("podAntiAffinity").contains("topologyKey: kubernetes.io/hostname")
                .contains("fieldPath: metadata.name");
        assertThat(rendered).as("two replicas by default").contains("replicas: 2");
    }

    @Test
    void theProbesAreTheOnesTheRecordNumbers() throws IOException {
        String rendered = Files.readString(RENDERED);

        assertThat(rendered).contains("path: /_tesseraql/health/live")
                .contains("path: /_tesseraql/health/ready");
        for (String probe : new String[]{"startup", "liveness", "readiness"}) {
            int period = number(VALUES, "^  " + probe + ":\\s*\\n\\s+periodSeconds:\\s*(\\d+)");
            int failures = number(VALUES,
                    "^  " + probe
                            + ":\\s*\\n\\s+periodSeconds:\\s*\\d+\\s*\\n\\s+failureThreshold:\\s*(\\d+)");
            assertThat(rendered)
                    .as("the rendering carries the %s probe's numbers", probe)
                    .containsPattern(probe + "Probe:\\s*\\n\\s+httpGet:[\\s\\S]*?periodSeconds: "
                            + period + "\\s*\\n\\s+failureThreshold: " + failures);
        }
        assertThat(number(VALUES, "^  startup:\\s*\\n\\s+periodSeconds:\\s*(\\d+)")
                * number(VALUES,
                        "^  startup:\\s*\\n\\s+periodSeconds:\\s*\\d+\\s*\\n\\s+failureThreshold:\\s*(\\d+)"))
                .as("the startup budget is sixty seconds against a boot of about three")
                .isEqualTo(60);
    }

    @Test
    void theRenderingIsTheExampleImageAndTheCommittedVersionIsAPlaceholder() throws IOException {
        assertThat(Files.readString(RENDERED))
                .as("the committed rendering names the example image the workflow renders with")
                .contains("image: \"ghcr.io/example/orders-stack:1.0.0\"");
        assertThat(Files.readString(WORKFLOW))
                .contains(
                        "--set image.repository=ghcr.io/example/orders-stack --set image.tag=1.0.0");
        assertThat(Files.readString(CHART)).as("the release sets the version from the tag")
                .contains("version: 0.0.0");
        assertThat(Files.readString(RELEASE))
                .contains(
                        "helm package deploy/helm/tesseraql --version \"$VERSION\" --app-version \"$VERSION\"")
                .contains("helm push");
        assertThat(Files.readString(RENDERED)).as("no version label to change per release")
                .doesNotContain("helm.sh/chart").doesNotContain("app.kubernetes.io/version");
    }

    /**
     * The M10 proof (docs/deployment-maturity.md decision 10): the workflow's {@code two-node}
     * job runs every phase of the proof script as a step of its own, so a red run names the
     * sentence; the proof's values put the origin on the NodePort the kind cluster publishes;
     * and the job is scheduled, since a real cluster is not a per-pull-request unit.
     */
    @Test
    void theTwoNodeJobRunsEveryPhaseOfTheProofOnAScheduleToo() throws IOException {
        String workflow = Files.readString(WORKFLOW);
        assertThat(workflow).contains("  two-node:").contains("schedule:");
        for (String phase : new String[]{"cluster", "install", "rolling", "firings", "sessions",
                "alerts", "stop", "logs"}) {
            assertThat(workflow).as("the %s phase is a step", phase)
                    .contains("bash .github/kubernetes/proof.sh " + phase);
        }
        String script = Files.readString(REPO.resolve(".github/kubernetes/proof.sh"));
        for (String phase : new String[]{"rolling", "firings", "sessions", "alerts", "stop"}) {
            assertThat(script).contains("phase_" + phase + "()");
        }
        String values = Files.readString(REPO.resolve(".github/kubernetes/values.yaml"));
        String kind = Files.readString(REPO.resolve(".github/kubernetes/kind.yaml"));
        assertThat(values).contains("type: NodePort").contains("nodePort: 30080");
        assertThat(kind).as("the cluster publishes the port the values pin")
                .contains("containerPort: 30080");
        assertThat(Files.readString(REPO.resolve("deploy/helm/tesseraql/templates/service.yaml")))
                .contains("nodePort: {{ .Values.service.nodePort }}");
    }

    @Test
    void thePageDocumentsEveryTopLevelValue() throws IOException {
        String page = Files.readString(PAGE);
        Matcher key = Pattern.compile("(?m)^([a-zA-Z]+):").matcher(Files.readString(VALUES));
        java.util.List<String> undocumented = new java.util.ArrayList<>();
        while (key.find()) {
            String name = key.group(1);
            if (!page.contains("`" + name)) {
                undocumented.add(name);
            }
        }
        assertThat(undocumented).as("top-level values docs/kubernetes.md does not name").isEmpty();
    }

    private static int number(Path file, String pattern) throws IOException {
        Matcher matcher = Pattern.compile(pattern, Pattern.MULTILINE)
                .matcher(Files.readString(file));
        assertThat(matcher.find()).as("%s matches %s", file, pattern).isTrue();
        return Integer.parseInt(matcher.group(1));
    }
}
