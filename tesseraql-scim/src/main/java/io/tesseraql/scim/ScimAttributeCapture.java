package io.tesseraql.scim;

import com.fasterxml.jackson.databind.JsonNode;
import io.tesseraql.identity.FederatedIdentities;
import io.tesseraql.identity.IdentityService;
import io.tesseraql.identity.RealmConfig;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Lands provisioned SCIM attributes in the identity store (docs/application-roles.md structural
 * decision 3): the enterprise extension's org attributes under their own names ({@code department},
 * {@code division}, {@code costCenter}, {@code employeeNumber}, {@code manager}) plus a configured
 * map of additional SCIM paths → attribute names. Capture is declared, not promiscuous — anything
 * unmapped stays discarded. The SCIM resource id keys the write, so capture assumes the
 * deployment's SCIM contracts manage the identity store's users (the id is the {@code tql_users}
 * user id).
 *
 * <p>A create or replace converges the whole declared set (absent means delete); a PATCH applies
 * only the members its operations address, so an unrelated PATCH cannot erase captured values.
 */
public final class ScimAttributeCapture {

    private static final String ENTERPRISE_PREFIX = ScimUser.ENTERPRISE_SCHEMA
            .toLowerCase(Locale.ROOT) + ":";

    private final IdentityService identity;
    private final RealmConfig realm;
    private final Map<String, String> extras;

    /** With the configured additional captures: SCIM attribute path → store attribute name. */
    public ScimAttributeCapture(IdentityService identity, RealmConfig realm,
            Map<String, String> extras) {
        this.identity = identity;
        this.realm = realm;
        this.extras = extras == null ? Map.of() : Map.copyOf(extras);
    }

    /** Converges the declared set from a full resource (create and replace). */
    public void syncResource(String userId, ScimUser user, JsonNode payload) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        Map<String, String> values = new LinkedHashMap<>();
        ScimUser.Enterprise enterprise = user.enterprise();
        values.put("department", enterprise == null ? null : enterprise.department());
        values.put("division", enterprise == null ? null : enterprise.division());
        values.put("costCenter", enterprise == null ? null : enterprise.costCenter());
        values.put("employeeNumber", enterprise == null ? null : enterprise.employeeNumber());
        values.put("manager", enterprise == null ? null : enterprise.managerValue());
        for (Map.Entry<String, String> extra : extras.entrySet()) {
            values.put(extra.getValue(), scalarAt(payload, extra.getKey()));
        }
        FederatedIdentities.syncAttributes(identity, realm, userId, values);
    }

    /** Applies only the members a PATCH addresses (add/replace sets, remove deletes). */
    public void syncPatch(String userId, ScimPatchRequest patch) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (ScimPatchRequest.Operation operation : patch.operations()) {
            boolean remove = "remove".equalsIgnoreCase(operation.op());
            if (operation.path() == null || operation.path().isBlank()) {
                if (operation.value() != null && operation.value().isObject()) {
                    operation.value().properties().forEach(
                            entry -> collect(values, entry.getKey(), entry.getValue(), false));
                }
            } else {
                collect(values, operation.path(), operation.value(), remove);
            }
        }
        if (!values.isEmpty()) {
            FederatedIdentities.syncAttributes(identity, realm, userId, values);
        }
    }

    /** One addressed path: an enterprise member, the enterprise object, or a configured extra. */
    private void collect(Map<String, String> values, String path, JsonNode value,
            boolean remove) {
        String normalized = path.toLowerCase(Locale.ROOT);
        if (normalized.startsWith(ENTERPRISE_PREFIX)) {
            collectEnterprise(values, normalized.substring(ENTERPRISE_PREFIX.length()), value,
                    remove);
            return;
        }
        if (normalized.equals(ScimUser.ENTERPRISE_SCHEMA.toLowerCase(Locale.ROOT))) {
            if (value != null && value.isObject()) {
                value.properties().forEach(entry -> collectEnterprise(values,
                        entry.getKey().toLowerCase(Locale.ROOT), entry.getValue(), false));
            }
            return;
        }
        for (Map.Entry<String, String> extra : extras.entrySet()) {
            if (extra.getKey().toLowerCase(Locale.ROOT).equals(normalized)) {
                values.put(extra.getValue(), remove ? null : scalar(value));
            }
        }
    }

    private static void collectEnterprise(Map<String, String> values, String member,
            JsonNode value, boolean remove) {
        switch (member) {
            case "department" -> values.put("department", remove ? null : scalar(value));
            case "division" -> values.put("division", remove ? null : scalar(value));
            case "costcenter" -> values.put("costCenter", remove ? null : scalar(value));
            case "employeenumber" -> values.put("employeeNumber",
                    remove ? null : scalar(value));
            case "manager", "manager.value" -> values.put("manager",
                    remove
                            ? null
                            : value != null && value.isObject()
                                    ? scalar(value.get("value"))
                                    : scalar(value));
            default -> {
                // An enterprise member TesseraQL does not capture: ignored, not an error.
            }
        }
    }

    /**
     * The scalar at a configured SCIM path in the raw payload: an extension attribute is
     * addressed {@code <schema urn>:<attribute>} (sub-attributes dotted), a core attribute by
     * its dotted path. A missing or non-scalar value reads as absent.
     */
    private static String scalarAt(JsonNode payload, String path) {
        if (payload == null) {
            return null;
        }
        String container = null;
        String attribute = path;
        int urnEnd = path.lastIndexOf(':');
        if (urnEnd > 0) {
            container = path.substring(0, urnEnd);
            attribute = path.substring(urnEnd + 1);
        }
        JsonNode node = container == null ? payload : payload.get(container);
        for (String part : attribute.split("\\.")) {
            if (node == null) {
                return null;
            }
            node = node.get(part);
        }
        return scalar(node);
    }

    private static String scalar(JsonNode value) {
        return value == null || !value.isValueNode() || value.isNull() ? null : value.asText();
    }
}
