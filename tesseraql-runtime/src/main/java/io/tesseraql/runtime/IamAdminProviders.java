package io.tesseraql.runtime;

import io.tesseraql.pipeline.RuntimeContext;
import io.tesseraql.pipeline.TesseraqlProperties;
import java.util.Map;

/**
 * The {@code iam.*} administration providers behind the bundled iam-admin app: grant views,
 * the role and grant editors, delegated administration, elevation, reviews, requests, groups,
 * conditions, attributes and the recompute actions (docs/application-roles.md,
 * docs/access-governance.md). Extracted verbatim from the runtime boot
 * (docs/boot-phases.md slice 1) - registration order and lambda bodies are exactly what
 * {@code TesseraqlRuntime.start(...)} inlined; this class only relocates them.
 *
 * <p>Identity and realm resolve lazily: they bind after the boot's provider chain builds, and a
 * boot with no realm answers the same degraded model a sql realm without the optional contracts
 * gets.
 */
final class IamAdminProviders {

    private IamAdminProviders() {
    }

    /**
     * Registers the {@code iam.*} providers wherever iam-admin mounts: the surface runtime
     * ({@code stackMembers} non-null) or the unhosted boot (a stack of one). A hosted member is
     * neither, and registers nothing - its iam-admin surface is the stack's.
     */
    static void register(io.tesseraql.core.service.ServiceProviders serviceProviders,
            RuntimeContext context, io.tesseraql.yaml.manifest.AppManifest manifest,
            String appName,
            java.util.List<io.tesseraql.operations.app.InstalledApp> stackMembers,
            boolean unhosted) {
        if (stackMembers == null && !unhosted) {
            return;
        }
        java.util.List<String> grantViewMembers = stackMembers != null
                ? stackMembers.stream()
                        .map(io.tesseraql.operations.app.InstalledApp::name).toList()
                : java.util.List.of(appName);
        java.util.function.Supplier<io.tesseraql.identity.GrantViews.ContractRunner> grantContracts = () -> {
            io.tesseraql.identity.IdentityService identity = context.lookup(
                    TesseraqlProperties.IDENTITY_SERVICE_BEAN,
                    io.tesseraql.identity.IdentityService.class);
            io.tesseraql.identity.RealmConfig realm = context.lookup(
                    TesseraqlProperties.IDENTITY_REALM_BEAN,
                    io.tesseraql.identity.RealmConfig.class);
            if (identity == null || realm == null) {
                return null;
            }
            return (contract, contractParams) -> identity.execute(realm,
                    contract, contractParams);
        };
        serviceProviders.register("iam.applications", params -> {
            io.tesseraql.identity.GrantViews.ContractRunner runner = grantContracts.get();
            return runner == null
                    ? io.tesseraql.identity.GrantViews.applicationsUnavailable(
                            grantViewMembers, heldPermissions(params),
                            "No identity realm is configured")
                    : io.tesseraql.identity.GrantViews.applications(grantViewMembers,
                            heldPermissions(params), runner);
        });
        serviceProviders.register("iam.applicationGrants", params -> {
            String memberName = String.valueOf(params.get("name"));
            io.tesseraql.identity.GrantViews.ContractRunner runner = grantContracts.get();
            return runner == null
                    ? io.tesseraql.identity.GrantViews.applicationGrantsUnavailable(
                            memberName, grantViewMembers,
                            "No identity realm is configured")
                    : io.tesseraql.identity.GrantViews.applicationGrants(memberName,
                            grantViewMembers, heldPermissions(params), runner);
        });
        // The role and grant editors (docs/application-roles.md slice 2): reads
        // degrade like the views; writes are gated by the realm's role capability
        // inside IdentityService.executeUpdate.
        java.util.function.Supplier<io.tesseraql.identity.IdentityService> iamIdentity = () -> context
                .lookup(
                        TesseraqlProperties.IDENTITY_SERVICE_BEAN,
                        io.tesseraql.identity.IdentityService.class);
        java.util.function.Supplier<io.tesseraql.identity.RealmConfig> iamRealm = () -> context
                .lookup(
                        TesseraqlProperties.IDENTITY_REALM_BEAN,
                        io.tesseraql.identity.RealmConfig.class);
        // What this caller may administer (docs/access-governance.md structural
        // decision 7): store-wide, or confined to the applications whose delegated
        // atom they hold. Derived from the route-declared principal permissions, so
        // it is route-resolved and never caller-writable.
        java.util.function.Function<Map<String, Object>, io.tesseraql.identity.AdminScope> adminScope = params -> io.tesseraql.identity.AdminScope
                .of(heldPermissions(params),
                        grantViewMembers);
        // The same scope narrowed to the application the request is addressed to, for
        // the per-application pages: the URL names one application, so a write arriving
        // through it belongs to that one whoever the caller is.
        java.util.function.Function<Map<String, Object>, io.tesseraql.identity.AdminScope> appScope = params -> adminScope
                .apply(params)
                .confinedTo(String.valueOf(params.get("application")));
        serviceProviders.register("iam.roles",
                params -> io.tesseraql.identity.RoleAdmin.rolesModel(iamIdentity.get(),
                        iamRealm.get(), grantViewMembers));
        serviceProviders.register("iam.grantEditor",
                params -> io.tesseraql.identity.RoleAdmin.grantEditorModel(
                        iamIdentity.get(), iamRealm.get(),
                        String.valueOf(params.get("userId"))));
        serviceProviders.register("iam.createRole",
                params -> io.tesseraql.identity.RoleAdmin.createRole(iamIdentity.get(),
                        iamRealm.get(), adminScope.apply(params),
                        String.valueOf(params.get("code")),
                        String.valueOf(params.get("name")),
                        String.valueOf(params.get("application"))));
        // The actor is a declared route parameter (`actor: principal.loginId`), route-
        // resolved and never caller-writable, exactly as the role picker declares
        // principal.roleGrants (docs/access-governance.md structural decision 1).
        serviceProviders.register("iam.assignRole",
                params -> io.tesseraql.identity.RoleAdmin.assignRole(iamIdentity.get(),
                        iamRealm.get(), adminScope.apply(params),
                        String.valueOf(params.get("actor")),
                        String.valueOf(params.get("userId")),
                        String.valueOf(params.get("roleCode")),
                        String.valueOf(params.get("startsAt")),
                        String.valueOf(params.get("endsAt")),
                        io.tesseraql.identity.GrantHistory.SOURCE_ADMIN, null));
        serviceProviders.register("iam.unassignRole",
                params -> io.tesseraql.identity.RoleAdmin.unassignRole(
                        iamIdentity.get(), iamRealm.get(), adminScope.apply(params),
                        String.valueOf(params.get("actor")),
                        String.valueOf(params.get("userId")),
                        String.valueOf(params.get("roleCode")),
                        io.tesseraql.identity.GrantHistory.SOURCE_ADMIN, null));
        serviceProviders.register("iam.grantPermission",
                params -> io.tesseraql.identity.RoleAdmin.grantPermission(
                        iamIdentity.get(), iamRealm.get(), adminScope.apply(params),
                        String.valueOf(params.get("actor")),
                        String.valueOf(params.get("userId")),
                        String.valueOf(params.get("code")),
                        String.valueOf(params.get("startsAt")),
                        String.valueOf(params.get("endsAt"))));
        serviceProviders.register("iam.revokePermission",
                params -> io.tesseraql.identity.RoleAdmin.revokePermission(
                        iamIdentity.get(), iamRealm.get(), adminScope.apply(params),
                        String.valueOf(params.get("actor")),
                        String.valueOf(params.get("userId")),
                        String.valueOf(params.get("code")),
                        io.tesseraql.identity.GrantHistory.SOURCE_ADMIN, null));
        // The per-application writes (docs/access-governance.md structural decision 7).
        // Same RoleAdmin calls as above, reached through a page addressed to one
        // application: the scope is narrowed to it, and the user is named by the login
        // the administrator was given rather than by a key only the store list shows.
        serviceProviders.register("iam.app.createRole",
                params -> io.tesseraql.identity.RoleAdmin.createRole(iamIdentity.get(),
                        iamRealm.get(), appScope.apply(params),
                        String.valueOf(params.get("code")),
                        String.valueOf(params.get("name")),
                        String.valueOf(params.get("application"))));
        serviceProviders.register("iam.app.assignRole",
                params -> io.tesseraql.identity.RoleAdmin.assignRole(iamIdentity.get(),
                        iamRealm.get(), appScope.apply(params),
                        String.valueOf(params.get("actor")),
                        loginToUserId(iamIdentity.get(), iamRealm.get(), params),
                        String.valueOf(params.get("roleCode")),
                        String.valueOf(params.get("startsAt")),
                        String.valueOf(params.get("endsAt")),
                        io.tesseraql.identity.GrantHistory.SOURCE_ADMIN, null));
        serviceProviders.register("iam.app.unassignRole",
                params -> io.tesseraql.identity.RoleAdmin.unassignRole(iamIdentity.get(),
                        iamRealm.get(), appScope.apply(params),
                        String.valueOf(params.get("actor")),
                        loginToUserId(iamIdentity.get(), iamRealm.get(), params),
                        String.valueOf(params.get("roleCode")),
                        io.tesseraql.identity.GrantHistory.SOURCE_ADMIN, null));
        serviceProviders.register("iam.app.grantPermission",
                params -> io.tesseraql.identity.RoleAdmin.grantPermission(
                        iamIdentity.get(), iamRealm.get(), appScope.apply(params),
                        String.valueOf(params.get("actor")),
                        loginToUserId(iamIdentity.get(), iamRealm.get(), params),
                        String.valueOf(params.get("code")),
                        String.valueOf(params.get("startsAt")),
                        String.valueOf(params.get("endsAt"))));
        serviceProviders.register("iam.app.revokePermission",
                params -> io.tesseraql.identity.RoleAdmin.revokePermission(
                        iamIdentity.get(), iamRealm.get(), appScope.apply(params),
                        String.valueOf(params.get("actor")),
                        loginToUserId(iamIdentity.get(), iamRealm.get(), params),
                        String.valueOf(params.get("code")),
                        io.tesseraql.identity.GrantHistory.SOURCE_ADMIN, null));
        // Separation of duties (docs/access-governance.md structural decision 2):
        // the page reads constraints and existing violations; the checkpoints live
        // inside the grant writes themselves, not here.
        serviceProviders.register("iam.constraints",
                params -> io.tesseraql.identity.SeparationOfDuties.constraintsModel(
                        iamIdentity.get(), iamRealm.get()));
        serviceProviders.register("iam.createConstraint",
                params -> io.tesseraql.identity.RoleAdmin.createConstraint(
                        iamIdentity.get(), iamRealm.get(),
                        String.valueOf(params.get("name")),
                        String.valueOf(params.get("severity")),
                        String.valueOf(params.get("firstRole")),
                        String.valueOf(params.get("secondRole"))));
        serviceProviders.register("iam.addConstraintRole",
                params -> io.tesseraql.identity.RoleAdmin.addConstraintRole(
                        iamIdentity.get(), iamRealm.get(),
                        String.valueOf(params.get("constraintId")),
                        String.valueOf(params.get("roleCode"))));
        serviceProviders.register("iam.deleteConstraint",
                params -> io.tesseraql.identity.RoleAdmin.deleteConstraint(
                        iamIdentity.get(), iamRealm.get(),
                        String.valueOf(params.get("constraintId"))));
        // Self-service access requests (docs/access-governance.md structural
        // decision 6). The approver queue is filtered by ownership against the
        // caller's own principal, so a request only ever reaches somebody who owns
        // the role it asks for.
        serviceProviders.register("iam.requestQueue",
                params -> io.tesseraql.identity.AccessRequests.queueModel(
                        iamIdentity.get(), iamRealm.get(),
                        String.valueOf(params.get("subject")),
                        params.get("groups") instanceof java.util.List<?> held
                                ? held.stream().map(String::valueOf).toList()
                                : java.util.List.of()));
        serviceProviders.register("iam.decideRequest",
                params -> io.tesseraql.identity.AccessRequests.decide(
                        iamIdentity.get(), iamRealm.get(),
                        String.valueOf(params.get("actor")),
                        String.valueOf(params.get("requestId")),
                        String.valueOf(params.get("decision")),
                        String.valueOf(params.get("note"))));
        serviceProviders.register("iam.roleOwners",
                params -> io.tesseraql.identity.AccessRequests.ownersModel(
                        iamIdentity.get(), iamRealm.get()));
        serviceProviders.register("iam.addRoleOwner",
                params -> io.tesseraql.identity.AccessRequests.addOwner(
                        iamIdentity.get(), iamRealm.get(),
                        String.valueOf(params.get("roleCode")),
                        String.valueOf(params.get("ownerKind")),
                        String.valueOf(params.get("ownerRef"))));
        serviceProviders.register("iam.removeRoleOwner",
                params -> io.tesseraql.identity.AccessRequests.removeOwner(
                        iamIdentity.get(), iamRealm.get(),
                        String.valueOf(params.get("roleCode")),
                        String.valueOf(params.get("ownerKind")),
                        String.valueOf(params.get("ownerRef"))));
        // Access review campaigns (docs/access-governance.md structural decision 5):
        // a snapshot, decisions on it, and revocations executed through RoleAdmin at
        // close so each one inherits its validation and its trail row.
        serviceProviders.register("iam.reviews",
                params -> io.tesseraql.identity.AccessReview.reviewsModel(
                        iamIdentity.get(), iamRealm.get(), grantViewMembers));
        serviceProviders.register("iam.review",
                params -> io.tesseraql.identity.AccessReview.reviewModel(
                        iamIdentity.get(), iamRealm.get(),
                        String.valueOf(params.get("reviewId"))));
        serviceProviders.register("iam.openReview",
                params -> io.tesseraql.identity.AccessReview.open(iamIdentity.get(),
                        iamRealm.get(), String.valueOf(params.get("actor")),
                        String.valueOf(params.get("name")),
                        String.valueOf(params.get("application"))));
        serviceProviders.register("iam.decideReviewItem",
                params -> io.tesseraql.identity.AccessReview.decide(iamIdentity.get(),
                        iamRealm.get(), String.valueOf(params.get("actor")),
                        String.valueOf(params.get("reviewId")),
                        String.valueOf(params.get("userId")),
                        String.valueOf(params.get("itemKind")),
                        String.valueOf(params.get("subjectCode")),
                        String.valueOf(params.get("decision")),
                        String.valueOf(params.get("note"))));
        serviceProviders.register("iam.closeReview",
                params -> io.tesseraql.identity.AccessReview.close(iamIdentity.get(),
                        iamRealm.get(), String.valueOf(params.get("actor")),
                        String.valueOf(params.get("reviewId"))));
        // Group management (docs/access-governance.md structural decision 4): the
        // schema was complete and nothing wrote it. Membership writes take the
        // actor for the trail, like the role and permission writes.
        serviceProviders.register("iam.groups",
                params -> io.tesseraql.identity.GroupAdmin.groupsModel(
                        iamIdentity.get(), iamRealm.get()));
        serviceProviders.register("iam.group",
                params -> io.tesseraql.identity.GroupAdmin.groupModel(
                        iamIdentity.get(), iamRealm.get(),
                        String.valueOf(params.get("groupCode"))));
        serviceProviders.register("iam.createGroup",
                params -> io.tesseraql.identity.GroupAdmin.createGroup(
                        iamIdentity.get(), iamRealm.get(),
                        String.valueOf(params.get("code")),
                        String.valueOf(params.get("name"))));
        serviceProviders.register("iam.deleteGroup",
                params -> io.tesseraql.identity.GroupAdmin.deleteGroup(
                        iamIdentity.get(), iamRealm.get(),
                        String.valueOf(params.get("actor")),
                        String.valueOf(params.get("groupCode"))));
        serviceProviders.register("iam.addGroupMember",
                params -> io.tesseraql.identity.GroupAdmin.addMember(
                        iamIdentity.get(), iamRealm.get(),
                        String.valueOf(params.get("actor")),
                        String.valueOf(params.get("groupCode")),
                        String.valueOf(params.get("userId")),
                        String.valueOf(params.get("startsAt")),
                        String.valueOf(params.get("endsAt"))));
        serviceProviders.register("iam.removeGroupMember",
                params -> io.tesseraql.identity.GroupAdmin.removeMember(
                        iamIdentity.get(), iamRealm.get(),
                        String.valueOf(params.get("actor")),
                        String.valueOf(params.get("groupCode")),
                        String.valueOf(params.get("userId"))));
        serviceProviders.register("iam.grantGroupRole",
                params -> io.tesseraql.identity.GroupAdmin.grantRole(
                        iamIdentity.get(), iamRealm.get(),
                        String.valueOf(params.get("groupCode")),
                        String.valueOf(params.get("roleCode"))));
        serviceProviders.register("iam.revokeGroupRole",
                params -> io.tesseraql.identity.GroupAdmin.revokeRole(
                        iamIdentity.get(), iamRealm.get(),
                        String.valueOf(params.get("groupCode")),
                        String.valueOf(params.get("roleCode"))));
        // Eligibility, the administrator's side of elevation
        // (docs/access-governance.md structural decision 3). Taking the role is
        // the account surface's own Java route, because only that layer can make
        // the elevation live in the caller's session.
        serviceProviders.register("iam.eligibility",
                params -> io.tesseraql.identity.Elevation.eligibilityModel(
                        iamIdentity.get(), iamRealm.get(),
                        String.valueOf(params.get("userId"))));
        serviceProviders.register("iam.grantEligibility",
                params -> io.tesseraql.identity.Elevation.grantEligibility(
                        iamIdentity.get(), iamRealm.get(),
                        String.valueOf(params.get("userId")),
                        String.valueOf(params.get("roleCode")),
                        String.valueOf(params.get("maxMinutes")),
                        "1".equals(String.valueOf(params.get("requiresReason")))));
        serviceProviders.register("iam.revokeEligibility",
                params -> io.tesseraql.identity.Elevation.revokeEligibility(
                        iamIdentity.get(), iamRealm.get(),
                        String.valueOf(params.get("userId")),
                        String.valueOf(params.get("roleCode"))));
        // Grant context conditions (docs/access-governance.md structural decision 8).
        // The page declares them; the evaluation is a compiled step on every secured
        // route, so nothing here decides who gets in.
        serviceProviders.register("iam.conditions",
                params -> io.tesseraql.identity.RoleConditions.conditionsModel(
                        iamIdentity.get(), iamRealm.get()));
        serviceProviders.register("iam.addCondition",
                params -> io.tesseraql.identity.RoleConditions.addCondition(
                        iamIdentity.get(), iamRealm.get(),
                        String.valueOf(params.get("roleCode")),
                        String.valueOf(params.get("conditionKind")),
                        String.valueOf(params.get("value"))));
        serviceProviders.register("iam.removeCondition",
                params -> io.tesseraql.identity.RoleConditions.removeCondition(
                        iamIdentity.get(), iamRealm.get(),
                        String.valueOf(params.get("roleCode")),
                        String.valueOf(params.get("conditionKind")),
                        String.valueOf(params.get("value"))));
        serviceProviders.register("iam.grantHistory",
                params -> io.tesseraql.identity.GrantHistory.historyModel(
                        iamIdentity.get(), iamRealm.get(),
                        String.valueOf(params.get("userId")),
                        String.valueOf(params.get("application"))));
        // Attributes and assignment rules (docs/application-roles.md slice 4).
        boolean orgManaged = "managed".equals(manifest.config()
                .getString("tesseraql.orgunit.mode").orElse("app"));
        serviceProviders.register("iam.rules",
                params -> io.tesseraql.identity.RoleAdmin.rulesModel(
                        iamIdentity.get(), iamRealm.get()));
        serviceProviders.register("iam.createRoleRule",
                params -> io.tesseraql.identity.RoleAdmin.createRule(
                        iamIdentity.get(), iamRealm.get(),
                        String.valueOf(params.get("roleCode")),
                        String.valueOf(params.get("attribute")),
                        String.valueOf(params.get("kind")),
                        String.valueOf(params.get("value")), orgManaged));
        serviceProviders.register("iam.addRuleCondition",
                params -> io.tesseraql.identity.RoleAdmin.addRuleCondition(
                        iamIdentity.get(), iamRealm.get(),
                        String.valueOf(params.get("ruleId")),
                        String.valueOf(params.get("attribute")),
                        String.valueOf(params.get("kind")),
                        String.valueOf(params.get("value")), orgManaged));
        serviceProviders.register("iam.deleteRoleRule",
                params -> io.tesseraql.identity.RoleAdmin.deleteRule(
                        iamIdentity.get(), iamRealm.get(),
                        String.valueOf(params.get("ruleId"))));
        serviceProviders.register("iam.setAttribute",
                params -> io.tesseraql.identity.RoleAdmin.setAttribute(
                        iamIdentity.get(), iamRealm.get(),
                        String.valueOf(params.get("userId")),
                        String.valueOf(params.get("name")),
                        String.valueOf(params.get("value"))));
        serviceProviders.register("iam.deleteAttribute",
                params -> io.tesseraql.identity.RoleAdmin.deleteAttribute(
                        iamIdentity.get(), iamRealm.get(),
                        String.valueOf(params.get("userId")),
                        String.valueOf(params.get("name"))));
        serviceProviders.register("iam.recomputeUser", params -> Map.of("recomputed",
                io.tesseraql.identity.RoleRules.recompute(iamIdentity.get(),
                        iamRealm.get(), String.valueOf(params.get("userId")))
                        .size()));
        serviceProviders.register("iam.recomputeAll",
                params -> io.tesseraql.identity.RoleAdmin.recomputeAll(
                        iamIdentity.get(), iamRealm.get()));
    }

    /**
     * The caller's granted permission codes as the route declared them
     * ({@code permissions: principal.permissions}), or none.
     *
     * <p>Route-resolved from the session principal and so never caller-writable: what arrives
     * here is what the store granted, not what a request asked to be treated as.
     */
    private static java.util.List<String> heldPermissions(java.util.Map<String, Object> params) {
        return params.get("permissions") instanceof java.util.List<?> held
                ? held.stream().map(String::valueOf).toList()
                : java.util.List.of();
    }

    /**
     * The store id of the user a per-application write names by login
     * (docs/access-governance.md structural decision 7).
     */
    private static String loginToUserId(io.tesseraql.identity.IdentityService identity,
            io.tesseraql.identity.RealmConfig realm, java.util.Map<String, Object> params) {
        return io.tesseraql.identity.RoleAdmin.userIdForLogin(identity, realm,
                String.valueOf(params.get("loginId")));
    }
}
