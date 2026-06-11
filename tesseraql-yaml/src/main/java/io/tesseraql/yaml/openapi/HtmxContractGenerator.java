package io.tesseraql.yaml.openapi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.RouteFile;
import io.tesseraql.yaml.model.RouteDefinition;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Generates the deterministic htmx server contract from the route manifest (design ch. 22.18):
 * the HTML-rendering surface a hypermedia client can rely on. Pages and fragments are listed with
 * their method, URL, declared inputs, template, and security, and fragments (partial markup under
 * a {@code fragments/} segment, swapped into pages by hypermedia requests) are flagged so template
 * authors and reviewers can see the server contract without reading every route file.
 */
public final class HtmxContractGenerator {

    private static final TqlErrorCode GEN_ERROR = new TqlErrorCode(TqlDomain.REPORT, 2002);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Builds the contract document tree (deterministically ordered). */
    public Map<String, Object> generate(AppManifest manifest) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("contract", "tesseraql-htmx/v1");
        doc.put("app", manifest.config().getString("tesseraql.app.name").orElse("tesseraql-app"));

        List<Map<String, Object>> pages = new ArrayList<>();
        List<Map<String, Object>> fragments = new ArrayList<>();
        // Manifest routes are discovery-ordered; sort by URL then method for stable output.
        List<RouteFile> routes = new ArrayList<>(manifest.routes());
        routes.sort(java.util.Comparator.comparing(RouteFile::urlPath)
                .thenComparing(RouteFile::httpMethod));
        for (RouteFile route : routes) {
            RouteDefinition definition = route.definition();
            if (!isHtml(definition)) {
                continue;
            }
            (isFragment(route) ? fragments : pages).add(entry(route));
        }
        doc.put("pages", pages);
        doc.put("fragments", fragments);
        return doc;
    }

    /** Serializes the contract as pretty JSON. */
    public String toJson(AppManifest manifest) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(generate(manifest));
        } catch (JsonProcessingException ex) {
            throw new TqlException(GEN_ERROR, "Failed to serialize htmx contract: " + ex.getMessage());
        }
    }

    private static boolean isHtml(RouteDefinition definition) {
        String recipe = definition.recipe() == null ? "" : definition.recipe();
        return "query-html".equals(recipe) || "page".equals(recipe);
    }

    /** Fragments are partial markup by URL convention: a {@code fragments/} path segment. */
    private static boolean isFragment(RouteFile route) {
        return route.urlPath().contains("/fragments/");
    }

    private static Map<String, Object> entry(RouteFile route) {
        RouteDefinition definition = route.definition();
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", definition.id());
        entry.put("method", route.httpMethod().toUpperCase(java.util.Locale.ROOT));
        entry.put("path", route.urlPath());
        if (definition.response() != null && definition.response().html() != null
                && definition.response().html().template() != null) {
            entry.put("template", definition.response().html().template());
        }
        Map<String, Object> inputs = new TreeMap<>();
        definition.input().forEach((name, field) -> {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("type", field.type() == null ? "string" : field.type());
            input.put("required", field.required());
            inputs.put(name, input);
        });
        if (!inputs.isEmpty()) {
            entry.put("inputs", inputs);
        }
        if (definition.security() != null) {
            Map<String, Object> security = new LinkedHashMap<>();
            if (definition.security().auth() != null) {
                security.put("auth", definition.security().auth());
            }
            if (definition.security().policy() != null) {
                security.put("policy", definition.security().policy());
            }
            if (Boolean.TRUE.equals(definition.security().csrf())) {
                security.put("csrf", true);
            }
            entry.put("security", security);
        }
        return entry;
    }
}
