package io.tesseraql.scim;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalizes a SCIM PATCH request into a full group (design ch. 10.15, RFC 7644 §3.5.2): it applies
 * each operation to the current group so the result can be persisted via the replace contract.
 * Supports the group attributes TesseraQL maps ({@code displayName}, {@code externalId}) and
 * {@code members} (add / replace the whole set / remove by value array or {@code members[value eq
 * "..."]} path filter). Unsupported paths are rejected with {@code invalidPath} (400).
 */
public final class ScimGroupPatch {

    private static final Pattern MEMBER_FILTER =
            Pattern.compile("^members\\[\\s*value\\s+eq\\s+\"(.*)\"\\s*]$");

    private ScimGroupPatch() {
    }

    /** Applies the patch operations to {@code current}, returning the resulting group. */
    public static ScimGroup apply(ScimGroup current, ScimPatchRequest patch) {
        State state = new State(current);
        for (ScimPatchRequest.Operation operation : patch.operations()) {
            apply(state, operation);
        }
        return state.toGroup(current.id());
    }

    private static void apply(State state, ScimPatchRequest.Operation operation) {
        String op = operation.op() == null ? "" : operation.op().toLowerCase(Locale.ROOT);
        String path = operation.path();
        if (path == null || path.isBlank()) {
            applyValueObject(state, op, operation.value());
            return;
        }
        Matcher filter = MEMBER_FILTER.matcher(path);
        if (filter.matches()) {
            if (!"remove".equals(op)) {
                throw new ScimException(400, "invalidPath",
                        "Member value-filter paths are only valid for remove: " + path);
            }
            state.members.remove(filter.group(1));
            return;
        }
        setAttribute(state, op, path, operation.value());
    }

    /** A path-less add/replace carries a partial group object; apply each field. */
    private static void applyValueObject(State state, String op, JsonNode value) {
        if (value == null || !value.isObject()) {
            throw new ScimException(400, "noTarget", "PATCH without a path requires an object value");
        }
        value.fields().forEachRemaining(entry -> setAttribute(state, op, entry.getKey(), entry.getValue()));
    }

    private static void setAttribute(State state, String op, String path, JsonNode value) {
        boolean remove = "remove".equals(op);
        switch (path.toLowerCase(Locale.ROOT)) {
            case "displayname" -> state.displayName = remove ? null : text(value);
            case "externalid" -> state.externalId = remove ? null : text(value);
            case "members" -> applyMembers(state, op, value);
            default -> throw new ScimException(400, "invalidPath", "Unsupported PATCH path: " + path);
        }
    }

    private static void applyMembers(State state, String op, JsonNode value) {
        switch (op) {
            case "add" -> members(value).forEach(member -> state.members.put(member.value(), member));
            case "replace" -> {
                state.members.clear();
                members(value).forEach(member -> state.members.put(member.value(), member));
            }
            case "remove" -> {
                List<ScimGroup.Member> targeted = members(value);
                if (targeted.isEmpty()) {
                    state.members.clear();
                } else {
                    targeted.forEach(member -> state.members.remove(member.value()));
                }
            }
            default -> throw new ScimException(400, "invalidSyntax", "Unsupported members op: " + op);
        }
    }

    /** Extracts members from a value node (an array of member objects, a single object, or a scalar). */
    private static List<ScimGroup.Member> members(JsonNode value) {
        List<ScimGroup.Member> members = new ArrayList<>();
        if (value == null || value.isNull()) {
            return members;
        }
        if (value.isArray()) {
            value.forEach(node -> members.add(member(node)));
        } else {
            members.add(member(value));
        }
        return members;
    }

    private static ScimGroup.Member member(JsonNode node) {
        if (node.isObject()) {
            return new ScimGroup.Member(text(node.get("value")), text(node.get("display")), null);
        }
        return new ScimGroup.Member(node.asText(), null, null);
    }

    private static String text(JsonNode value) {
        return value == null || value.isNull() ? null : value.asText();
    }

    /** Mutable working copy of a group's attributes and ordered, value-keyed members. */
    private static final class State {
        private String displayName;
        private String externalId;
        private final Map<String, ScimGroup.Member> members = new LinkedHashMap<>();

        State(ScimGroup current) {
            this.displayName = current.displayName();
            this.externalId = current.externalId();
            current.members().forEach(member -> members.put(member.value(), member));
        }

        ScimGroup toGroup(String id) {
            return new ScimGroup(null, id, externalId, displayName, new ArrayList<>(members.values()));
        }
    }
}
