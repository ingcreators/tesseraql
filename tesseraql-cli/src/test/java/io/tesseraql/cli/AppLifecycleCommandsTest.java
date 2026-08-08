package io.tesseraql.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.yaml.manifest.ManifestLoader;
import io.tesseraql.yaml.release.ReleaseEvidence;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * The database-free app-lifecycle CLI surface — {@code lint}, {@code generate}, {@code governance},
 * {@code package} and {@code verify} — drives the same engines as the Maven goals over a freshly
 * scaffolded app.
 */
class AppLifecycleCommandsTest {

    @Test
    void lintPassesOnAFreshlyScaffoldedApp(@TempDir Path dir) {
        Path app = scaffold(dir);
        assertThat(execute("lint", "--app", app.toString())).isZero();
    }

    @Test
    void lintJsonPrintsTheCrossSurfaceFindingsDocument(@TempDir Path dir) throws Exception {
        Path app = scaffold(dir);
        Captured clean = executeCapturing("lint", "--app", app.toString(), "--format", "json");
        assertThat(clean.exitCode()).isZero();
        JsonNode document = new ObjectMapper().readTree(clean.stdout());
        assertThat(document.get("errors").asLong()).isZero();
        assertThat(document.get("warnings").asLong()).isEqualTo(document.get("findings").size());

        Files.createDirectories(app.resolve("web/broken"));
        Files.writeString(app.resolve("web/broken/get.yml"), """
                version: tesseraql/v1
                id: broken.search
                kind: route
                recipe: query-json

                security:
                  auth: bearer
                  policy: app.read

                sql:
                  file: missing.sql
                  mode: query

                response:
                  json:
                    status: 200
                    body:
                      data: sql.rows
                """);
        Captured broken = executeCapturing("lint", "--app", app.toString(), "--format", "json");
        assertThat(broken.exitCode()).isOne();
        JsonNode report = new ObjectMapper().readTree(broken.stdout());
        assertThat(report.get("errors").asLong()).isPositive();
        JsonNode finding = null;
        for (JsonNode candidate : report.get("findings")) {
            if (candidate.get("message").asText().contains("missing.sql")) {
                finding = candidate;
            }
        }
        assertThat(finding).as("a finding about the missing SQL file").isNotNull();
        assertThat(finding.get("severity").asText()).isEqualTo("error");
        assertThat(finding.get("source").asText()).isEqualTo("web/broken/get.yml");
        assertThat(finding.get("code").asText()).startsWith("TQL-");
        assertThat(finding.has("line")).isTrue();
        assertThat(finding.has("column")).isTrue();
    }

