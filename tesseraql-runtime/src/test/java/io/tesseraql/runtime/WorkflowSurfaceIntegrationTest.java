package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.pipeline.TesseraqlProperties;
import io.tesseraql.security.Principal;
import io.tesseraql.security.session.SessionStore;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The workflow surface end to end (docs/workflow-surface.md slice 1): a detail view declaring
 * {@code workflow:} renders the lifecycle stepper and only the transitions legal for this user
 * on this state — wrong role absent, wrong state absent, a refused expression guard disabled
 * with its reason — and a browser form post answers Post/Redirect/Get back to the page, whose
 * re-render shows current truth. The JSON contract of the synthesized routes is untouched.
 */
@Testcontainers
class WorkflowSurfaceIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static TesseraqlRuntime runtime;
    static Path appHome;
    static String actorCookie;
    static String actorCsrf;
    static String approverCookie;
    static String approverCsrf;

    @BeforeAll
    static void start() throws Exception {
        seedDatabase();
        appHome = prepareAppHome();
        runtime = TesseraqlRuntime.start(appHome, 0);
        SessionStore sessions = runtime.context().lookup(
                TesseraqlProperties.SESSION_STORE_BEAN, SessionStore.class);
        String actorSid = sessions.create(new Principal("actor-1", "actor-1", "Actor", null,
                List.of(), List.of("USER"), List.of(), Map.of()), SessionStore.ClientInfo.NONE);
        actorCookie = sessions.cookieName() + "=" + actorSid;
        actorCsrf = sessions.session(actorSid).csrfToken();
        String approverSid = sessions.create(new Principal("approver-1", "approver-1",
                "Approver", null, List.of(), List.of("USER", "APPROVER"), List.of(), Map.of()),
                SessionStore.ClientInfo.NONE);
        approverCookie = sessions.cookieName() + "=" + approverSid;
        approverCsrf = sessions.session(approverSid).csrfToken();
    }

    @AfterAll
    static void stop() throws IOException {
        if (runtime != null) {
            runtime.close();
        }
        if (appHome != null) {
            deleteRecursively(appHome);
        }
    }

    @Test
    void theRegionRendersOnlyThisStatesTransitions() throws Exception {
        HttpResponse<String> page = get("/docs/D-1", actorCookie);

        assertThat(page.statusCode()).isEqualTo(200);
        // Draft: submit is the one legal move; review-state actions are absent, not disabled.
        assertThat(page.body()).contains("data-hc-workflow")
                .contains("formaction=\"/api/docs/D-1/submit\"")
                .doesNotContain("/api/docs/D-1/approve")
                .doesNotContain("/api/docs/D-1/reject");
        // The stepper marks the current state and renders the whole declared lifecycle.
        assertThat(page.body()).contains("aria-current=\"step\"")
                .contains("hc-stepper");
    }

    @Test
    void aRefusedExpressionGuardRendersDisabledWithItsReason() throws Exception {
        HttpResponse<String> page = get("/docs/D-2", actorCookie);

        // D-2 has amount 0: the guard says no, and the refusal teaches — the button renders
        // aria-disabled with the declared message, never as a live formaction.
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("aria-disabled=\"true\"")
                .contains("needs a positive amount")
                .doesNotContain("formaction=\"/api/docs/D-2/submit\"");
    }

    @Test
    void aPolicyRefusedTransitionIsAbsentForTheWrongRole() throws Exception {
        // D-3 sits in review: approve carries its own policy (APPROVER), reject rides the
        // workflow default. The actor sees reject only; the approver sees both.
        HttpResponse<String> actor = get("/docs/D-3", actorCookie);
        assertThat(actor.body()).contains("/api/docs/D-3/reject")
                .doesNotContain("/api/docs/D-3/approve");

        HttpResponse<String> approver = get("/docs/D-3", approverCookie);
        assertThat(approver.body()).contains("/api/docs/D-3/approve")
                .contains("/api/docs/D-3/reject");
    }

    @Test
    void aTerminalTransitionCarriesTheConfirmGate() throws Exception {
        HttpResponse<String> approver = get("/docs/D-3", approverCookie);

        assertThat(approver.body()).contains("data-hc-confirm");
    }

    @Test
    void aFormPostAnswersPostRedirectGetAndThePageShowsCurrentTruth() throws Exception {
        HttpResponse<String> post = postForm("/api/docs/D-4/submit",
                "_csrf=" + actorCsrf + "&_return=/docs/D-4", actorCookie);

        assertThat(post.statusCode()).isEqualTo(303);
        assertThat(post.headers().firstValue("Location").orElse("")).endsWith("/docs/D-4");

        HttpResponse<String> page = get("/docs/D-4", actorCookie);
        // Current truth: the document moved to review, submit is gone, reject is offered.
        assertThat(page.body()).doesNotContain("formaction=\"/api/docs/D-4/submit\"")
                .contains("/api/docs/D-4/reject");

        // The state itself is the optimistic lock: replaying the stale submit answers 409
        // (TQL-WORKFLOW-3201) — the action the user saw is never applied to a moved document.
        HttpResponse<String> stale = postForm("/api/docs/D-4/submit",
                "_csrf=" + actorCsrf + "&_return=/docs/D-4", actorCookie);
        assertThat(stale.statusCode()).isEqualTo(409);
    }

    @Test
    void aBrowserPostWithoutTheCsrfTokenIsRefused() throws Exception {
        HttpResponse<String> post = postForm("/api/docs/D-5/submit", "_return=/docs/D-5",
                actorCookie);

        assertThat(post.statusCode()).isEqualTo(403);
    }

    @Test
    void aManagedWorkflowReadsItsStateFromTheInstanceStore() throws Exception {
        // M-1 has never transitioned: no instance row exists, and the region renders the
        // workflow's initial state — null from the store IS the initial state.
        HttpResponse<String> fresh = get("/cases/M-1", actorCookie);
        assertThat(fresh.statusCode()).isEqualTo(200);
        assertThat(fresh.body()).contains("data-hc-workflow")
                .contains("formaction=\"/api/cases/M-1/open\"");

        // Transitioning writes the instance; the re-rendered page reads it back.
        HttpResponse<String> post = postForm("/api/cases/M-1/open",
                "_csrf=" + actorCsrf + "&_return=/cases/M-1", actorCookie);
        assertThat(post.statusCode()).isEqualTo(303);
        HttpResponse<String> page = get("/cases/M-1", actorCookie);
        assertThat(page.body()).doesNotContain("formaction=\"/api/cases/M-1/open\"")
                .contains("formaction=\"/api/cases/M-1/close\"");
    }

    @Test
    void aCommentRequiredTransitionRefusesWithoutOneAndRecordsItInHistory() throws Exception {
        // Bring M-2 into the state whose exit demands a comment; open itself needs none.
        assertThat(postForm("/api/cases/M-2/open",
                "_csrf=" + actorCsrf + "&_return=/cases/M-2", actorCookie).statusCode())
                .isEqualTo(303);
        HttpResponse<String> page = get("/cases/M-2", actorCookie);
        assertThat(page.body()).contains("name=\"comment\"")
                .contains("Required for:");

        // Without a comment: the framework's standard required-input refusal (400, the
        // TQL-FIELD-2001 shape every required input answers — a recorded deviation from the
        // upstream contract's 422, consistency inside the framework being worth more).
        HttpResponse<String> refused = postForm("/api/cases/M-2/close",
                "_csrf=" + actorCsrf + "&_return=/cases/M-2", actorCookie);
        assertThat(refused.statusCode()).isEqualTo(400);

        // With one: the transition applies and the history row finally carries its note.
        HttpResponse<String> closed = postForm("/api/cases/M-2/close",
                "_csrf=" + actorCsrf + "&_return=/cases/M-2"
                        + "&comment=Resolved+by+phone",
                actorCookie);
        assertThat(closed.statusCode()).isEqualTo(303);
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement();
                var rows = statement.executeQuery("select note from tql_workflow_history"
                        + " where doc_id = 'M-2' and transition = 'close'")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString("note")).isEqualTo("Resolved by phone");
        }
    }

    @Test
    void theJsonContractIsUntouchedForApiCallers() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + "/api/docs/D-6/submit"))
                .header("Cookie", actorCookie)
                .header("X-CSRF-Token", actorCsrf)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        HttpResponse<String> post = HttpClient.newHttpClient().send(request,
                HttpResponse.BodyHandlers.ofString());

        assertThat(post.statusCode()).isEqualTo(200);
        assertThat(post.body()).contains("\"ok\"");
    }

    private static HttpResponse<String> get(String path, String cookie) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + runtime.port() + path))
                        .header("Cookie", cookie).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> postForm(String path, String body, String cookie)
            throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + runtime.port() + path))
                        .header("Cookie", cookie)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static void seedDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("create table docs (id varchar(64) primary key,"
                    + " status varchar(32) not null, amount int not null)");
            statement.execute("insert into docs (id, status, amount) values"
                    + " ('D-1', 'draft', 100), ('D-2', 'draft', 0), ('D-3', 'review', 50),"
                    + " ('D-4', 'draft', 10), ('D-5', 'draft', 10), ('D-6', 'draft', 10)");
            statement.execute("create table cases (id varchar(64) primary key,"
                    + " subject varchar(100) not null)");
            statement.execute("insert into cases (id, subject) values ('M-1', 'A case'),"
                    + " ('M-2', 'Another case')");
        }
    }

    private static Path prepareAppHome() throws IOException {
        Path home = Files.createTempDirectory("tesseraql-workflow-surface-it");
        Files.createDirectories(home.resolve("config"));
        Files.writeString(home.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: workflow-surface-app
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                  workflow:
                    mode: app
                  security:
                    defaults:
                      routes:
                        - match: /**
                          auth: browser
                          csrf: auto
                    policies:
                      wf.act:
                        anyOf:
                          - role: USER
                      wf.approve:
                        anyOf:
                          - role: APPROVER
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
        Files.createDirectories(home.resolve("workflow"));
        Files.writeString(home.resolve("workflow/doc.yml"), """
                version: tesseraql/v1
                id: doc
                kind: workflow
                mode: app
                document: { type: doc, table: docs, key: id, stateColumn: status }
                basePath: /api/docs
                security: { auth: browser, policy: wf.act }
                initial: draft
                states:
                  - { id: draft, type: initial }
                  - { id: review }
                  - { id: done, type: terminal }
                  - { id: void, type: terminal }
                transitions:
                  - id: submit
                    from: draft
                    to: review
                    guard:
                      expression: "document.amount > 0"
                      message: needs a positive amount
                    command: { file: touch.sql }
                  - id: approve
                    from: review
                    to: done
                    security: { auth: browser, policy: wf.approve }
                    command: { file: touch.sql }
                  - id: reject
                    from: review
                    to: void
                    command: { file: touch.sql }
                """);
        Files.writeString(home.resolve("workflow/touch.sql"), """
                update docs set amount = amount where id = /* key */'D-1'
                ;
                """);
        Files.writeString(home.resolve("workflow/case.yml"), """
                version: tesseraql/v1
                id: case
                kind: workflow
                mode: managed
                document: { type: case, table: cases, key: id }
                basePath: /api/cases
                security: { auth: browser, policy: wf.act }
                initial: new
                states:
                  - { id: new, type: initial }
                  - { id: open }
                  - { id: closed, type: terminal }
                transitions:
                  - { id: open, from: new, to: open, command: { file: touch-case.sql } }
                  - id: close
                    from: open
                    to: closed
                    comment: required
                    command: { file: touch-case.sql }
                """);
        Files.writeString(home.resolve("workflow/touch-case.sql"), """
                update cases set subject = subject where id = /* key */'M-1'
                ;
                """);
        Path caseDir = home.resolve("web/cases/{id}");
        Files.createDirectories(caseDir);
        Files.writeString(caseDir.resolve("get.yml"), """
                version: tesseraql/v1
                id: cases.detail
                kind: route
                recipe: query-html
                security: { policy: wf.act }
                sources:
                  main:
                    sql:
                      file: case.sql
                      mode: query
                      params:
                        id: path.id
                response:
                  html:
                    view: case
                """);
        Files.writeString(caseDir.resolve("case.sql"), """
                select c.id, c.subject
                from cases c
                where c.id = /* id */'M-1'
                ;
                """);
        Files.writeString(caseDir.resolve("detail.view.yml"), """
                version: tesseraql/v1
                id: case
                kind: view
                recipe: detail
                workflow: case
                title: Case
                """);
        Path dir = home.resolve("web/docs/{id}");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("get.yml"), """
                version: tesseraql/v1
                id: docs.detail
                kind: route
                recipe: query-html
                security: { policy: wf.act }
                sources:
                  main:
                    sql:
                      file: doc.sql
                      mode: query
                      params:
                        id: path.id
                response:
                  html:
                    view: doc
                """);
        Files.writeString(dir.resolve("doc.sql"), """
                select d.id, d.status, d.amount
                from docs d
                where d.id = /* id */'D-1'
                ;
                """);
        Files.writeString(dir.resolve("detail.view.yml"), """
                version: tesseraql/v1
                id: doc
                kind: view
                recipe: detail
                workflow: doc
                title: Document
                """);
        return home;
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> files = Files.walk(root)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            });
        }
    }
}
