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
 */
public final class OpenApiGenerator {

    private static final TqlErrorCode GEN_ERROR = new TqlErrorCode(TqlDomain.REPORT, 2001);
    private static final Pattern PATH_PARAM = Pattern.compile("\\{(\\w+)\\}");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Builds the OpenAPI document tree (deterministically ordered). */
    public Map<String, Object> generate(AppManifest manifest) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("openapi", "3.0.3");

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("title", manifest.config().getString("tesseraql.app.name").orElse("tesseraql-app"));
        info.put("version", manifest.config().getString("app.version").orElse("1.0.0"));
        doc.put("info", info);

        Map<String, Object> paths = new TreeMap<>();
        for (RouteFile route : manifest.routes()) {
            Map<String, Object> methods = pathItem(paths, route.urlPath());
            methods.put(route.httpMethod().toLowerCase(), operation(route));
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
        return (Map<String, Object>) paths.computeIfAbsent(path, key -> new TreeMap<String, Object>());
    }

    private Map<String, Object> operation(RouteFile route) {
        RouteDefinition definition = route.definition();
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("operationId", definition.id());

        List<Object> parameters = new ArrayList<>();
        Matcher matcher = PATH_PARAM.matcher(route.urlPath());
        while (matcher.find()) {
            parameters.add(parameter(matcher.group(1), "path", true, "string"));
        }
        new TreeMap<>(definition.input()).forEach((name, field) ->
                parameters.add(parameter(name, "query", field.required(), schemaType(field))));
        if (!parameters.isEmpty()) {
            operation.put("parameters", parameters);
        }

        if (definition.security() != null && "bearer".equals(definition.security().auth())) {
            operation.put("security", List.of(Map.of("bearerAuth", List.of())));
        }
        operation.put("responses", Map.of("200", Map.of("description", "OK")));
        return operation;
    }

    private static Map<String, Object> parameter(String name, String in, boolean required, String type) {
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
        return Map.of("securitySchemes", Map.of("bearerAuth", bearer));
    }
}
