package io.tesseraql.yaml.governance;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The marketplace admission profile (roadmap Phase 47): declarative-only, deny-by-default
 * policies DEFINED, bounded egress, CSP intact, lint clean — and the reference apps clear it.
 */
class AdmissionProfileTest {

    private Path cleanApp(Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: admitted
                  security:
                    policies:
                      app.read:
                        anyOf:
                          - role: APP_READ
                """);
        Path api = dir.resolve("web/api/items");
        Files.createDirectories(api);
        Files.writeString(api.resolve("items.sql"), "select 1 as x\n");
        Files.writeString(api.resolve("get.yml"), """
                version: tesseraql/v1
                id: items.list
                kind: route
                recipe: query-json
                security:
                  auth: bearer
                  policy: app.read
                sql:
                  file: items.sql
                  mode: query
                response:
                  json:
                    body:
                      data: sql.rows
                """);
        return dir;
    }

    @Test
    void aCleanDeclarativeAppIsAdmitted(@TempDir Path dir) throws Exception {
        AdmissionProfile.Report report = AdmissionProfile.check(cleanApp(dir));
        assertThat(report.failures()).isEmpty();
        assertThat(report.admitted()).isTrue();
    }

    @Test
    void undefinedPolicyServiceBindingUnboundedEgressAndMissingCspAllFail(@TempDir Path dir)
            throws Exception {
        cleanApp(dir);
        // Reference a policy the config does NOT define (lint warns; admission fails).
        Path api = dir.resolve("web/api/loose");
        Files.createDirectories(api);
        Files.writeString(api.resolve("loose.sql"), "select 1 as x\n");
        Files.writeString(api.resolve("get.yml"), """
                version: tesseraql/v1
                id: loose.list
                kind: route
                recipe: query-json
                security:
                  auth: bearer
                  policy: nowhere.defined
                sql:
                  file: loose.sql
                  mode: query
                response:
                  json:
                    body:
                      data: sql.rows
                """);
        // A service binding -> mode extended -> declarative-only failure.
        Path svc = dir.resolve("web/api/svc");
        Files.createDirectories(svc);
        Files.writeString(svc.resolve("get.yml"), """
                version: tesseraql/v1
                id: svc.view
                kind: route
                recipe: query-json
                security:
                  auth: bearer
                  policy: app.read
                sql:
                  service: some.provider
                response:
                  json:
                    body:
                      data: sql
                """);
        // An HTML page without CSP headers.
        Path page = dir.resolve("web/page");
        Files.createDirectories(page);
        Files.writeString(page.resolve("page.sql"), "select 1 as x\n");
        Files.writeString(page.resolve("page.html"), "<html><body>x</body></html>\n");
        Files.writeString(page.resolve("get.yml"), """
                version: tesseraql/v1
                id: page.view
                kind: route
                recipe: query-html
                security:
                  auth: browser
                sql:
                  file: page.sql
                  mode: query
                response:
                  html:
                    status: 200
                    template: page.html
                    model:
                      rows: sql.rows
                """);
        // Unbounded egress.
        Files.writeString(dir.resolve("config/overlay.yml"), """
                tesseraql:
                  http:
                    outbound:
                      allowedHosts:
                        - "*"
                """);

        AdmissionProfile.Report report = AdmissionProfile.check(dir);

        assertThat(report.admitted()).isFalse();
        assertThat(report.failures())
                .extracting(AdmissionProfile.Finding::code)
                .contains("TQL-ADM-4701", "TQL-ADM-4702", "TQL-ADM-4703", "TQL-ADM-4704");
        assertThat(report.failures().stream()
                .filter(f -> f.code().equals("TQL-ADM-4701"))
                .map(AdmissionProfile.Finding::subject)).contains("svc.view");
    }

    @Test
    void aPluginJarFailsDeclarativeOnly(@TempDir Path dir) throws Exception {
        cleanApp(dir);
        Files.createDirectories(dir.resolve("plugins"));
        Files.write(dir.resolve("plugins/custom.jar"), new byte[]{0x50, 0x4b});

        AdmissionProfile.Report report = AdmissionProfile.check(dir);
        assertThat(report.failures()).extracting(AdmissionProfile.Finding::code)
                .contains("TQL-ADM-4701");
    }

    @Test
    void theShippedExampleAppsClearTheProfile() {
        // The gallery bar (roadmap Phase 47): what we ship must pass what we gate others on.
        Path examples = Path.of("..", "examples").toAbsolutePath().normalize();
        for (String app : new String[]{"scaffold-demo-app", "user-admin-app"}) {
            AdmissionProfile.Report report = AdmissionProfile.check(examples.resolve(app));
            assertThat(report.failures())
                    .as(app + " admission failures")
                    .isEmpty();
        }
    }
}
