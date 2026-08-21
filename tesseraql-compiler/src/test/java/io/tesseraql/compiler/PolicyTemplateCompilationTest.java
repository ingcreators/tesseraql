package io.tesseraql.compiler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A route policy that resolves its atom from the route's own path
 * (docs/access-governance.md structural decision 7), as the compiler emits and refuses it.
 *
 * <p>The assertion is on the compiled endpoint URI rather than on the YAML, because what
 * matters is what the producer receives: the policy with its {@code path.} qualifier dropped,
 * and the route's own URL template beside it — the template being what lets the atom be read
 * off the request's URL rather than off a header a form field can overwrite.
 */
class PolicyTemplateCompilationTest {

    @Test
    void theTemplateAndTheRoutesUrlTemplateBothReachTheProducer(@TempDir Path dir)
            throws Exception {
        Files.createDirectories(dir.resolve("web/admin/applications/{name}"));
        writeApp(dir, "web/admin/applications/{name}", "tql.iam.write.{path.name}");

        assertThat(authorizeGates(dir)).singleElement().satisfies(gate -> {
            assertThat(gate.policy()).isEqualTo("tql.iam.write.{name}");
            assertThat(gate.pathTemplate()).isEqualTo("/admin/applications/{name}");
        });
    }

    @Test
    void aFixedPolicyIdReachesTheGateUnchanged(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("web/admin/applications/{name}"));
        writeApp(dir, "web/admin/applications/{name}", "tql.iam.admin.write");

        assertThat(authorizeGates(dir)).singleElement().satisfies(gate -> {
            assertThat(gate.policy()).isEqualTo("tql.iam.admin.write");
            // A fixed atom needs no template: there is no parameter to read off the URL.
            assertThat(gate.pathTemplate()).isNull();
        });
    }

    /**
     * The braces need no escaping, because there is nothing left to escape them into.
     *
     * <p>This used to be the round-trip assertion: the template was percent-encoded into a query
     * string on the way out and decoded on the way in, and a mismatch would have handed the gate
     * an escaped string it would try to resolve literally. The gate takes the value as an argument
     * now (docs/camel-removal.md decision 2), so the encoding — and the class of defect that comes
     * with an encoding — is gone rather than tested.
     */
    @Test
    void theTemplateReachesTheGateWithNothingEncodedOnTheWay(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("web/admin/applications/{name}"));
        writeApp(dir, "web/admin/applications/{name}", "tql.iam.write.{path.name}");

        assertThat(authorizeGates(dir)).singleElement().satisfies(gate -> {
            assertThat(gate.policy()).doesNotContain("%");
            assertThat(gate.pathTemplate()).doesNotContain("%");
        });
    }

    /** A reference the route's own path does not declare would resolve to nothing, always. */
    @Test
    void anUndeclaredPathParameterIsRefusedAtBoot(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("web/admin/applications/{name}"));
        writeApp(dir, "web/admin/applications/{name}", "tql.iam.write.{path.nope}");

        assertThatThrownBy(() -> authorizeGates(dir))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-YAML-1409")
                .hasMessageContaining("[name]");
    }

    /** A gate may not be built from a value the caller shapes freely. */
    @Test
    void onlyThePathMayBeInterpolated(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("web/admin/applications/{name}"));
        writeApp(dir, "web/admin/applications/{name}", "tql.iam.write.{query.name}");

        assertThatThrownBy(() -> authorizeGates(dir))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-YAML-1409")
                .hasMessageContaining("own path");
    }

    /** Only a framework atom is synthesized from the granted code, so only it can resolve. */
    @Test
    void aTemplateOutsideTheFrameworkMarkIsRefused(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("web/admin/applications/{name}"));
        writeApp(dir, "web/admin/applications/{name}", "orders.admin.{path.name}");

        assertThatThrownBy(() -> authorizeGates(dir))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-YAML-1409")
                .hasMessageContaining("names no policy at all");
    }

    /** Every {@code tesseraql-auth:authorize} endpoint the fixture app compiles to. */
    private static List<io.tesseraql.camel.auth.AuthStep> authorizeGates(Path dir)
            throws Exception {
        AppManifest manifest = new ManifestLoader().load(dir);
        try (DefaultCamelContext context = new DefaultCamelContext()) {
            new RouteCompiler().appName("policy-template-test")
                    .compile(context, manifest, false, null);
            return CompiledPipelines.steps(context, io.tesseraql.camel.auth.AuthStep.class)
                    .stream().filter(step -> "authorize".equals(step.operation())).toList();
        }
    }

    private static void writeApp(Path dir, String routeDir, String policy) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: policy-template-test
                """);
        Files.createDirectories(dir.resolve(routeDir));
        Files.writeString(dir.resolve(routeDir).resolve("get.yml"), """
                version: tesseraql/v1
                id: applications.detail
                kind: route
                recipe: query-json
                security:
                  auth: browser
                  policy: %s
                sources:
                  main:
                    sql:
                      file: read.sql
                      mode: query
                response:
                  json:
                    body:
                      data: main.rows
                """.formatted(policy));
        Files.writeString(dir.resolve(routeDir).resolve("read.sql"), "select 1 as one\n");
    }
}
