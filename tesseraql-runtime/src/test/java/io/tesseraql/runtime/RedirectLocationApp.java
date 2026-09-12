package io.tesseraql.runtime;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The YAML app behind {@link RedirectLocationIntegrationTest}: landing routes at DISTINCT
 * statuses (root 230, Japanese 228, ASCII 229, Latin-1 227, the protected pages 226/225), the
 * redirect sources, the documented {@code headers: Location} recipe, an api-key attachment
 * document under a Japanese basePath, a paged list under a Japanese path (its {@code Link}
 * header), and — for the prefixed runtime — SCIM. Parameters: the runtime's base path ("" or
 * "/受注") and whether SCIM is enabled.
 */
final class RedirectLocationApp {

    private RedirectLocationApp() {
    }

    static Path prepare(String jdbcUrl, String username, String password, String basePath,
            String apiKeyHash, boolean scim) throws Exception {
        Path home = Files.createTempDirectory("tesseraql-redirect-location-it");
        Files.createDirectories(home.resolve("config"));
        String base = basePath.isEmpty() ? "" : "\n  http:\n    basePath: \"" + basePath + "\"";
        String scimBlock = !scim
                ? ""
                : "\n  scim:\n    enabled: true\n    users:\n"
                        + "      create: scim/create-user.sql\n      keys: [id]\n"
                        + "      findById: scim/find-user.sql\n      list: scim/list-users.sql\n"
                        + "      replace: scim/replace-user.sql\n      delete: scim/delete-user.sql\n"
                        + "      findByUserName: scim/find-user-by-name.sql\n      count: scim/count-users.sql";
        Files.writeString(home.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: redirect-location%s%s
                  security:
                    jwt:
                      secret: dev-only-secret-change-me-in-production
                      audience:
                        - https://app.example.com
                      rolesClaim: roles
                    policies:
                      data.read:
                        anyOf:
                          - role: READER
                      scim.manage:
                        anyOf:
                          - role: SCIM
                    apiKeys:
                      label: X-API-Key
                      clients:
                        userA:
                          secretHash: %s
                          subject: user-a
                          roles: [READER]
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                """.formatted(base, scimBlock, apiKeyHash, jdbcUrl, username, password));
        if (scim) {
            scimSql(home.resolve("scim"));
        }
        Files.createDirectories(home.resolve("attachments"));
        Files.writeString(home.resolve("attachments/uploads.yml"), """
                version: tesseraql/v1
                id: uploads
                kind: attachment
                basePath: /添付/{recordId}/files
                record: { entity: record, key: recordId }
                security:
                  auth: api-key
                  policy: data.read
                """);
        landing(home, "", "land.root", 230);
        landing(home, "受注一覧", "land.jp", 228);
        landing(home, "landed", "land.ascii", 229);
        landing(home, "café", "land.latin", 227);
        redirect(home, "go/jp", "go.jp", "/受注一覧");
        redirect(home, "go/latin", "go.latin", "/café");
        redirect(home, "go/pre", "go.pre", "/caf%C3%A9");
        redirect(home, "go/premix", "go.premix", "/受注/caf%C3%A9");
        redirect(home, "go/q", "go.q", "/受注一覧?x=1");
        redirect(home, "go/ascii", "go.ascii", "/landed");
        redirect(home, "go/back", "go.back", "back");
        redirect(home, "go/absjp", "go.absjp", "https://example.test/受注");
        redirect(home, "go/brace", "go.brace", "/a|b^c");
        route(home, "go/expr", """
                version: tesseraql/v1
                id: go.expr
                kind: route
                recipe: query-json
                input:
                  name:
                    in: query
                    type: string
                  id:
                    in: query
                    type: string
                response:
                  redirect:
                    status: 303
                    location: /orders/{params.name}/{params.id}
                """);
        route(home, "go/hdr", """
                version: tesseraql/v1
                id: go.hdr
                kind: route
                recipe: query-json
                input:
                  id:
                    in: query
                    type: string
                response:
                  json:
                    status: 201
                    headers:
                      Location: "/api/items/{params.id}"
                      X-Msg: "{params.id}"
                """);
        route(home, "go/hxhdr", """
                version: tesseraql/v1
                id: go.hxhdr
                kind: route
                recipe: query-json
                input:
                  id:
                    in: query
                    type: string
                response:
                  json:
                    status: 200
                    headers:
                      HX-Redirect: "/x/{params.id}"
                """);
        route(home, "authored", """
                version: tesseraql/v1
                id: authored.next
                kind: route
                recipe: query-json
                input:
                  next:
                    in: query
                    type: string
                response:
                  json:
                    status: 302
                    headers:
                      Location: "{params.next}"
                """);
        secret(home, "受注/secret", "land.secret.jp", 226);
        secret(home, "plain/secret", "land.secret.plain", 225);
        // The smallest paged list under a Japanese path, for the Link header row: one row a
        // page over the two seeded order lines, so page 1 has a next and page 2 a prev.
        route(home, "受注/明細", """
                version: tesseraql/v1
                id: list.jp
                kind: route
                recipe: query-json
                sources:
                  main:
                    sql:
                      file: lines.sql
                pagination:
                  size: 1
                response:
                  json:
                    status: 200
                    body:
                      data: main.rows
                """);
        Files.writeString(home.resolve("web/受注/明細/lines.sql"),
                "select id, item from order_lines order by id\n");
        return home;
    }

    private static void landing(Path home, String path, String id, int status) throws Exception {
        route(home, path, """
                version: tesseraql/v1
                id: %s
                kind: route
                recipe: query-json
                response:
                  json:
                    status: %d
                """.formatted(id, status));
    }

    private static void secret(Path home, String path, String id, int status) throws Exception {
        route(home, path, """
                version: tesseraql/v1
                id: %s
                kind: route
                recipe: query-json
                security:
                  auth: browser
                input:
                  q:
                    in: query
                    type: string
                    required: false
                  r:
                    in: query
                    type: string
                    required: false
                response:
                  json:
                    status: %d
                    headers:
                      X-Q: "{params.q}"
                      X-R: "{params.r}"
                """.formatted(id, status));
    }

    private static void redirect(Path home, String path, String id, String location)
            throws Exception {
        route(home, path, """
                version: tesseraql/v1
                id: %s
                kind: route
                recipe: query-json
                response:
                  redirect:
                    status: 303
                    location: "%s"
                """.formatted(id, location));
    }

    private static void route(Path home, String path, String yaml) throws Exception {
        Path dir = path.isEmpty() ? home.resolve("web") : home.resolve("web").resolve(path);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("get.yml"), yaml);
    }

    private static void scimSql(Path scim) throws Exception {
        Files.createDirectories(scim);
        String columns = "select id, user_name as \"userName\", given_name as \"givenName\","
                + " family_name as \"familyName\", email as \"email\", active as \"active\","
                + " external_id as \"externalId\" from scim_users";
        Files.writeString(scim.resolve("create-user.sql"),
                """
                        insert into scim_users (user_name, given_name, family_name, email, active, external_id)
                        values (/* userName */ 'u', /* givenName */ 'g', /* familyName */ 'f',
                                /* email */ 'e', /* active */ true, /* externalId */ 'x')
                        """);
        Files.writeString(scim.resolve("find-user.sql"),
                columns + " where id::text = /* id */ '0'\n");
        Files.writeString(scim.resolve("list-users.sql"), columns
                + " order by id limit /* count */ 100 offset (/* startIndex */ 1 - 1)\n");
        Files.writeString(scim.resolve("replace-user.sql"),
                """
                        update scim_users set user_name = /* userName */ 'u', given_name = /* givenName */ 'g',
                               family_name = /* familyName */ 'f', email = /* email */ 'e',
                               active = /* active */ true, external_id = /* externalId */ 'x'
                        where id::text = /* id */ '0'
                        """);
        Files.writeString(scim.resolve("delete-user.sql"),
                "delete from scim_users where id::text = /* id */ '0'\n");
        Files.writeString(scim.resolve("find-user-by-name.sql"),
                columns + " where user_name = /* userName */ 'u'\n");
        Files.writeString(scim.resolve("count-users.sql"),
                "select count(*) as \"totalResults\" from scim_users\n");
    }
}