    @Test
    void symbolsPrintsPoliciesMessagesAndRoutesWithLines(@TempDir Path dir) throws Exception {
        Path app = scaffold(dir);
        Files.createDirectories(app.resolve("messages"));
        Files.writeString(app.resolve("messages/en.yml"), """
                users:
                  list:
                    title: Users
                """);
        Files.createDirectories(app.resolve("domains"));
        Files.writeString(app.resolve("domains/catalog.yml"), """
                version: tesseraql/v1
                domains:
                  sku:
                    type: string
                    maxLength: 40
                """);
        Files.createDirectories(app.resolve("rules"));
        Files.writeString(app.resolve("rules/inventory.yml"), """
                version: tesseraql/v1
                rules:
                  editableStatus:
                    rule: "params.status == 'draft'"
                    code: not-editable
                """);
        Files.createDirectories(app.resolve("decisions"));
        Files.writeString(app.resolve("decisions/approval.yml"), """
                version: tesseraql/v1
                decisions:
                  approvalRoute:
                    inputs:
                      amount: { type: number, match: between }
                    outputs:
                      assignee: { type: string }
                    rows:
                      - outputs: { assignee: approver-1 }
                """);
        Files.createDirectories(app.resolve("workflow"));
        Files.writeString(app.resolve("workflow/tick.sql"),
                "update purchase_requests set last_action = 'tick' where id = /* key */ 'x'\n");
        Files.writeString(app.resolve("workflow/purchase_request.yml"), """
                version: tesseraql/v1
                id: purchase_request
                kind: workflow
                mode: app
                document: { type: purchase_request, table: purchase_requests, key: id,
                            stateColumn: status }
                initial: draft
                states:
                  - { id: draft, type: initial }
                  - { id: approved, type: terminal }
                  - { id: escalated, type: terminal }
                transitions:
                  - { id: approve, from: draft, to: approved,
                      guard: "document.amount > 0", command: tick.sql }
                  - { id: escalate, from: draft, to: escalated, command: tick.sql }
                dispatch:
                  - { id: decide_next, oneOf: [approve, escalate] }
                """);
        Files.createDirectories(app.resolve("calendars"));
        Files.writeString(app.resolve("calendars/jp.yml"), """
                version: tesseraql/v1
                calendars:
                  jp-banking:
                    weekend: [saturday, sunday]
                """);
        Files.createDirectories(app.resolve("batch/close"));
        Files.writeString(app.resolve("batch/close/job.yml"), """
                version: tesseraql/v1
                id: nightly.close
                kind: job
                recipe: batch-tasklet
                trigger:
                  schedule:
                    cron: "0 0 2 * * ?"
                    calendar: jp-banking
                    dayOfMonth: 5
                sql: { file: close.sql, mode: query }
                """);
        Files.writeString(app.resolve("batch/close/close.sql"), "select 1\n");
        Captured captured = executeCapturing("symbols", "--app", app.toString());
        assertThat(captured.exitCode()).isZero();
        JsonNode document = new ObjectMapper().readTree(captured.stdout());

        assertThat(document.get("calendars")).hasSize(1);
        JsonNode calendar = document.get("calendars").get(0);
        assertThat(calendar.get("name").asText()).isEqualTo("jp-banking");
        assertThat(calendar.get("source").asText()).isEqualTo("calendars/jp.yml");
        assertThat(calendar.get("line").asInt()).isEqualTo(3);

        JsonNode close = null;
        for (JsonNode job : document.get("jobs")) {
            if (job.get("id").asText().equals("nightly.close")) {
                close = job;
            }
        }
        assertThat(close).as("the declared nightly.close job").isNotNull();
        assertThat(close.get("source").asText()).isEqualTo("batch/close/job.yml");
        assertThat(close.get("line").asInt()).isEqualTo(2);
        assertThat(close.get("trigger").asText())
                .isEqualTo("cron 0 0 2 * * ?, calendar jp-banking (day 5)");

        assertThat(document.get("workflows")).hasSize(1);
        JsonNode workflow = document.get("workflows").get(0);
        assertThat(workflow.get("id").asText()).isEqualTo("purchase_request");
        assertThat(workflow.get("source").asText()).isEqualTo("workflow/purchase_request.yml");
        assertThat(workflow.get("line").asInt()).isEqualTo(2);
        assertThat(workflow.get("transitions")).extracting(JsonNode::asText)
                .containsExactly("approve", "escalate");
        assertThat(workflow.get("dispatches")).extracting(JsonNode::asText)
                .containsExactly("decide_next");

        JsonNode appRead = null;
        for (JsonNode policy : document.get("policies")) {
            if (policy.get("name").asText().equals("app.read")) {
                appRead = policy;
            }
        }
        assertThat(appRead).as("the scaffolded app.read policy").isNotNull();
        assertThat(appRead.get("source").asText()).isEqualTo("config/tesseraql.yml");
        assertThat(appRead.get("line").asInt()).isPositive();

        assertThat(document.get("messages")).hasSize(1);
        JsonNode message = document.get("messages").get(0);
        assertThat(message.get("key").asText()).isEqualTo("users.list.title");
        assertThat(message.get("line").asInt()).isEqualTo(3);

        JsonNode sku = null;
        for (JsonNode domain : document.get("domains")) {
            if (domain.get("name").asText().equals("sku")) {
                sku = domain;
            }
        }
        assertThat(sku).as("the declared sku domain").isNotNull();
        assertThat(sku.get("source").asText()).isEqualTo("domains/catalog.yml");
        assertThat(sku.get("line").asInt()).isEqualTo(3);

        JsonNode rule = null;
        for (JsonNode candidate : document.get("rules")) {
            if (candidate.get("name").asText().equals("editableStatus")) {
                rule = candidate;
            }
        }
        assertThat(rule).as("the declared editableStatus rule").isNotNull();
        assertThat(rule.get("source").asText()).isEqualTo("rules/inventory.yml");
        assertThat(rule.get("line").asInt()).isEqualTo(3);

        JsonNode decision = null;
        for (JsonNode candidate : document.get("decisions")) {
            if (candidate.get("name").asText().equals("approvalRoute")) {
                decision = candidate;
            }
        }
        assertThat(decision).as("the declared approvalRoute decision").isNotNull();
        assertThat(decision.get("source").asText()).isEqualTo("decisions/approval.yml");
        assertThat(decision.get("line").asInt()).isEqualTo(3);

        assertThat(document.get("routes").size()).isPositive();
        JsonNode route = document.get("routes").get(0);
        assertThat(route.get("id").asText()).isNotBlank();
        assertThat(route.get("source").asText()).endsWith(".yml");
        assertThat(route.get("recipe").asText()).isNotBlank();
    }

