package io.tesseraql.scim;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps SCIM groups to SQL contract bind parameters and result rows back to SCIM groups
 * (design ch. 10.15). The group contract SQL binds {@code id}, {@code externalId} and
 * {@code displayName}, and should alias its group columns to those SCIM attribute names; the
 * membership SQL binds {@code groupId}/{@code memberId} and aliases member columns to
 * {@code value} and {@code display}.
 */
public final class ScimGroupMapper {

    private ScimGroupMapper() {
    }

    /** Flattens a SCIM group's own attributes into bind parameters for the group contract SQL. */
    public static Map<String, Object> toParams(ScimGroup group) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", group.id());
        params.put("externalId", group.externalId());
        params.put("displayName", group.displayName());
        return params;
    }

    /** Reconstructs a SCIM group from its contract result row and its resolved members. */
    public static ScimGroup fromRow(Map<String, Object> row, List<ScimGroup.Member> members) {
        return new ScimGroup(null, string(row.get("id")), string(row.get("externalId")),
                string(row.get("displayName")), members);
    }

    /** Reconstructs a single SCIM member from a membership result row. */
    public static ScimGroup.Member memberFromRow(Map<String, Object> row) {
        return new ScimGroup.Member(string(row.get("value")), string(row.get("display")), null);
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
