package io.tesseraql.identity;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.security.policy.Atoms;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The per-application grant views (docs/application-roles.md slice 1): read-only answers to
 * "who may do what in this application", derived from the atom grammar and the identity store
 * with no new declarations. The IAM Admin pages reach these through the
 * {@code iam.applications} and {@code iam.applicationGrants} providers; the member list comes
 * from the stack (or the single unhosted application), never from the store.
 *
 * <p>The two backing contracts are optional: a {@code sql} realm that does not provide them
 * renders the pages degraded — the model says so — rather than failing them.
 */
public final class GrantViews {

    /** One atom family shown per application: a label and the prefix the name completes. */
    private record Family(String key, String label, String prefix) {
    }

    private static final List<Family> FAMILIES = List.of(
            new Family("use", "May use the application", Atoms.APP_USE_PREFIX),
            new Family("opsView", "May see its operational data", Atoms.OPS_VIEW_PREFIX),
            new Family("opsRun", "May act on its operations", Atoms.OPS_RUN_PREFIX),
            new Family("deploy", "May deploy it", Atoms.APP_DEPLOY_PREFIX),
            new Family("iamView", "May see its access", Atoms.IAM_VIEW_PREFIX),
            new Family("iamWrite", "May administer its access", Atoms.IAM_WRITE_PREFIX),
            new Family("studio", "May edit it in Studio (reserved)", Atoms.STUDIO_EDIT_PREFIX));

    /** Executes one identity contract; the runtime binds this to the realm's service. */
    @FunctionalInterface
    public interface ContractRunner {
        List<Map<String, Object>> run(String contract, Map<String, Object> params);
    }

    private GrantViews() {
    }

