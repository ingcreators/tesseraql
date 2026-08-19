package io.tesseraql.scim;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Normalizes a SCIM PATCH request into a full user (design ch. 10.15): it applies each operation to
 * the current user's attributes so the result can be persisted via the replace contract. Supports
 * the attributes TesseraQL maps ({@code userName}, {@code active}, {@code externalId},
 * {@code name.givenName/familyName}, {@code emails}) and the enterprise extension's org attributes
 * (docs/application-roles.md structural decision 3). Unsupported <em>core</em> paths are rejected
 * with {@code invalidPath} (400); an unmapped extension-schema path (one carrying a {@code :}) is
 * tolerated as a no-op instead, so an IdP provisioning extension attributes TesseraQL does not
 * capture cannot be broken by them.
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

    private static final String ENTERPRISE_PREFIX = ScimUser.ENTERPRISE_SCHEMA
            .toLowerCase(Locale.ROOT) + ":";

    private static void setAttribute(Map<String, Object> flat, String path, JsonNode value,
            boolean remove) {
        String normalized = path.toLowerCase(Locale.ROOT);
        if (normalized.startsWith(ENTERPRISE_PREFIX)) {
            setEnterpriseAttribute(flat, normalized.substring(ENTERPRISE_PREFIX.length()),
                    value, remove);
            return;
        }
        if (normalized.equals(ScimUser.ENTERPRISE_SCHEMA.toLowerCase(Locale.ROOT))) {
            if (value != null && value.isObject()) {
                value.properties().forEach(entry -> setEnterpriseAttribute(flat,
                        entry.getKey().toLowerCase(Locale.ROOT), entry.getValue(), false));
            }
            return;
        }
        switch (normalized) {
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
            default -> {
                if (!normalized.contains(":")) {
                    throw new ScimException(400, "invalidPath",
                            "Unsupported PATCH path: " + path);
                }
            }
        }
    }

    /** One enterprise-extension member; {@code manager} accepts an object or its bare value. */
    private static void setEnterpriseAttribute(Map<String, Object> flat, String member,
            JsonNode value, boolean remove) {
        switch (member) {
            case "department" -> flat.put("department", remove ? null : text(value));
            case "division" -> flat.put("division", remove ? null : text(value));
            case "costcenter" -> flat.put("costCenter", remove ? null : text(value));
            case "employeenumber" -> flat.put("employeeNumber", remove ? null : text(value));
            case "manager", "manager.value" -> flat.put("manager",
                    remove
                            ? null
                            : value != null && value.isObject()
                                    ? text(value.get("value"))
                                    : text(value));
            default -> {
                // An enterprise member TesseraQL does not map: tolerated, not an error.
            }
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
