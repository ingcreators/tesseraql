package io.tesseraql.yaml.openapi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.RouteFile;
import io.tesseraql.yaml.model.InputField;
import io.tesseraql.yaml.model.RouteDefinition;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates a deterministic OpenAPI 3 document from the route manifest (design ch. 22.18). The
 * generated document is a derived artifact; the source of truth remains the Simple YAML routes.
 *
 * <p>Recipes shape their operations: JSON recipes respond {@code application/json} (non-GET inputs
 * become a JSON request body), HTML recipes respond {@code text/html}, {@code query-export}
 * responds with the export format's file, and the file-transfer recipes document their 202
 * acknowledgement plus the {@code {transferId}} status and {@code /file} download subpaths the
 * compiler mounts.
 */
public final class OpenApiGenerator {

    private static final TqlErrorCode GEN_ERROR = new TqlErrorCode(TqlDomain.REPORT, 2001);
    private static final Pattern PATH_PARAM = Pattern.compile("\\{(\\w+)\\}");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, String> EXPORT_CONTENT_TYPES = Map.of(
            "csv", "text/csv; charset=utf-8",
            "excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    /** Builds the OpenAPI document tree (deterministically ordered). */
    public Map<String, Object> generate(AppManifest manifest) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("openapi", "3.0.3");

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("title",
                manifest.config().getString("tesseraql.app.name").orElse("tesseraql-app"));
        info.put("version", manifest.config().getString("app.version").orElse("1.0.0"));
        doc.put("info", info);

        Map<String, Object> paths = new TreeMap<>();
        for (RouteFile route : manifest.routes()) {
            pathItem(paths, route.urlPath()).put(route.httpMethod().toLowerCase(),
                    operation(route));
            addTransferSubpaths(paths, route);
        }
        doc.put("paths", paths);
        doc.put("components", components());
        return doc;
    }

