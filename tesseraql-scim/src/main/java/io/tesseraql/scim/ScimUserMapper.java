package io.tesseraql.scim;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps SCIM users to SQL contract bind parameters and result rows back to SCIM users
 * (design ch. 10.15). The SCIM contract SQL binds the parameters below and should alias its result
 * columns to the same SCIM attribute names ({@code id}, {@code userName}, {@code givenName},
 * {@code familyName}, {@code email}, {@code active}, {@code externalId}).
 */
public final class ScimUserMapper {

    private ScimUserMapper() {
    }

    /** Flattens a SCIM user into bind parameters for the SCIM contract SQL. */
    public static Map<String, Object> toParams(ScimUser user) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", user.id());
        params.put("externalId", user.externalId());
        params.put("userName", user.userName());
        params.put("givenName", user.name() == null ? null : user.name().givenName());
        params.put("familyName", user.name() == null ? null : user.name().familyName());
        params.put("email", user.primaryEmail());
        params.put("active", user.active());
        return params;
    }

    /** Reconstructs a SCIM user from a SCIM-contract result row. */
    public static ScimUser fromRow(Map<String, Object> row) {
        String givenName = string(row.get("givenName"));
        String familyName = string(row.get("familyName"));
        ScimUser.Name name = givenName == null && familyName == null
                ? null : new ScimUser.Name(givenName, familyName, null);
        String email = string(row.get("email"));
        List<ScimUser.Email> emails = email == null
                ? List.of() : List.of(new ScimUser.Email(email, true));
        return new ScimUser(null, string(row.get("id")), string(row.get("externalId")),
                string(row.get("userName")), name, emails, bool(row.get("active")));
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Boolean bool(Object value) {
        return switch (value) {
            case null -> null;
            case Boolean b -> b;
            case Number n -> n.intValue() != 0;
            default -> Boolean.parseBoolean(String.valueOf(value));
        };
    }
}
