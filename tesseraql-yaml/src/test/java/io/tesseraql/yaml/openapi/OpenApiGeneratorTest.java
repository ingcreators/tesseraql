package io.tesseraql.yaml.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestLoader;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

class OpenApiGeneratorTest {

    private static AppManifest exampleApp() {
        Path appHome = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        return new ManifestLoader().load(appHome);
    }

    @Test
    void generatesPathsMethodsAndSecurity() {
        String json = new OpenApiGenerator().toJson(exampleApp());

        assertThat(json).contains("\"openapi\" : \"3.0.3\"");
        assertThat(json).contains("\"/api/users\"").contains("\"operationId\" : \"users.search\"");
        assertThat(json).contains("\"bearerAuth\"");
        assertThat(json).contains("\"name\" : \"q\"").contains("\"in\" : \"query\"");
    }

    @Test
    void pathParametersAreIncluded() {
        String json = new OpenApiGenerator().toJson(exampleApp());
        // POST /api/admin/users/{id}/disable declares a path parameter id.
        assertThat(json).contains("\"name\" : \"id\"").contains("\"in\" : \"path\"");
    }

    @Test
    void recipesShapeResponses() {
        String json = new OpenApiGenerator().toJson(exampleApp());
        // HTML pages respond text/html; query-export streams the export format.
        assertThat(json).contains("\"text/html\"");
        assertThat(json).contains("\"text/csv; charset=utf-8\"");
        assertThat(json).contains("\"format\" : \"binary\"");
        // The browser-session scheme accompanies bearer in the components.
        assertThat(json).contains("\"sessionCookie\"").contains("\"tesseraql_sid\"");
    }

    @Test
    void jsonResponseSchemaMirrorsTheBodyStructure() throws Exception {
        com.fasterxml.jackson.databind.JsonNode schema = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(new OpenApiGenerator().toJson(exampleApp()))
                .path("paths").path("/api/users").path("get").path("responses").path("200")
                .path("content").path("application/json").path("schema");

        // The response.json.body structure is mirrored with property names (not just {type:object}).
        assertThat(schema.path("type").asText()).isEqualTo("object");
        com.fasterxml.jackson.databind.JsonNode props = schema.path("properties");
        // data: sql.rows -> an array of row objects
        assertThat(props.path("data").path("type").asText()).isEqualTo("array");
        assertThat(props.path("data").path("items").path("type").asText()).isEqualTo("object");
        // meta: a nested object; count is a row count (integer); limit/offset take their input types.
        com.fasterxml.jackson.databind.JsonNode meta = props.path("meta").path("properties");
        assertThat(meta.path("count").path("type").asText()).isEqualTo("integer");
        assertThat(meta.path("limit").path("type").asText()).isEqualTo("integer");
        assertThat(meta.path("offset").path("type").asText()).isEqualTo("integer");
    }

    @Test
    @SuppressWarnings("unchecked")
    void transferRoutesDocumentUploadAcknowledgementAndSubpaths() {
        io.tesseraql.yaml.SimpleYamlParser parser = new io.tesseraql.yaml.SimpleYamlParser();
        Path home = Path.of("/app").toAbsolutePath().normalize();
        var importRoute = new io.tesseraql.yaml.manifest.RouteFile("post", "/api/items/import",
                home.resolve("web/api/items/import/post.yml"), parser.parseRoute("""
                        version: tesseraql/v1
                        id: items.import
                        kind: route
                        recipe: file-import
                        import:
                          format: csv
                          columns: [name]
                          sql:
                            file: upsert.sql
                        """, "import"));
        var exportRoute = new io.tesseraql.yaml.manifest.RouteFile("post", "/api/items/export",
                home.resolve("web/api/items/export/post.yml"), parser.parseRoute("""
                        version: tesseraql/v1
                        id: items.export
                        kind: route
                        recipe: file-export
                        export:
                          format: excel
                          filename: items.xlsx
                          sql:
                            file: select.sql
                        """, "export"));
        var commandRoute = new io.tesseraql.yaml.manifest.RouteFile("post", "/api/items",
                home.resolve("web/api/items/post.yml"), parser.parseRoute("""
                        version: tesseraql/v1
                        id: items.create
                        kind: route
                        recipe: command-json
                        input:
                          name:
                            type: string
                            required: true
                        sql:
                          file: insert.sql
                          mode: update
                        """, "command"));
        AppManifest manifest = new AppManifest(home,
                new io.tesseraql.yaml.config.AppConfig(java.util.Map.of(), name -> null),
                java.util.List.of(importRoute, exportRoute, commandRoute), java.util.List.of(),
                java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(),
                java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(),
                java.util.List.of(),
                io.tesseraql.yaml.manifest.ManifestIndex.of(java.util.Map.of(), "test"));

        java.util.Map<String, Object> doc = new OpenApiGenerator().generate(manifest);
        var paths = (java.util.Map<String, Object>) doc.get("paths");
        // The compiler-mounted status and download subpaths are part of the contract.
        assertThat(paths).containsKeys("/api/items/import", "/api/items/import/{transferId}",
                "/api/items/export", "/api/items/export/{transferId}",
                "/api/items/export/{transferId}/file");

        String json = new OpenApiGenerator().toJson(manifest);
        // Uploads accept a raw body or a multipart "file" part and acknowledge with 202.
        assertThat(json).contains("\"multipart/form-data\"").contains("\"202\"")
                .contains("TransferAccepted").contains("TransferStatus");
        // The export download declares the Excel content type and the busy/unknown statuses.
        assertThat(json)
                .contains("\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet\"")
                .contains("\"409\"");
        // A POST route's declared inputs become a JSON request body schema.
        assertThat(json).contains("\"requestBody\"")
                .contains("\"required\" : [ \"name\" ]");
    }

    @Test
    void outputIsDeterministic() {
        AppManifest manifest = exampleApp();
        OpenApiGenerator generator = new OpenApiGenerator();
        assertThat(generator.toJson(manifest)).isEqualTo(generator.toJson(manifest));
    }
}
