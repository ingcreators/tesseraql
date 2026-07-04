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
    public static final String ENABLE_USER = "enable-user";
    public static final String DISABLE_USER = "disable-user";

    // Bootstrap helpers (design ch. 18 identity goals); managed-pack only, not part of the
    // standard contract set a sql realm must provide.
    /** Self-service credential rotation (roadmap Phase 48, the account surface). */
    public static final String UPDATE_PASSWORD = "update-password";
    /** Where a password-reset link may be sent (roadmap Phase 50); no row = not by mail. */
    public static final String FIND_RECOVERY_DESTINATION = "find-recovery-destination-by-login";
    public static final String SEED_ADMIN_USER = "seed-admin-user";
    public static final String ENSURE_ROLE = "ensure-role";
    public static final String ASSIGN_USER_ROLE = "assign-user-role";
    public static final String ENSURE_PERMISSION = "ensure-permission";
    public static final String ASSIGN_ROLE_PERMISSION = "assign-role-permission";

    private IdentityContracts() {
    }

    /** The standard contract names shipped by the default identity pack (for coverage denominators). */
    public static java.util.List<String> standardContracts() {
        return java.util.List.of(FIND_USER_BY_LOGIN, FIND_USER_BY_ID, CREATE_USER,
                FIND_CREDENTIAL_BY_LOGIN, FIND_ROLES_BY_USER_ID, FIND_PERMISSIONS_BY_USER_ID,
                FIND_GROUPS_BY_USER_ID, LIST_USERS, COUNT_USERS);
    }
}
