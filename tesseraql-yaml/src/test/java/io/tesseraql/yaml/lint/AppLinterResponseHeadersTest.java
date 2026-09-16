package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Lints around the app-wide default response headers (docs/route-defaults.md). */
class AppLinterResponseHeadersTest {

    private Path app(@TempDir Path dir, String routeHeaders) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: t
                  security:
                    responseHeaders:
                      X-Frame-Options: DENY
                      Content-Security-Policy: "default-src 'self'"
                """);
        Files.createDirectories(dir.resolve("web/home"));
        Files.writeString(dir.resolve("web/home/get.yml"), """
                version: tesseraql/v1
                id: home.page
                kind: route
                recipe: query-html
                security:
                  auth: browser
                sources:
                  main:
                    sql:
                      file: home.sql
                response:
                  html:
                    template: home.html
                %s
                """.formatted(routeHeaders));
        Files.writeString(dir.resolve("web/home/home.sql"), "select 1\n");
        Files.createDirectories(dir.resolve("templates"));
        Files.writeString(dir.resolve("templates/home.html"), "<main></main>\n");
        return dir;
    }

    @Test
    void flagsAnIdenticalRestatement(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir,
                "    headers:\n      X-Frame-Options: DENY"));

        assertThat(findings).anyMatch(f -> f.code().equals("TQL-SEC-4133") && !f.isError()
                && f.source().equals("web/home/get.yml"));
    }

    @Test
    void flagsSuppressionAndWildcardBroadening(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir,
                "    headers:\n      X-Frame-Options: unset\n"
                        + "      Content-Security-Policy: \"default-src *\""));

        assertThat(findings.stream()
                .filter(f -> f.code().equals("TQL-SEC-4134")).count()).isEqualTo(2);
    }

    @Test
    void aDifferentiatedOverrideAndForeignHeadersLintClean(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir,
                "    headers:\n"
                        + "      Content-Security-Policy: \"default-src 'self'; frame-src 'self' data:\"\n"
                        + "      HX-Trigger: saved"));

        assertThat(findings).noneMatch(f -> f.code().startsWith("TQL-SEC-413"));
    }
    /**
     * A JSON route is linted against the defaults too.
     *
     * <p>Only {@code response.html} was inspected, so once the defaults began merging into JSON
     * responses as well (docs/route-defaults.md) a JSON route could restate or weaken one of them
     * without a word — the same drift these lints exist to end, in the arm they did not reach.
     */
    @Test
    void flagsARestatementOnAJsonRoute(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: t
                  security:
                    responseHeaders:
                      X-Frame-Options: DENY
                """);
        Files.createDirectories(dir.resolve("web/api/items"));
        Files.writeString(dir.resolve("web/api/items/get.yml"), """
                version: tesseraql/v1
                id: items.list
                kind: route
                recipe: query-json
                security:
                  auth: public
                sources:
                  main:
                    sql:
                      file: items.sql
                response:
                  json:
                    status: 200
                    headers:
                      X-Frame-Options: DENY
                    body:
                      ok: true
                """);
        Files.writeString(dir.resolve("web/api/items/items.sql"), "select 1\n");

        List<LintFinding> findings = new AppLinter().lint(dir);

        assertThat(findings).anyMatch(f -> f.code().equals("TQL-SEC-4133") && !f.isError()
                && f.source().equals("web/api/items/get.yml"));
    }

    /**
     * A literal control character in a route's declared header is named at build time
     * (docs/edge-hygiene.md E3), where the edge would refuse it on every request; a
     * placeholder's value is judged only there, and a map value serializes to JSON, which
     * escapes its own.
     */
    @Test
    void refusesADeclaredHeaderValueWithAControlCharacter(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir,
                "    headers:\n      X-Note: \"line one\\nline two\"\n      X-Tab: \"a\\tb\""));

        assertThat(findings).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-SEC-4151");
            assertThat(finding.isError()).isTrue();
            assertThat(finding.message()).contains("X-Note").contains("U+000A");
        });
        // HTAB is the one control a field value may carry (RFC 9110 section 5.5).
        assertThat(findings).noneSatisfy(finding -> assertThat(finding.message())
                .contains("X-Tab"));
    }

    /** The config twin: the same predicate the boot runs, so lint and boot cannot disagree. */
    @Test
    void refusesADefaultHeaderValueWithAControlCharacter(@TempDir Path dir) throws Exception {
        Path home = app(dir, "");
        Files.writeString(home.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: t
                  security:
                    responseHeaders:
                      X-Frame-Options: "DENY\\u0000"
                """);

        assertThat(new AppLinter().lint(home)).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-SEC-4135");
            assertThat(finding.isError()).isTrue();
            assertThat(finding.message()).contains("X-Frame-Options").contains("U+0000");
        });
    }

    /**
     * A declared header the transport owns is refused at build time (TQL-SEC-4139): the edge
     * would drop it at the wire, and a declaration that is never sent is a promise the app
     * cannot keep — framing names corrupt the response, and the {@code tql.} namespace never
     * leaves the runtime.
     */
    @Test
    void refusesADeclaredTransportOwnedHeader(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir,
                "    headers:\n      Content-Length: \"100\"\n      tql.acting.role: admin"));

        assertThat(findings.stream()
                .filter(f -> f.code().equals("TQL-SEC-4139") && f.isError())
                .count()).isEqualTo(2);
    }

    /**
     * Findings come out in the order the declaration was written, because {@code tesseraql lint
     * --format json} serializes this list verbatim — two runs over identical sources have to
     * produce identical bytes. The four names have eight reachable iteration orders and the
     * authored one is not among them, so this was red on every boot before the response model
     * kept its order (docs/deterministic-output.md decision 2).
     */
    @Test
    void findingsFollowTheOrderTheHeadersWereDeclaredIn(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir,
                "    headers:\n      Content-Length: \"100\"\n"
                        + "      Transfer-Encoding: chunked\n      Connection: close\n"
                        + "      Trailer: Expires"));

        assertThat(findings.stream()
                .filter(f -> f.code().equals("TQL-SEC-4139"))
                .map(LintFinding::message))
                .hasSize(4)
                .allSatisfy(message -> assertThat(message).contains("'"));
        assertThat(findings.stream()
                .filter(f -> f.code().equals("TQL-SEC-4139"))
                .map(f -> f.message().replaceAll("(?s).*response header '([^']+)'.*", "$1")))
                .containsExactly("Content-Length", "Transfer-Encoding", "Connection", "Trailer");
    }

    /**
     * A declared header's NAME is judged (docs/audit-low-leads.md slice 9, DN-02a): a key with
     * a space linted clean and hung the route on every request, because the name reached
     * Vert.x inside the transport. Named here at build time; the edge refuses it as a 500.
     */
    @Test
    void refusesADeclaredHeaderWhoseNameIsNotAToken(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir,
                "    headers:\n      \"X-Typo Name\": v\n      \"X-Ok\": v"));

        assertThat(findings).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-SEC-4152");
            assertThat(finding.isError()).isTrue();
            assertThat(finding.message()).contains("'X-Typo Name'").contains("U+0020")
                    .contains("edge refuses it");
        });
        assertThat(findings).noneSatisfy(finding -> assertThat(finding.message())
                .contains("'X-Ok'"));
    }

    /** The config twin: a name the transport owns is the boot's refusal too, under its code. */
    @Test
    void refusesADefaultHeaderWhoseNameIsNotAToken(@TempDir Path dir) throws Exception {
        Path home = app(dir, "");
        Files.writeString(home.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: t
                  security:
                    responseHeaders:
                      "X Space": typo
                """);

        assertThat(new AppLinter().lint(home)).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-SEC-4135");
            assertThat(finding.isError()).isTrue();
            assertThat(finding.source()).isEqualTo("config");
            assertThat(finding.message()).contains("'X Space'").contains("U+0020");
        });
    }

    /**
     * A {@code Content-Disposition} written by hand from a placeholder is neither quoted nor
     * encoded (docs/audit-low-leads.md slice 9, DN-02c): a quote in the caller's value ends
     * the name and starts a parameter, a non-ASCII name folds to {@code ?}. The download
     * recipe's {@code filename:} does both, so the finding points there. A literal
     * disposition is left alone — {@code inline} has no other spelling — and a JSON route is
     * held to the same rule.
     */
    @Test
    void aPlaceholderFilenameInADeclaredContentDispositionIsPointedAtTheFileRecipe(
            @TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir,
                "    headers:\n      Content-Disposition: 'attachment; filename=\"{params.name}\"'"));

        assertThat(findings).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-SEC-4153");
            assertThat(finding.severity()).isEqualTo("warning");
            assertThat(finding.message()).contains("placeholder")
                    .contains("response.file: filename:");
        });
    }

    @Test
    void aLiteralInlineDispositionLintsClean(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir,
                "    headers:\n      Content-Disposition: inline\n"
                        + "      X-Name: \"{params.name}\""));

        assertThat(findings).noneMatch(finding -> finding.code().equals("TQL-SEC-4153"));
    }

    @Test
    void aJsonRouteIsHeldToTheDispositionRuleToo(@TempDir Path dir) throws Exception {
        Path home = app(dir, "");
        Files.createDirectories(home.resolve("web/api/dl"));
        Files.writeString(home.resolve("web/api/dl/get.yml"), """
                version: tesseraql/v1
                id: api.dl
                kind: route
                recipe: query-json
                security:
                  auth: public
                input:
                  name:
                    type: string
                response:
                  json:
                    body:
                      ok: true
                    headers:
                      content-disposition: "attachment; filename={params.name}"
                """);

        assertThat(new AppLinter().lint(home)).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-SEC-4153");
            assertThat(finding.source()).isEqualTo("web/api/dl/get.yml");
        });
    }

    /** The app-wide defaults are held to the same rule, named as config. */
    @Test
    void refusesAReservedDefaultHeader(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: t
                  security:
                    responseHeaders:
                      Connection: close
                """);

        List<LintFinding> findings = new AppLinter().lint(dir);

        assertThat(findings).anyMatch(f -> f.code().equals("TQL-SEC-4139") && f.isError()
                && f.source().equals("config"));
    }

    /** And a declared JSON header the defaults say nothing about is not a finding. */
    @Test
    void leavesAJsonHeaderWithNoMatchingDefaultAlone(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: t
                  security:
                    responseHeaders:
                      X-Frame-Options: DENY
                """);
        Files.createDirectories(dir.resolve("web/api/items"));
        Files.writeString(dir.resolve("web/api/items/get.yml"), """
                version: tesseraql/v1
                id: items.list
                kind: route
                recipe: query-json
                security:
                  auth: public
                sources:
                  main:
                    sql:
                      file: items.sql
                response:
                  json:
                    status: 200
                    headers:
                      Cache-Control: no-store
                    body:
                      ok: true
                """);
        Files.writeString(dir.resolve("web/api/items/items.sql"), "select 1\n");

        List<LintFinding> findings = new AppLinter().lint(dir);

        assertThat(findings).noneMatch(f -> f.code().startsWith("TQL-SEC-413"));
    }
}
