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

    // Grant views (docs/application-roles.md slice 1); read-only and OPTIONAL — a sql realm
    // that does not provide them renders the per-application pages degraded, never fails, so
    // they are deliberately not in the standard contract set below.
    /** Users holding one exact permission code, with the role and path that delivered it. */
    public static final String FIND_PERMISSION_HOLDERS = "find-permission-holders";
    /** Permission codes under one prefix (the caller pre-escapes the LIKE pattern). */
    public static final String LIST_PERMISSIONS_BY_PREFIX = "list-permissions-by-prefix";

    // The application-role store surface (docs/application-roles.md slice 2); optional like
    // the grant views — a sql realm without them has no grant attribution and a read-only
    // admin surface, never a failure.
    /** A user's held roles with their application axis and delivered permission bundles. */
    public static final String FIND_ROLE_GRANTS_BY_USER_ID = "find-role-grants-by-user-id";
    /** A user's direct permission grants inside their validity window. */
    public static final String FIND_DIRECT_PERMISSIONS_BY_USER_ID = "find-direct-permissions-by-user-id";
    /** A user's direct role assignments, windows included, for the admin editor. */
    public static final String LIST_ROLE_ASSIGNMENTS_BY_USER_ID = "list-role-assignments-by-user-id";
    /** A user's direct permission grants, windows included, for the admin editor. */
    public static final String LIST_PERMISSION_GRANTS_BY_USER_ID = "list-permission-grants-by-user-id";
    public static final String LIST_ROLES = "list-roles";
    public static final String CREATE_ROLE = "create-role";
    public static final String GRANT_USER_ROLE = "grant-user-role";
    public static final String REVOKE_USER_ROLE = "revoke-user-role";
    public static final String GRANT_USER_PERMISSION = "grant-user-permission";
    public static final String REVOKE_USER_PERMISSION = "revoke-user-permission";

    // Declared-role reconciliation (docs/application-roles.md slice 3); managed-pack only.
    /** Insert-or-update one declared role by code, stamping source 'declared'. */
    public static final String UPSERT_DECLARED_ROLE = "upsert-declared-role";
    /** Empties one role's bundle before the declaration's codes are re-assigned. */
    public static final String CLEAR_ROLE_PERMISSIONS = "clear-role-permissions";
    /** One application's roles with their source, for orphan detection and the admin views. */
    public static final String LIST_ROLES_BY_APPLICATION = "list-roles-by-application";
    /** Stamps one role's source ('orphaned' when its declaration went away). */
    public static final String SET_ROLE_SOURCE = "set-role-source";

    // Attributes and assignment rules (docs/application-roles.md slice 4); managed-pack.
    public static final String LIST_USER_ATTRIBUTES = "list-user-attributes";
    public static final String INSERT_USER_ATTRIBUTE = "insert-user-attribute";
    public static final String DELETE_USER_ATTRIBUTE = "delete-user-attribute";
    public static final String LIST_ROLE_RULES = "list-role-rules";
    public static final String LIST_RULE_CONDITIONS = "list-rule-conditions";
    /** The enabled rules with their conditions, one joined read for sign-in evaluation. */
    public static final String FIND_ENABLED_RULE_CONDITIONS = "find-enabled-rule-conditions";
    public static final String CREATE_ROLE_RULE = "create-role-rule";
    public static final String DELETE_ROLE_RULE = "delete-role-rule";
    public static final String INSERT_RULE_CONDITION = "insert-rule-condition";
    public static final String DELETE_RULE_CONDITIONS = "delete-rule-conditions";
    /** A user's rule-produced assignments ({@code source = 'rule'}), for the converge. */
    public static final String LIST_RULE_ASSIGNMENTS_BY_USER_ID = "list-rule-assignments-by-user-id";
    public static final String GRANT_USER_ROLE_RULE = "grant-user-role-rule";
    public static final String REVOKE_USER_ROLE_RULE = "revoke-user-role-rule";
    /** Whether one org unit sits under another, via the managed closure. */
    public static final String IS_ORG_DESCENDANT = "is-org-descendant";
    public static final String LIST_USER_IDS = "list-user-ids";

    // Federated identity keys (docs/application-roles.md slice 4, second half); managed-pack.
    // The SSO linkers resolve by the immutable (provider, external subject) pair so a login-id
    // change at the IdP re-syncs the same account instead of provisioning a duplicate.
    /** The user linked to one federated identity ({@code provider} + {@code subject}). */
    public static final String FIND_USER_BY_IDENTITY = "find-user-by-identity";
    /** Records the immutable link from a federated identity to a local user. */
    public static final String LINK_USER_IDENTITY = "link-user-identity";
    /** Re-syncs a federated user's mutable profile (login id, display name, email). */
    public static final String UPDATE_FEDERATED_USER = "update-federated-user";

    /** The write contracts gated by the realm's role capability, not its user capability. */
    public static java.util.Set<String> roleManagementContracts() {
        return java.util.Set.of(CREATE_ROLE, GRANT_USER_ROLE, REVOKE_USER_ROLE,
                GRANT_USER_PERMISSION, REVOKE_USER_PERMISSION, ENSURE_ROLE, ENSURE_PERMISSION,
                ASSIGN_USER_ROLE, ASSIGN_ROLE_PERMISSION, UPSERT_DECLARED_ROLE,
                CLEAR_ROLE_PERMISSIONS, SET_ROLE_SOURCE, CREATE_ROLE_RULE, DELETE_ROLE_RULE,
                INSERT_RULE_CONDITION, DELETE_RULE_CONDITIONS, GRANT_USER_ROLE_RULE,
                REVOKE_USER_ROLE_RULE);
    }

    private IdentityContracts() {
    }

    /** The standard contract names shipped by the default identity pack (for coverage denominators). */
    public static java.util.List<String> standardContracts() {
        return java.util.List.of(FIND_USER_BY_LOGIN, FIND_USER_BY_ID, CREATE_USER,
                FIND_CREDENTIAL_BY_LOGIN, FIND_ROLES_BY_USER_ID, FIND_PERMISSIONS_BY_USER_ID,
                FIND_GROUPS_BY_USER_ID, LIST_USERS, COUNT_USERS);
    }
}
