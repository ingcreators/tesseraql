package io.tesseraql.yaml.manifest;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.yaml.model.RouteDefinition;
import io.tesseraql.yaml.model.ValidationRule;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Parses the Phase 19 declarative-validation declarations: an ordered {@code validate:} block of
 * expression rules and validation SQL rules with field paths, rule codes, and message keys.
 */
class ValidateBlockParsingTest {

    @Test
    void parsesExpressionAndSqlRulesInAuthoredOrder(@TempDir Path dir) throws Exception {
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
                  startDate:
                    type: string
                  endDate:
                    type: string
                validate:
                  dateOrder:
                    when: body.endDate != null
                    rule: body.endDate >= body.startDate
                    field: endDate
                    code: end-before-start
                    message: members.dates.end-before-start
                  uniqueEmail:
                    file: check-email.sql
                    params:
                      email: body.email
                    field: email
                    code: duplicate
                    message: members.email.duplicate
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

        // Rules keep their authored order; each is either an expression or a SQL rule.
        assertThat(route.validate().keySet()).containsExactly("dateOrder", "uniqueEmail");

        ValidationRule dateOrder = route.validate().get("dateOrder");
        assertThat(dateOrder.isExpression()).isTrue();
        assertThat(dateOrder.isSql()).isFalse();
        assertThat(dateOrder.when()).isEqualTo("body.endDate != null");
        assertThat(dateOrder.rule()).isEqualTo("body.endDate >= body.startDate");
        assertThat(dateOrder.field()).isEqualTo("endDate");
        assertThat(dateOrder.code()).isEqualTo("end-before-start");
        assertThat(dateOrder.message()).isEqualTo("members.dates.end-before-start");

        ValidationRule uniqueEmail = route.validate().get("uniqueEmail");
        assertThat(uniqueEmail.isSql()).isTrue();
        assertThat(uniqueEmail.isExpression()).isFalse();
        assertThat(uniqueEmail.file()).isEqualTo("check-email.sql");
        assertThat(uniqueEmail.params()).containsEntry("email", "body.email");
        assertThat(uniqueEmail.field()).isEqualTo("email");
        assertThat(uniqueEmail.code()).isEqualTo("duplicate");
    }

    @Test
    void routeWithoutValidateBlockHasNoRules(@TempDir Path dir) throws Exception {
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

        assertThat(manifest.routes().get(0).definition().validate()).isEmpty();
    }
}