    /** Serializes the OpenAPI document as pretty JSON. */
    public String toJson(AppManifest manifest) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(generate(manifest));
        } catch (JsonProcessingException ex) {
            throw new TqlException(GEN_ERROR, "Failed to serialize OpenAPI: " + ex.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> pathItem(Map<String, Object> paths, String path) {
        return (Map<String, Object>) paths.computeIfAbsent(path,
                key -> new TreeMap<String, Object>());
    }

    private Map<String, Object> operation(RouteFile route) {
        RouteDefinition definition = route.definition();
        String recipe = definition.recipe();
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("operationId", definition.id());

        List<Object> parameters = new ArrayList<>();
        Matcher matcher = PATH_PARAM.matcher(route.urlPath());
        while (matcher.find()) {
            parameters.add(parameter(matcher.group(1), "path", true, "string"));
        }
        // GET/DELETE inputs bind from the query string; other methods carry a JSON body
        // (except file-import, whose body is the uploaded file itself).
        boolean queryInputs = "GET".equalsIgnoreCase(route.httpMethod())
                || "DELETE".equalsIgnoreCase(route.httpMethod());
        if (queryInputs) {
            new TreeMap<>(definition.input()).forEach((name, field) -> parameters
                    .add(parameter(name, "query", field.required(), schemaType(field))));
        }
        if (!parameters.isEmpty()) {
            operation.put("parameters", parameters);
        }
        if ("file-import".equals(recipe)) {
            operation.put("requestBody", uploadBody());
        } else if (!queryInputs && !definition.input().isEmpty()) {
            operation.put("requestBody", jsonBody(definition));
        }

        security(definition).ifPresent(value -> operation.put("security", value));
        operation.put("responses", responses(definition));
        return operation;
    }

    /** The responses by recipe: JSON, HTML, a streamed file, or the async 202 acknowledgement. */
    private Map<String, Object> responses(RouteDefinition definition) {
        Map<String, Object> responses = new TreeMap<>();
        switch (definition.recipe() == null ? "" : definition.recipe()) {
            case "file-import", "file-export" -> responses.put("202", withContent(
                    "Transfer accepted", "application/json", ref("TransferAccepted")));
            case "query-export" -> responses.put("200", withContent("The exported file",
                    exportContentType(definition), ordered("type", "string", "format", "binary")));
            case "query-html", "page" -> responses.put("200", withContent("OK", "text/html",
                    Map.of("type", "string")));
            default -> responses.put("200", withContent("OK", "application/json",
                    Map.of("type", "object")));
        }
        return responses;
    }

    /** The status (and for exports, download) subpaths the compiler mounts per transfer route. */
    private void addTransferSubpaths(Map<String, Object> paths, RouteFile route) {
        RouteDefinition definition = route.definition();
        String recipe = definition.recipe();
        if (!"file-import".equals(recipe) && !"file-export".equals(recipe)) {
            return;
        }
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("operationId", definition.id() + ".status");
        status.put("parameters", List.of(parameter("transferId", "path", true, "string")));
        security(definition).ifPresent(value -> status.put("security", value));
        Map<String, Object> statusResponses = new TreeMap<>();
        statusResponses.put("200", withContent("Transfer state", "application/json",
                ref("TransferStatus")));
        statusResponses.put("404", Map.of("description", "Unknown transfer"));
        status.put("responses", statusResponses);
        pathItem(paths, route.urlPath() + "/{transferId}").put("get", status);

        if ("file-export".equals(recipe)) {
            Map<String, Object> file = new LinkedHashMap<>();
            file.put("operationId", definition.id() + ".file");
            file.put("parameters", List.of(parameter("transferId", "path", true, "string")));
            security(definition).ifPresent(value -> file.put("security", value));
            Map<String, Object> fileResponses = new TreeMap<>();
            fileResponses.put("200", withContent("The exported file",
                    exportContentType(definition), ordered("type", "string", "format", "binary")));
            fileResponses.put("404", Map.of("description", "Unknown transfer"));
            fileResponses.put("409", Map.of("description", "Transfer not completed yet"));
            file.put("responses", fileResponses);
            pathItem(paths, route.urlPath() + "/{transferId}/file").put("get", file);
        }
    }

    private static String exportContentType(RouteDefinition definition) {
        String format = definition.fileExport() == null || definition.fileExport().format() == null
                ? "csv"
                : definition.fileExport().format();
        return EXPORT_CONTENT_TYPES.getOrDefault(format, "application/octet-stream");
    }

    private static java.util.Optional<Object> security(RouteDefinition definition) {
        if (definition.security() == null || definition.security().auth() == null) {
            return java.util.Optional.empty();
        }
        return switch (definition.security().auth()) {
            case "bearer" -> java.util.Optional.of(List.of(Map.of("bearerAuth", List.of())));
            case "browser" -> java.util.Optional.of(List.of(Map.of("sessionCookie", List.of())));
            default -> java.util.Optional.empty();
        };
    }

    /** A JSON request body whose object schema mirrors the route's declared inputs. */
    private static Map<String, Object> jsonBody(RouteDefinition definition) {
        Map<String, Object> properties = new TreeMap<>();
        List<String> required = new ArrayList<>();
        new TreeMap<>(definition.input()).forEach((name, field) -> {
            properties.put(name, Map.of("type", schemaType(field)));
            if (field.required()) {
                required.add(name);
            }
        });
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("required", true);
        body.put("content", Map.of("application/json", Map.of("schema", schema)));
        return body;
    }

    /** The uploaded file for {@code file-import}: a raw body or a multipart {@code file} part. */
    private static Map<String, Object> uploadBody() {
        Map<String, Object> binary = ordered("type", "string", "format", "binary");
        Map<String, Object> content = new TreeMap<>();
        content.put("application/octet-stream", Map.of("schema", binary));
        content.put("multipart/form-data", Map.of("schema",
                ordered("type", "object", "properties", Map.of("file", binary))));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("required", true);
        body.put("content", content);
        return body;
    }

    private static Map<String, Object> withContent(String description, String contentType,
            Map<String, Object> schema) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("description", description);
        response.put("content", Map.of(contentType, Map.of("schema", schema)));
        return response;
    }

    /** An insertion-ordered two-entry map: multi-entry Map.of would randomize JSON key order. */
    private static Map<String, Object> ordered(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(k1, v1);
        map.put(k2, v2);
        return map;
    }

    private static Map<String, Object> ref(String schema) {
        return Map.of("$ref", "#/components/schemas/" + schema);
    }

    private static Map<String, Object> parameter(String name, String in, boolean required,
            String type) {
        Map<String, Object> parameter = new LinkedHashMap<>();
        parameter.put("name", name);
        parameter.put("in", in);
        parameter.put("required", required);
        parameter.put("schema", Map.of("type", type));
        return parameter;
    }

    private static String schemaType(InputField field) {
        return switch (field.type() == null ? "string" : field.type()) {
            case "integer" -> "integer";
            case "number" -> "number";
            case "boolean" -> "boolean";
            case "array" -> "array";
            default -> "string";
        };
    }

    private static Map<String, Object> components() {
        Map<String, Object> bearer = new LinkedHashMap<>();
        bearer.put("type", "http");
        bearer.put("scheme", "bearer");
        bearer.put("bearerFormat", "JWT");
        Map<String, Object> cookie = new LinkedHashMap<>();
        cookie.put("type", "apiKey");
        cookie.put("in", "cookie");
        cookie.put("name", "tesseraql_sid");

        Map<String, Object> securitySchemes = new TreeMap<>();
        securitySchemes.put("bearerAuth", bearer);
        securitySchemes.put("sessionCookie", cookie);

        Map<String, Object> accepted = new TreeMap<>();
        accepted.put("transferId", Map.of("type", "string"));
        accepted.put("statusUrl", Map.of("type", "string"));
        accepted.put("fileUrl", Map.of("type", "string"));

        Map<String, Object> statusProperties = new TreeMap<>();
        statusProperties.put("transferId", Map.of("type", "string"));
        statusProperties.put("route", Map.of("type", "string"));
        statusProperties.put("direction", Map.of("type", "string"));
        statusProperties.put("status", Map.of("type", "string"));
        statusProperties.put("rows", Map.of("type", "integer"));
        statusProperties.put("errors", ordered("type", "array", "items", Map.of("type", "object")));
        statusProperties.put("filename", Map.of("type", "string"));
        statusProperties.put("downloaded", Map.of("type", "boolean"));
        statusProperties.put("fileUrl", Map.of("type", "string"));

        Map<String, Object> schemas = new TreeMap<>();
        schemas.put("TransferAccepted", ordered("type", "object", "properties", accepted));
        schemas.put("TransferStatus", ordered("type", "object", "properties", statusProperties));

        Map<String, Object> components = new LinkedHashMap<>();
        components.put("securitySchemes", securitySchemes);
        components.put("schemas", schemas);
        return components;
    }
}