    @Test
    void generateWritesOpenApiHtmxAndDocsSpec(@TempDir Path dir) {
        Path app = scaffold(dir);
        assertThat(execute("generate", "--app", app.toString())).isZero();
        assertThat(app.resolve("work/generated/openapi.json")).exists();
        assertThat(app.resolve("work/generated/htmx-contract.json")).exists();
        assertThat(app.resolve("work/generated/docs/spec.json")).exists();
    }

    @Test
    void governanceAssessesRoutesWithoutFailingWhenAsked(@TempDir Path dir) {
        Path app = scaffold(dir);
        assertThat(execute("governance", "--app", app.toString(), "--no-fail-on-violation"))
                .isZero();
    }

    @Test
    void packageProducesADeterministicArchiveWithAChecksum(@TempDir Path dir) throws Exception {
        Path app = scaffold(dir);
        assertThat(execute("package", "--app", app.toString())).isZero();
        Path archive = app.resolve("work/" + app.getFileName() + ".tqlapp");
        assertThat(archive).exists();
        assertThat(app.resolve("work/" + app.getFileName() + ".tqlapp.sha256")).exists();

        byte[] first = Files.readAllBytes(archive);
        assertThat(execute("package", "--app", app.toString())).isZero();
        assertThat(Files.readAllBytes(archive)).isEqualTo(first);
    }

    @Test
    void verifyAcceptsMatchingEvidenceAndRejectsTamperedSources(@TempDir Path dir)
            throws Exception {
        Path app = scaffold(dir);
        Path evidence = dir.resolve("release-evidence.json");
        Files.writeString(evidence, new ReleaseEvidence()
                .toJson(new ManifestLoader().load(app), "demo", "1.0.0"));

        assertThat(execute("verify", "--app", app.toString(),
                "--evidence-file", evidence.toString())).isZero();

        // Tampering with a recorded source breaks the recorded hash.
        Path tampered = app.resolve("config/tesseraql.yml");
        Files.writeString(tampered, Files.readString(tampered) + "\n# tampered\n");
        assertThat(execute("verify", "--app", app.toString(),
                "--evidence-file", evidence.toString())).isEqualTo(1);
    }

    private static Path scaffold(Path dir) {
        assertThat(execute("new", "demo", "--dir", dir.toString())).isZero();
        return dir.resolve("demo");
    }

    private static int execute(String... args) {
        return new CommandLine(new TesseraqlCli()).execute(args);
    }

    private static Captured executeCapturing(String... args) {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
            int exitCode = execute(args);
            return new Captured(exitCode, buffer.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(original);
        }
    }

    private record Captured(int exitCode, String stdout) {
    }
}
