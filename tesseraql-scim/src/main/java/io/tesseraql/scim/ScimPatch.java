package io.tesseraql.scim;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Normalizes a SCIM PATCH request into a full user (design ch. 10.15): it applies each operation to
 * the current user's attributes so the result can be persisted via the replace contract. Supports
 * the attributes TesseraQL maps ({@code userName}, {@code active}, {@code externalId},
 * {@code name.givenName/familyName}, {@code emails}); unsupported paths are rejected with
 * {@code invalidPath} (400).
 */
public final class ScimPatch {

    private ScimPatch() {
    }

    /** Applies the patch operations to {@code current}, returning the resulting user. */
    public static ScimUser apply(ScimUser current, ScimPatchRequest patch) {
        Map<String, Object> flat = new LinkedHashMap<>(ScimUserMapper.toParams(current));
        flat.put("id", current.id());
        for (ScimPatchRequest.Operation operation : patch.operations()) {
            boolean remove = "remove".equalsIgnoreCase(operation.op());
            if (operation.path() == null || operation.path().isBlank()) {
                applyValueObject(flat, operation.value());
            } else {
                setAttribute(flat, operation.path(), operation.value(), remove);
            }
        }
        return ScimUserMapper.fromRow(flat);
    }

    /** A path-less replace/add carries a partial resource object; apply each member. */
    private static void applyValueObject(Map<String, Object> flat, JsonNode value) {
        if (value == null || !value.isObject()) {
            throw new ScimException(400, "noTarget",
                    "PATCH without a path requires an object value");
        }
        value.properties().forEach(
                entry -> setAttribute(flat, entry.getKey(), entry.getValue(), false));
    }

    private static void setAttribute(Map<String, Object> flat, String path, JsonNode value,
            boolean remove) {
        switch (path.toLowerCase(Locale.ROOT)) {
            case "username" -> flat.put("userName", remove ? null : text(value));
            case "externalid" -> flat.put("externalId", remove ? null : text(value));
            case "active" -> flat.put("active", remove ? null : value != null && value.asBoolean());
            case "name.givenname" -> flat.put("givenName", remove ? null : text(value));
            case "name.familyname" -> flat.put("familyName", remove ? null : text(value));
            case "name" -> {
                if (value != null && value.isObject()) {
                    if (value.has("givenName")) {
                        flat.put("givenName", text(value.get("givenName")));
                    }
                    if (value.has("familyName")) {
                        flat.put("familyName", text(value.get("familyName")));
                    }
                }
            }
            case "emails" -> flat.put("email", remove ? null : primaryEmail(value));
            default ->
                throw new ScimException(400, "invalidPath", "Unsupported PATCH path: " + path);
        }
    }

    private static String text(JsonNode value) {
        return value == null || value.isNull() ? null : value.asText();
    }

    /** Extracts a single email from an {@code emails} value (array of objects, or a scalar). */
    private static String primaryEmail(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isArray()) {
            JsonNode chosen = null;
            for (JsonNode email : value) {
                if (email.path("primary").asBoolean(false)) {
                    chosen = email;
                    break;
                }
                if (chosen == null) {
                    chosen = email;
                }
            }
            return chosen == null ? null : text(chosen.get("value"));
        }
        return value.isObject() ? text(value.get("value")) : value.asText();
    }
}
