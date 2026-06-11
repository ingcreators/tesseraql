package io.tesseraql.yaml.manifest;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.yaml.model.JobDefinition;
import io.tesseraql.yaml.model.NotifySpec;
import io.tesseraql.yaml.model.RouteDefinition;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Parses the Phase 20 notification declarations: a {@code notify:} block on a command route and
 * a {@code notify:} pipeline step on a batch job.
 */
class NotifyBlockParsingTest {

    @Test
    void parsesTheNotifyBlockOfACommandRoute(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: members
                """);
        Path routeDir = dir.resolve("web/members");
        Files.createDirectories(routeDir);
        Files.writeString(routeDir.resolve("post.yml"), """
                version: tesseraql/v1
                id: members.register
                kind: route
                recipe: command-json
                input:
                  email:
                    type: string
                    required: true
                notify:
                  confirmation:
                    channel: member-mail
                    when: body.email != null
                    payload:
                      email: body.email
                  audit:
                    channel: audit-webhook
                    payload:
                      email: body.email
                      actor: principal.loginId
                sql:
                  file: insert-member.sql
                  mode: update
                  params:
                    email: body.email
                response:
                  json:
                    status: 201
                    body:
                      affected: sql.affectedRows
                """);

        AppManifest manifest = new ManifestLoader().load(dir);
        RouteDefinition route = manifest.routes().get(0).definition();

        // Notifications keep their authored order, like steps and validation rules.
        assertThat(route.notifications().keySet()).containsExactly("confirmation", "audit");

        NotifySpec confirmation = route.notifications().get("confirmation");
        assertThat(confirmation.channel()).isEqualTo("member-mail");
        assertThat(confirmation.when()).isEqualTo("body.email != null");
        assertThat(confirmation.payload()).containsEntry("email", "body.email");

        NotifySpec audit = route.notifications().get("audit");
        assertThat(audit.channel()).isEqualTo("audit-webhook");
        assertThat(audit.when()).isNull();
        assertThat(audit.payload()).containsEntry("actor", "principal.loginId");
    }

    @Test
    void parsesANotifyPipelineStep(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Path jobDir = dir.resolve("batch/cleanup");
        Files.createDirectories(jobDir);
        Files.writeString(jobDir.resolve("job.yml"), """
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

        AppManifest manifest = new ManifestLoader().load(dir);
        JobDefinition job = manifest.jobs().get(0).definition();

        assertThat(job.pipeline()).hasSize(2);
        assertThat(job.pipeline().get(0).sql()).isNotNull();
        assertThat(job.pipeline().get(0).notification()).isNull();
        assertThat(job.pipeline().get(1).sql()).isNull();
        assertThat(job.pipeline().get(1).notification().channel()).isEqualTo("ops-mail");
        assertThat(job.pipeline().get(1).notification().payload())
                .containsEntry("purged", "step.purge.affectedRows");
    }

    @Test
    void routeWithoutNotifyBlockHasNoNotifications(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Path routeDir = dir.resolve("web/members");
        Files.createDirectories(routeDir);
        Files.writeString(routeDir.resolve("post.yml"), """
                version: tesseraql/v1
                id: members.register
                kind: route
                recipe: command-json
                sql:
                  file: insert-member.sql
                  mode: update
                response:
                  json:
                    status: 201
                    body:
                      affected: sql.affectedRows
                """);

        AppManifest manifest = new ManifestLoader().load(dir);

        assertThat(manifest.routes().get(0).definition().notifications()).isEmpty();
    }
}
