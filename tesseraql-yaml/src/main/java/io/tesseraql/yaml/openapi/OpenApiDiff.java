package io.tesseraql.yaml.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Diffs two OpenAPI 3 documents (the kind {@link OpenApiGenerator} emits) into a deterministic API
 * changelog: which operations were added, removed, or changed, and — for a changed operation — what
 * about it changed (its parameters, request body, responses, or security). It compares the contract
 * the way a consumer reads it (by HTTP method and path), not by source order, so a re-ordering of the
 * routes is not a change.
 *
 * <p>It is a derived, read-only view used by the documentation portal to show "what changed since the
 * baseline" — the baseline being a previously-released {@code openapi.json} the operator keeps. The
 * comparison is structural and conservative: a request body or per-status response whose schema node
 * differs is reported as "changed" without diffing the schema field-by-field (the response shape and
 * request shape are already documented on the route page). Errors are raised in the
 * {@link TqlDomain#REPORT} domain.
 */
public final class OpenApiDiff {

    private static final TqlErrorCode DIFF_ERROR = new TqlErrorCode(TqlDomain.REPORT, 2005);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> HTTP_METHODS = Set.of("get", "put", "post", "delete", "patch",
            "head", "options", "trace");

    /** Parses the two OpenAPI JSON documents and diffs them (baseline &rarr; current). */
    public ApiChangelog diff(String baselineJson, String currentJson) {
        try {
            return diff(MAPPER.readTree(baselineJson), MAPPER.readTree(currentJson));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new TqlException(DIFF_ERROR,
                    "Failed to parse OpenAPI for diff: " + ex.getMessage());
        }
    }

    /**
     * Diffs two parsed OpenAPI documents. The changelog entries are sorted by path then HTTP method
     * for a stable presentation.
     */
    public ApiChangelog diff(JsonNode baseline, JsonNode current) {
        Map<String, JsonNode> before = operations(baseline);
        Map<String, JsonNode> after = operations(current);

        Set<String> keys = new TreeSet<>(OpenApiDiff::byPathThenMethod);
        keys.addAll(before.keySet());
        keys.addAll(after.keySet());

        List<ApiChangelog.Entry> entries = new ArrayList<>();
        for (String key : keys) {
            JsonNode oldOp = before.get(key);
            JsonNode newOp = after.get(key);
            String method = key.substring(0, key.indexOf(' '));
            String path = key.substring(key.indexOf(' ') + 1);
            if (oldOp == null) {
                entries.add(new ApiChangelog.Entry(ApiChangelog.Kind.ADDED, method, path,
                        operationId(newOp), List.of()));
            } else if (newOp == null) {
                entries.add(new ApiChangelog.Entry(ApiChangelog.Kind.REMOVED, method, path,
                        operationId(oldOp), List.of()));
            } else {
                List<String> details = changeDetails(oldOp, newOp);
                if (!details.isEmpty()) {
                    entries.add(new ApiChangelog.Entry(ApiChangelog.Kind.CHANGED, method, path,
                            operationId(newOp), details));
                }
            }
        }
        return new ApiChangelog(entries);
    }

    /** Every operation in the document, keyed by {@code "<METHOD> <path>"}. */
    private static Map<String, JsonNode> operations(JsonNode doc) {
        Map<String, JsonNode> operations = new LinkedHashMap<>();
        JsonNode paths = doc == null ? null : doc.get("paths");
        if (paths == null || !paths.isObject()) {
            return operations;
        }
        for (Map.Entry<String, JsonNode> path : paths.properties()) {
            JsonNode item = path.getValue();
            if (item == null || !item.isObject()) {
                continue;
            }
            for (Map.Entry<String, JsonNode> op : item.properties()) {
                if (HTTP_METHODS.contains(op.getKey().toLowerCase(Locale.ROOT))) {
                    operations.put(op.getKey().toUpperCase(Locale.ROOT) + " " + path.getKey(),
                            op.getValue());
                }
            }
        }
        return operations;
    }

    /** The human-readable details of how a changed operation differs (empty when it is unchanged). */
    private static List<String> changeDetails(JsonNode oldOp, JsonNode newOp) {
        List<String> details = new ArrayList<>();
        diffParameters(oldOp, newOp, details);
        diffRequestBody(oldOp, newOp, details);
        diffResponses(oldOp, newOp, details);
        diffSecurity(oldOp, newOp, details);
        return details;
    }

    private static void diffParameters(JsonNode oldOp, JsonNode newOp, List<String> details) {
        Map<String, JsonNode> before = parameters(oldOp);
        Map<String, JsonNode> after = parameters(newOp);
        Set<String> keys = new TreeSet<>();
        keys.addAll(before.keySet());
        keys.addAll(after.keySet());
        for (String key : keys) {
            JsonNode oldParam = before.get(key);
            JsonNode newParam = after.get(key);
            String label = parameterLabel(oldParam != null ? oldParam : newParam);
            if (oldParam == null) {
                details.add("+ " + label);
            } else if (newParam == null) {
                details.add("- " + label);
            } else {
                boolean oldReq = oldParam.path("required").asBoolean(false);
                boolean newReq = newParam.path("required").asBoolean(false);
                if (oldReq != newReq) {
                    details.add(label + ": now " + (newReq ? "required" : "optional"));
                }
                String oldType = oldParam.path("schema").path("type").asText("");
                String newType = newParam.path("schema").path("type").asText("");
                if (!oldType.equals(newType)) {
                    details.add(label + ": type " + display(oldType) + " → " + display(newType));
                }
            }
        }
    }

    private static void diffRequestBody(JsonNode oldOp, JsonNode newOp, List<String> details) {
        JsonNode oldBody = oldOp.get("requestBody");
        JsonNode newBody = newOp.get("requestBody");
        if (oldBody == null && newBody != null) {
            details.add("+ request body");
        } else if (oldBody != null && newBody == null) {
            details.add("- request body");
        } else if (oldBody != null && !oldBody.equals(newBody)) {
            details.add("request body changed");
        }
    }

    private static void diffResponses(JsonNode oldOp, JsonNode newOp, List<String> details) {
        Map<String, JsonNode> before = responses(oldOp);
        Map<String, JsonNode> after = responses(newOp);
        Set<String> codes = new TreeSet<>();
        codes.addAll(before.keySet());
        codes.addAll(after.keySet());
        for (String code : codes) {
            JsonNode oldResp = before.get(code);
            JsonNode newResp = after.get(code);
            if (oldResp == null) {
                details.add("+ response " + code);
            } else if (newResp == null) {
                details.add("- response " + code);
            } else if (!oldResp.equals(newResp)) {
                details.add("response " + code + " changed");
            }
        }
    }

    private static void diffSecurity(JsonNode oldOp, JsonNode newOp, List<String> details) {
        String before = securitySchemes(oldOp);
        String after = securitySchemes(newOp);
        if (!before.equals(after)) {
            details.add("security: " + before + " → " + after);
        }
    }

    /** The route's security requirement as a sorted, comma-joined scheme list (or {@code none}). */
    private static String securitySchemes(JsonNode op) {
        JsonNode security = op.get("security");
        if (security == null || !security.isArray() || security.isEmpty()) {
            return "none";
        }
        Set<String> schemes = new TreeSet<>();
        for (JsonNode requirement : security) {
            for (Map.Entry<String, JsonNode> scheme : requirement.properties()) {
                schemes.add(scheme.getKey());
            }
        }
        return schemes.isEmpty() ? "none" : String.join(", ", schemes);
    }

    /** Parameters keyed by {@code name|in} (so the same name in path and query stay distinct). */
    private static Map<String, JsonNode> parameters(JsonNode op) {
        Map<String, JsonNode> parameters = new LinkedHashMap<>();
        JsonNode array = op.get("parameters");
        if (array != null && array.isArray()) {
            for (JsonNode parameter : array) {
                parameters.put(parameter.path("name").asText("") + "|" + parameter.path("in")
                        .asText(""), parameter);
            }
        }
        return parameters;
    }

    private static Map<String, JsonNode> responses(JsonNode op) {
        Map<String, JsonNode> responses = new LinkedHashMap<>();
        JsonNode object = op.get("responses");
        if (object != null && object.isObject()) {
            for (Map.Entry<String, JsonNode> entry : object.properties()) {
                responses.put(entry.getKey(), entry.getValue());
            }
        }
        return responses;
    }

    private static String parameterLabel(JsonNode parameter) {
        String in = parameter.path("in").asText("");
        String name = parameter.path("name").asText("");
        return (in.isEmpty() ? "" : in + " ") + "parameter " + name;
    }

    private static String operationId(JsonNode op) {
        return op == null ? null : op.path("operationId").asText(null);
    }

    private static String display(String type) {
        return type.isEmpty() ? "untyped" : type;
    }

    /** Orders operation keys by path, then method, so the changelog reads path-first. */
    private static int byPathThenMethod(String a, String b) {
        String pathA = a.substring(a.indexOf(' ') + 1);
        String pathB = b.substring(b.indexOf(' ') + 1);
        int byPath = pathA.compareTo(pathB);
        return byPath != 0 ? byPath : a.compareTo(b);
    }

    /**
     * The structured difference between two OpenAPI documents: an ordered list of per-operation
     * entries (added, removed, or changed), each naming the HTTP method, path, and operation id.
     *
     * @param entries the changes, sorted by path then method
     */
    public record ApiChangelog(List<Entry> entries) {

        public ApiChangelog {
            entries = List.copyOf(entries);
        }

        /** Whether the two documents describe the same API surface (no added/removed/changed op). */
        public boolean isEmpty() {
            return entries.isEmpty();
        }

        /** The number of entries of one kind. */
        public long count(Kind kind) {
            return entries.stream().filter(entry -> entry.kind() == kind).count();
        }

        /** How an operation changed between the baseline and the current document. */
        public enum Kind {
            ADDED, REMOVED, CHANGED
        }

        /**
         * One changed operation.
         *
         * @param kind        whether the operation was added, removed, or changed
         * @param method      the HTTP method (upper case)
         * @param path        the served URL path
         * @param operationId the operation id, or {@code null}
         * @param details     for a {@code CHANGED} entry, the specific differences; empty otherwise
         */
        public record Entry(Kind kind, String method, String path, String operationId,
                List<String> details) {

            public Entry {
                details = details == null ? List.of() : List.copyOf(details);
            }
        }
    }
}
