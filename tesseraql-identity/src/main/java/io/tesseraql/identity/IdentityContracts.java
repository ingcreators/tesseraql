package io.tesseraql.identity;

/**
 * The Identity SQL Contract names (design ch. 10.5.1). Each maps to a {@code <name>.sql} file: the
 * default identity pack for managed realms, or the app's {@code security/identity/<realm>/} for sql
 * realms. The result column aliases are fixed by the contract regardless of the underlying schema.
 */
public final class IdentityContracts {

    public static final String FIND_USER_BY_LOGIN = "find-user-by-login";
    public static final String FIND_USER_BY_ID = "find-user-by-id";
    public static final String CREATE_USER = "create-user";
    public static final String FIND_CREDENTIAL_BY_LOGIN = "find-credential-by-login";
    public static final String FIND_ROLES_BY_USER_ID = "find-roles-by-user-id";
    public static final String FIND_PERMISSIONS_BY_USER_ID = "find-permissions-by-user-id";
    public static final String FIND_GROUPS_BY_USER_ID = "find-groups-by-user-id";
    public static final String LIST_USERS = "list-users";
    public static final String COUNT_USERS = "count-users";

    private IdentityContracts() {
    }

    /** The standard contract names shipped by the default identity pack (for coverage denominators). */
    public static java.util.List<String> standardContracts() {
        return java.util.List.of(FIND_USER_BY_LOGIN, FIND_USER_BY_ID, CREATE_USER,
                FIND_CREDENTIAL_BY_LOGIN, FIND_ROLES_BY_USER_ID, FIND_PERMISSIONS_BY_USER_ID,
                FIND_GROUPS_BY_USER_ID, LIST_USERS, COUNT_USERS);
    }
}
