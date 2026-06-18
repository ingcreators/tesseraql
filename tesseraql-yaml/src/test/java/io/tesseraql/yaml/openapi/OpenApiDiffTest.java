package io.tesseraql.yaml.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.yaml.openapi.OpenApiDiff.ApiChangelog;
import io.tesseraql.yaml.openapi.OpenApiDiff.ApiChangelog.Entry;
import io.tesseraql.yaml.openapi.OpenApiDiff.ApiChangelog.Kind;
import org.junit.jupiter.api.Test;

class OpenApiDiffTest {

    private final OpenApiDiff diff = new OpenApiDiff();

    @Test
    void identicalDocumentsProduceAnEmptyChangelog() {
        String doc = """
                { "paths": { "/a": { "get": { "operationId": "a.get",
                    "responses": { "200": { "description": "OK" } } } } } }
                """;
        assertThat(diff.diff(doc, doc).isEmpty()).isTrue();
    }

    @Test
    void addedAndRemovedOperationsAreReportedSortedByPathThenMethod() {
        String baseline = """
                { "paths": {
                    "/a": { "get": { "operationId": "a.get", "responses": {} } },
                    "/c": { "delete": { "operationId": "c.delete", "responses": {} } } } }
                """;
        String current = """
                { "paths": {
                    "/a": { "get": { "operationId": "a.get", "responses": {} } },
                    "/b": { "post": { "operationId": "b.post", "responses": {} } } } }
                """;

        ApiChangelog changelog = diff.diff(baseline, current);

        assertThat(changelog.entries()).extracting(Entry::kind, Entry::method, Entry::path)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(Kind.ADDED, "POST", "/b"),
                        org.assertj.core.groups.Tuple.tuple(Kind.REMOVED, "DELETE", "/c"));
        assertThat(changelog.count(Kind.ADDED)).isEqualTo(1);
        assertThat(changelog.count(Kind.REMOVED)).isEqualTo(1);
    }

    @Test
    void changedParametersReportRequiredTypeAddedAndRemoved() {
        String baseline = """
                { "paths": { "/users": { "get": { "operationId": "users.search",
                    "parameters": [
                      { "name": "q", "in": "query", "required": true, "schema": { "type": "integer" } },
                      { "name": "gone", "in": "query", "required": false, "schema": { "type": "string" } } ],
                    "responses": { "200": { "description": "OK" } } } } } }
                """;
        String current = """
                { "paths": { "/users": { "get": { "operationId": "users.search",
                    "parameters": [
                      { "name": "q", "in": "query", "required": false, "schema": { "type": "string" } },
                      { "name": "limit", "in": "query", "required": false, "schema": { "type": "integer" } } ],
                    "responses": { "200": { "description": "OK" } } } } } }
                """;

        ApiChangelog changelog = diff.diff(baseline, current);

        assertThat(changelog.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.kind()).isEqualTo(Kind.CHANGED);
            assertThat(entry.method()).isEqualTo("GET");
            assertThat(entry.path()).isEqualTo("/users");
            // Details are deterministic: parameters by key (gone, limit, q), then required, then type.
            assertThat(entry.details()).containsExactly(
                    "- query parameter gone",
                    "+ query parameter limit",
                    "query parameter q: now optional",
                    "query parameter q: type integer → string");
        });
    }

    @Test
    void changedResponsesRequestBodyAndSecurityAreReported() {
        String baseline = """
                { "paths": { "/orders": { "post": { "operationId": "orders.create",
                    "responses": { "200": { "description": "OK" } } } } } }
                """;
        String current = """
                { "paths": { "/orders": { "post": { "operationId": "orders.create",
                    "requestBody": { "required": true, "content": {} },
                    "security": [ { "bearerAuth": [] } ],
                    "responses": { "200": { "description": "OK" }, "404": { "description": "Not found" } } } } } }
                """;

        ApiChangelog changelog = diff.diff(baseline, current);

        assertThat(changelog.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.kind()).isEqualTo(Kind.CHANGED);
            assertThat(entry.details()).containsExactly(
                    "+ request body",
                    "+ response 404",
                    "security: none → bearerAuth");
        });
    }

    @Test
    void aChangedResponseSchemaIsReportedAsChanged() {
        String baseline = """
                { "paths": { "/a": { "get": { "operationId": "a.get",
                    "responses": { "200": { "description": "OK",
                        "content": { "application/json": { "schema": { "type": "object" } } } } } } } } }
                """;
        String current = """
                { "paths": { "/a": { "get": { "operationId": "a.get",
                    "responses": { "200": { "description": "OK",
                        "content": { "application/json": { "schema": { "type": "array" } } } } } } } } }
                """;

        assertThat(diff.diff(baseline, current).entries()).singleElement()
                .satisfies(entry -> assertThat(entry.details())
                        .containsExactly("response 200 changed"));
    }

    @Test
    void aRemovedRequestBodyIsReported() {
        String baseline = """
                { "paths": { "/o": { "post": { "operationId": "o.create",
                    "requestBody": { "required": true, "content": {} }, "responses": {} } } } }
                """;
        String current = """
                { "paths": { "/o": { "post": { "operationId": "o.create", "responses": {} } } } }
                """;

        assertThat(diff.diff(baseline, current).entries()).singleElement()
                .satisfies(entry -> assertThat(entry.details()).containsExactly("- request body"));
    }

    @Test
    void anEmptyOrMissingPathsBlockIsHandled() {
        assertThat(diff.diff("{}", "{}").isEmpty()).isTrue();
        String withOne = """
                { "paths": { "/a": { "get": { "operationId": "a.get", "responses": {} } } } }
                """;
        assertThat(diff.diff("{}", withOne).entries()).singleElement()
                .satisfies(entry -> assertThat(entry.kind()).isEqualTo(Kind.ADDED));
    }
}
