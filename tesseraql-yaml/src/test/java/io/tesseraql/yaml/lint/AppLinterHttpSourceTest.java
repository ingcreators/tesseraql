package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * http: source lints (docs/connectors.md, "HTTP sources"): query recipes only, no shadowing
 * of SQL result keys, and the same egress checks as a job's http-call step.
 */
class AppLinterHttpSourceTest {

    private static void writeApp(Path dir, String recipe, String allowedHosts, String extra)
            throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: t
                  http:
                    outbound:
                      allowedHosts:
                        - %s
                      credentials:
                        fx-api:
                          type: bearer
                          token: dummy
                """.formatted(allowedHosts));
        Files.createDirectories(dir.resolve("web/orders"));
        Files.writeString(dir.resolve("web/orders/orders.sql"), "select 1 as id\n");
        Files.writeString(dir.resolve("web/orders/get.yml"), """
                version: tesseraql/v1
                id: orders.list
                kind: route
                recipe: %s
                sources:
                  main:
                    sql:
                      file: orders.sql
                  rates:
                    http:
                      url: https://fx.example.com/v1/rates
                      credential: fx-api
                %s
                response:
                  json:
                    status: 200
                    body:
                      rows: main.rows
                      fx: rates.body
                """.formatted(recipe, extra));
    }

    /** The same app with a retry: policy on the http source, for the TQL-YAML-1058 checks. */
    private static void writeRetryingApp(Path dir, String retry) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: t
                  http:
                    outbound:
                      allowedHosts:
                        - fx.example.com
                """);
        Files.createDirectories(dir.resolve("web/orders"));
        Files.writeString(dir.resolve("web/orders/orders.sql"), "select 1 as id\n");
        Files.writeString(dir.resolve("web/orders/get.yml"), """
                version: tesseraql/v1
                id: orders.list
                kind: route
                recipe: query-json
                sources:
                  main:
                    sql:
                      file: orders.sql
                  rates:
                    http:
                      url: https://fx.example.com/v1/rates
                      retry: %s
                response:
                  json:
                    status: 200
                    body:
                      rows: main.rows
                      fx: rates.body
                """.formatted(retry));
    }

    private static boolean hasRetryError(List<LintFinding> findings) {
        return findings.stream().anyMatch(finding -> finding.isError()
                && "TQL-YAML-1058".equals(finding.code()));
    }

    @Test
    void aWellFormedRetryPolicyLintsClean(@TempDir Path dir) throws Exception {
        writeRetryingApp(dir, "{ attempts: 3, backoff: 200ms, multiplier: 2 }");
        assertThat(new AppLinter().lint(dir)).noneMatch(LintFinding::isError);
    }

    /**
     * Past a handful of attempts a call is holding a thread against a dependency that is down,
     * which is the circuit breaker's job, not retry's.
     */
    @Test
    void tooManyAttemptsIsAnError(@TempDir Path dir) throws Exception {
        writeRetryingApp(dir, "{ attempts: 50 }");
        assertThat(hasRetryError(new AppLinter().lint(dir))).isTrue();
    }

    @Test
    void aRetryWithNoAttemptIsAnError(@TempDir Path dir) throws Exception {
        writeRetryingApp(dir, "{ attempts: 0 }");
        assertThat(hasRetryError(new AppLinter().lint(dir))).isTrue();
    }

    @Test
    void anUnparseableBackoffIsAnError(@TempDir Path dir) throws Exception {
        writeRetryingApp(dir, "{ attempts: 3, backoff: soon }");
        assertThat(hasRetryError(new AppLinter().lint(dir))).isTrue();
    }

    /** A backoff that shrinks is the hammering retry exists to avoid. */
    @Test
    void aShrinkingBackoffIsAnError(@TempDir Path dir) throws Exception {
        writeRetryingApp(dir, "{ attempts: 3, multiplier: 0.5 }");
        assertThat(hasRetryError(new AppLinter().lint(dir))).isTrue();
    }

    @Test
    void anAllowListedSourceOnAQueryRouteLintsClean(@TempDir Path dir) throws Exception {
        writeApp(dir, "query-json", "fx.example.com", "");
        assertThat(new AppLinter().lint(dir)).noneMatch(LintFinding::isError);
    }

    @Test
    void aDeniedHostIsAnError(@TempDir Path dir) throws Exception {
        writeApp(dir, "query-json", "other.example.com", "");
        assertThat(new AppLinter().lint(dir)).anyMatch(finding -> finding.isError()
                && "TQL-SEC-4070".equals(finding.code()));
    }

    @Test
    void httpSourcesAreNotAFileImportKey(@TempDir Path dir) throws Exception {
        writeApp(dir, "file-import", "fx.example.com", "");
        List<LintFinding> findings = new AppLinter().lint(dir);
        assertThat(findings).anyMatch(finding -> finding.isError()
                && "TQL-YAML-1022".equals(finding.code())
                && finding.message().contains("an http: source is supported"));
    }

    @Test
    void aCommandSourceMustAssertItIsAReference(@TempDir Path dir) throws Exception {
        // The call runs before the transaction and a rollback cannot un-make it, so the author
        // states that it is a read (docs/lookups.md, decision 19).
        writeApp(dir, "command-json", "fx.example.com", "");
        assertThat(new AppLinter().lint(dir)).anyMatch(finding -> finding.isError()
                && "TQL-YAML-1050".equals(finding.code()));
    }

    @Test
    void aCommandSourceAssertedReadOnlyLintsClean(@TempDir Path dir) throws Exception {
        writeApp(dir, "command-json", "fx.example.com", """
                      readOnly: true
                """);
        assertThat(new AppLinter().lint(dir)).noneMatch(LintFinding::isError);
    }

    @Test
    void twoSourcesCannotShareAName(@TempDir Path dir) throws Exception {
        writeApp(dir, "query-json", "fx.example.com", "");
        Files.writeString(dir.resolve("web/orders/count.sql"), "select 1 as n\n");
        Files.writeString(dir.resolve("web/orders/get.yml"), """
                version: tesseraql/v1
                id: orders.list
                kind: route
                recipe: query-json
                sources:
                  rates:
                    sql:
                      file: count.sql
                  rates:
                    http:
                      url: https://fx.example.com/v1/rates
                response:
                  json:
                    status: 200
                    body:
                      fx: rates.rows
                """);

        // One namespace makes the collision a duplicate key rather than a cross-map shadow,
        // so the lint that compared http: names against queries: names retires — and the
        // parser refuses the document outright instead of keeping the second silently.
        assertThatThrownBy(() -> new AppLinter().lint(dir))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("Duplicate field 'rates'");
    }

    @Test
    void aPostSourceWithABodyLintsClean(@TempDir Path dir) throws Exception {
        // A reference API that takes a list of keys is a POST; the read side is no longer
        // GET-only (docs/lookups.md, decision 16).
        writeApp(dir, "query-json", "fx.example.com", """
                      method: POST
                    body: params.codes
                """);
        assertThat(new AppLinter().lint(dir)).noneMatch(LintFinding::isError);
    }

    @Test
    void aBodyOnAMethodThatCarriesNoneIsAnError(@TempDir Path dir) throws Exception {
        writeApp(dir, "query-json", "fx.example.com", """
                      body: params.codes
                """);
        assertThat(new AppLinter().lint(dir)).anyMatch(finding -> finding.isError()
                && "TQL-YAML-1049".equals(finding.code())
                && finding.message().contains("GET"));
    }
}