    /**
     * The applications list: one row per stack member with its exact
     * {@code tql.app.use.<name>} holder count, plus the wildcard holders shown once — a
     * wildcard grant reaches every member, so it is not a per-row number.
     */
    public static Map<String, Object> applications(List<String> members, List<String> permissions,
            ContractRunner runner) {
        Map<String, Object> model = new LinkedHashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            for (String member : visible(members, permissions)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", member);
                row.put("holders",
                        distinctUsers(holders(runner, Atoms.APP_USE_PREFIX + member)));
                rows.add(row);
            }
            model.put("wildcardHolders",
                    distinctUsers(holders(runner, Atoms.APP_USE_PREFIX + "*")));
            model.put("available", 1);
        } catch (TqlException ex) {
            if (!ContractResolver.MISSING_CONTRACT.equals(ex.code())) {
                throw ex;
            }
            return applicationsUnavailable(members, permissions, ex.getMessage());
        }
        model.put("rows", rows);
        return model;
    }

    /**
     * The applications this caller may see: every member for the store-wide
     * {@code tql.iam.admin.view}, otherwise the ones whose {@code tql.iam.view.<name>} they
     * hold (docs/access-governance.md structural decision 7).
     *
     * <p>The list is the one page in this family with no application in its address, so there
     * is no atom for a route policy to resolve. It follows the stack shell's answer to the same
     * shape — the ops console home carries no policy id and narrows its switcher by the
     * caller's grants — so a caller holding nothing here sees an empty list rather than an open
     * door, and sees no more of the deployment than their grants already showed them.
     */
    private static List<String> visible(List<String> members, List<String> permissions) {
        if (permissions != null && permissions.contains(Atoms.IAM_ADMIN_VIEW)) {
            return members;
        }
        List<String> mine = new ArrayList<>();
        for (String member : members) {
            if (Atoms.holds(permissions, Atoms.IAM_VIEW_PREFIX, member)) {
                mine.add(member);
            }
        }
        return mine;
    }

    /** The list's degraded model: names only, and the page says why (never fails). */
    public static Map<String, Object> applicationsUnavailable(List<String> members,
            List<String> permissions, String reason) {
        Map<String, Object> model = new LinkedHashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String member : visible(members, permissions)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", member);
            rows.add(row);
        }
        model.put("available", 0);
        model.put("reason", reason);
        model.put("rows", rows);
        return model;
    }

    /**
     * One application's grant page: every atom family with its exact and wildcard holders, and
     * the application's own permission codes (its name-prefixed vocabulary) with theirs. An
     * unknown name answers {@code known: 0} so the route can 404; a store nobody has granted
     * anything in answers {@code hasAny: 0} so the page can render the deny-by-default state.
     */
    public static Map<String, Object> applicationGrants(String name, List<String> members,
            List<String> permissions, ContractRunner runner) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("name", name);
        model.put("known", members.contains(name) ? 1 : 0);
        if (!members.contains(name)) {
            return model;
        }
        // Whether this caller may write here at all, so the page offers only what the writes
        // would accept. The forms are the convenience; AdminScope inside RoleAdmin is the
        // control, and it runs again on every POST whatever this page rendered.
        model.put("canWrite",
                AdminScope.of(permissions, members).confinedTo(name).canWrite() ? 1 : 0);
        try {
            boolean any = false;
            List<Map<String, Object>> families = new ArrayList<>();
            for (Family family : FAMILIES) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("key", family.key());
                entry.put("label", family.label());
                entry.put("atom", family.prefix() + name);
                entry.put("wildcard", family.prefix() + "*");
                List<Map<String, Object>> exact = holders(runner, family.prefix() + name);
                List<Map<String, Object>> wildcard = holders(runner, family.prefix() + "*");
                entry.put("rows", exact);
                entry.put("wildcardRows", wildcard);
                entry.put("empty", exact.isEmpty() && wildcard.isEmpty() ? 1 : 0);
                any = any || !exact.isEmpty() || !wildcard.isEmpty();
                families.add(entry);
            }
            model.put("families", families);
            List<Map<String, Object>> codes = new ArrayList<>();
            for (Map<String, Object> code : runner.run(
                    IdentityContracts.LIST_PERMISSIONS_BY_PREFIX,
                    Map.of("prefix", escapeLike(name + ".")))) {
                Map<String, Object> entry = new LinkedHashMap<>();
                String permissionCode = String.valueOf(code.get("permission_code"));
                entry.put("code", permissionCode);
                entry.put("codeName", code.get("permission_name"));
                List<Map<String, Object>> codeHolders = holders(runner, permissionCode);
                entry.put("rows", codeHolders);
                any = any || !codeHolders.isEmpty();
                codes.add(entry);
            }
            model.put("codes", codes);
            model.put("hasAny", any || !codes.isEmpty() ? 1 : 0);
            // This application's own roles, for the assignment form's choices — the same set
            // the writes will accept, since a role outside it is not this application's.
            model.put("roles", runner.run(IdentityContracts.LIST_ROLES_BY_APPLICATION,
                    Map.of("application", name)));
            model.put("available", 1);
        } catch (TqlException ex) {
            if (!ContractResolver.MISSING_CONTRACT.equals(ex.code())) {
                throw ex;
            }
            return applicationGrantsUnavailable(name, members, ex.getMessage());
        }
        return model;
    }

    /** One application's degraded model (the realm answers no grant-view contracts). */
    public static Map<String, Object> applicationGrantsUnavailable(String name,
            List<String> members, String reason) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("name", name);
        model.put("known", members.contains(name) ? 1 : 0);
        model.put("families", List.of());
        model.put("codes", List.of());
        model.put("roles", List.of());
        model.put("hasAny", 0);
        // A store that cannot answer the reads behind this page is not one to write through:
        // the forms would offer choices nothing verified.
        model.put("canWrite", 0);
        model.put("available", 0);
        model.put("reason", reason);
        return model;
    }

    private static List<Map<String, Object>> holders(ContractRunner runner, String code) {
        return runner.run(IdentityContracts.FIND_PERMISSION_HOLDERS, Map.of("code", code));
    }

    private static int distinctUsers(List<Map<String, Object>> rows) {
        return (int) rows.stream().map(row -> row.get("login_id")).distinct().count();
    }

    /**
     * Escapes a literal for the {@code like … escape '#'} pattern the prefix contract declares.
     * An application name is dot-free but otherwise open (non-ASCII legal), so the pattern
     * characters are escaped rather than assumed absent.
     */
    static String escapeLike(String literal) {
        return literal.replace("#", "##").replace("%", "#%").replace("_", "#_");
    }
}
