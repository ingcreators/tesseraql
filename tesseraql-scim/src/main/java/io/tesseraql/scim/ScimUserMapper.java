package io.tesseraql.scim;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps SCIM users to SQL contract bind parameters and result rows back to SCIM users
 * (design ch. 10.15). The SCIM contract SQL binds the parameters below and should alias its result
 * columns to the same SCIM attribute names ({@code id}, {@code userName}, {@code givenName},
 * {@code familyName}, {@code email}, {@code active}, {@code externalId}). The enterprise-extension
 * attributes ({@code department}, {@code division}, {@code costCenter}, {@code employeeNumber},
 * {@code manager} — the manager's {@code value}) are bound too, so a deployment's contract SQL
 * may persist them; a contract that ignores them loses nothing, because the attribute capture
 * (docs/application-roles.md structural decision 3) lands them in {@code tql_user_attributes}.
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
        ScimUser.Enterprise enterprise = user.enterprise();
        params.put("department", enterprise == null ? null : enterprise.department());
        params.put("division", enterprise == null ? null : enterprise.division());
        params.put("costCenter", enterprise == null ? null : enterprise.costCenter());
        params.put("employeeNumber", enterprise == null ? null : enterprise.employeeNumber());
        params.put("manager", enterprise == null ? null : enterprise.managerValue());
        return params;
    }

    /** Reconstructs a SCIM user from a SCIM-contract result row. */
    public static ScimUser fromRow(Map<String, Object> row) {
        String givenName = string(row.get("givenName"));
        String familyName = string(row.get("familyName"));
        ScimUser.Name name = givenName == null && familyName == null
                ? null
                : new ScimUser.Name(givenName, familyName, null);
        String email = string(row.get("email"));
        List<ScimUser.Email> emails = email == null
                ? List.of()
                : List.of(new ScimUser.Email(email, true));
        return new ScimUser(null, string(row.get("id")), string(row.get("externalId")),
                string(row.get("userName")), name, emails, bool(row.get("active")),
                enterprise(row));
    }

    /** The enterprise extension from a result row, or null when no column carries a value. */
    private static ScimUser.Enterprise enterprise(Map<String, Object> row) {
        String department = string(row.get("department"));
        String division = string(row.get("division"));
        String costCenter = string(row.get("costCenter"));
        String employeeNumber = string(row.get("employeeNumber"));
        String manager = string(row.get("manager"));
        if (department == null && division == null && costCenter == null
                && employeeNumber == null && manager == null) {
            return null;
        }
        return new ScimUser.Enterprise(department, division, costCenter, employeeNumber,
                manager == null ? null : new ScimUser.Manager(manager, null));
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
