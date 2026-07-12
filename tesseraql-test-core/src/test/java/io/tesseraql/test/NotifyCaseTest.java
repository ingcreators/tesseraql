package io.tesseraql.test;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.test.TestSuite.Expectation;
import io.tesseraql.test.TestSuite.NotifyTarget;
import io.tesseraql.test.TestSuite.TestCase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 20: a notify case evaluates a route's {@code notify:} block or a job's notify steps
 * against the case's params — guards and payload expressions run exactly as at runtime — and
 * the fired notifications are the case's rows. No database, SMTP, or HTTP is touched.
 */
class NotifyCaseTest {

    @TempDir
    static Path appHome;

    static TestRunner runner;

    @BeforeAll
    static void setUp() throws Exception {
        Files.createDirectories(appHome.resolve("config"));
        Files.writeString(appHome.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: members
                  notifications:
                    channels:
                      audit-webhook:
                        type: webhook
                        url: https://audit.example/hooks/tesseraql
                        secret: test-signing-secret
                      member-mail:
                        type: mail
                        host: mail.example.com
                        from: noreply@example.com
                        to: ops@example.com
                        subject: "Welcome [(${payload.email})]"
                        template: templates/mail/confirmation.txt
                """);
        Files.createDirectories(appHome.resolve("templates/mail"));
        Files.writeString(appHome.resolve("templates/mail/confirmation.txt"),
                "Hello [(${payload.email})] - your registration is confirmed.\n");
        Files.createDirectories(appHome.resolve("web/members"));
        Files.writeString(appHome.resolve("web/members/post.yml"), """
                version: tesseraql/v1
                id: members.register
                kind: route
                recipe: command-json
                notify:
                  confirmation:
                    channel: member-mail
                    when: body.email != null
                    payload:
                      email: body.email
                  audit:
                    channel: audit-webhook
                sql:
                  file: insert-member.sql
                  mode: update
                response:
                  json:
                    status: 201
                    body:
                      affected: sql.affectedRows
                """);
        Files.createDirectories(appHome.resolve("batch/cleanup"));
        Files.writeString(appHome.resolve("batch/cleanup/job.yml"), """
                version: tesseraql/v1
                id: members.cleanup
                kind: job
                recipe: batch-pipeline
                pipeline:
                  - id: purge
                    sql:
                      file: purge.sql
                      mode: update
                  - id: report
                    notify:
                      channel: ops-mail
                      payload:
                        purged: step.purge.affectedRows
                """);
        runner = new TestRunner(null, appHome);
    }

    private static TestReport run(TestCase test) {
        return runner.run(new TestSuite(List.of(test)));
    }

    @Test
    void aRouteCaseReturnsFiredNotificationsAsRows() {
        TestReport report = run(new TestCase("fires both", null, null,
                Map.of("body", Map.of("email", "sato@example.com")),
                new Expectation(2, List.of(
                        Map.of("notify", "confirmation", "channel", "member-mail",
                                "email", "sato@example.com"),
                        Map.of("notify", "audit", "channel", "audit-webhook",
                                "source", "members.register.audit"))),
                null, new NotifyTarget("members.register", null, null), null));

        assertThat(report.results()).singleElement()
                .satisfies(result -> assertThat(result.passed())
                        .withFailMessage(result.message()).isTrue());
    }

    @Test
    void theWhenGuardSkipsANotification() {
        TestReport report = run(new TestCase("guard skips confirmation", null, null,
                Map.of("body", Map.of()),
                new Expectation(0, List.of()),
                null, new NotifyTarget("members.register", null, "confirmation"), null));

        assertThat(report.results()).singleElement()
                .satisfies(result -> assertThat(result.passed())
                        .withFailMessage(result.message()).isTrue());
    }

    @Test
    void aJobCaseEvaluatesNotifySteps() {
        TestReport report = run(new TestCase("job report step", null, null,
                Map.of("step", Map.of("purge", Map.of("affectedRows", 7))),
                new Expectation(1, List.of(
                        Map.of("notify", "report", "channel", "ops-mail", "purged", 7))),
                null, new NotifyTarget(null, "members.cleanup", null), null));

        assertThat(report.results()).singleElement()
                .satisfies(result -> assertThat(result.passed())
                        .withFailMessage(result.message()).isTrue());
    }

    @Test
    void anUnknownTargetFailsTheCase() {
        TestReport unknownRoute = run(new TestCase("unknown", null, null, Map.of(), null,
                null, new NotifyTarget("members.unknown", null, null), null));
        TestReport bothTargets = run(new TestCase("both", null, null, Map.of(), null,
                null, new NotifyTarget("members.register", "members.cleanup", null), null));

        assertThat(unknownRoute.results().get(0).passed()).isFalse();
        assertThat(bothTargets.results().get(0).passed()).isFalse();
    }

    /**
     * Real-send mode (docs/testing.md): the webhook notification is delivered by the
     * production sender over a real socket to the runner's capture server — the row carries
     * the HMAC signature and the JSON body that actually hit the wire. The mail-channel
     * notification keeps its evaluate-only row.
     */
    @Test
    void sendDeliversWebhookChannelsToTheCaptureServer() {
        TestReport report = run(new TestCase("audit hits the wire signed", null, null,
                Map.of("body", Map.of("email", "a@example.com", "name", "sato")), null, null,
                new TestSuite.NotifyTarget("members.register", null, null, true), null));

        assertThat(report.allPassed()).as(report.results().toString()).isTrue();
    }

    @Test
    void sendRowsCarryTheWireSignatureAndBody() {
        TestReport report = run(new TestCase("wire fields", null, null,
                Map.of("body", Map.of("name", "sato")), new Expectation(1, List.of(
                        Map.of("notify", "audit", "channel", "audit-webhook",
                                "delivered", true))),
                null, new TestSuite.NotifyTarget("members.register", null, "audit", true),
                null));
        assertThat(report.allPassed()).as(report.results().toString()).isTrue();
    }

    /**
     * Mail real-send (docs/testing.md): the production sender renders the channel template
     * and subject and delivers over real SMTP to the runner's capture - the row carries the
     * rfc822 truth (to/from/subject/body), and the wire never touches the channel's real host.
     */
    @Test
    void sendDeliversMailChannelsToTheSmtpCapture() {
        TestReport report = run(new TestCase("confirmation mail hits the wire", null, null,
                Map.of("body", Map.of("email", "sato@example.com")),
                new Expectation(1, List.of(Map.of(
                        "notify", "confirmation",
                        "channel", "member-mail",
                        "delivered", true,
                        "to", "ops@example.com",
                        "from", "noreply@example.com",
                        "subject", "Welcome sato@example.com",
                        "wireBody", "Hello sato@example.com - your registration is confirmed."))),
                null, new TestSuite.NotifyTarget("members.register", null, "confirmation", true),
                null));
        assertThat(report.allPassed()).as(report.results().toString()).isTrue();
    }
}
