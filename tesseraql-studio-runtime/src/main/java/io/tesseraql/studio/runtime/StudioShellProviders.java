package io.tesseraql.studio.runtime;

import io.tesseraql.core.service.ServiceProviders;
import io.tesseraql.security.policy.Atoms;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The studio shell's delegating providers (docs/studio-shell.md structural decision 2): one
 * {@code studio.shell.<op>} per export-table row, plus the switcher's {@code studio.shell.nav}.
 * Reach is filtered here — the switcher lists only members whose {@code tql.studio.edit} atom
 * the caller holds, and a page for any other member answers the 404-shaped refusal — but
 * authority stays at the member, which re-runs the same check on its own authenticated
 * principal. A shell bug can widen what is listed, never what is answered.
 */
final class StudioShellProviders {

    private StudioShellProviders() {
    }

    static void register(ServiceProviders providers, WorkshopTargets targets) {
        providers.register("studio.shell.nav", params -> {
            Map<String, Object> model = new LinkedHashMap<>();
            model.put("members", visible(targets, params.get("shellPermissions")));
            return model;
        });
        for (String op : WorkshopOps.OPS.keySet()) {
            providers.register("studio.shell." + op, params -> {
                String member = StudioSupport.str(params, "member");
                boolean tokenAuthorized = WorkshopOps.PUBLIC.contains(op);
                if (member == null || !targets.memberNames().contains(member)
                        || (!tokenAuthorized
                                && !holds(params.get("shellPermissions"), member))) {
                    throw WorkshopTargets.notFound(member);
                }
                Map<String, Object> forwarded = new LinkedHashMap<>();
                params.forEach((key, value) -> {
                    // The transport keys are the shell's own (shellCookie/shellCsrf carry the
                    // caller's credentials to the member); everything else - a page's own
                    // cookie/csrf feature params included - is the page's data and forwards.
                    if (!"member".equals(key) && !"shellCookie".equals(key)
                            && !"shellCsrf".equals(key) && !"shellCsrfHeader".equals(key)
                            && !"shellPermissions".equals(key) && !"principal".equals(key)
                            && !"Cookie".equals(key) && !"_csrf".equals(key)
                            && !"X-CSRF-Token".equals(key) && value != null) {
                        forwarded.put(key, value);
                    }
                });
                String csrf = StudioSupport.str(params, "shellCsrf") != null
                        ? StudioSupport.str(params, "shellCsrf")
                        : StudioSupport.str(params, "shellCsrfHeader");
                Object result = targets.invoke(member, op, forwarded,
                        permissionList(params.get("shellPermissions")),
                        StudioSupport.str(params, "shellCookie"), csrf);
                if (WorkshopOps.NO_CHROME.contains(op)) {
                    // A file response's model: templated bytes or text, no shell chrome -
                    // chrome strings inside a download would be data corruption.
                    return result;
                }
                if (!(result instanceof Map<?, ?> map)) {
                    // A scalar (the CSV export, a generated file): templated as-is, no chrome.
                    return result;
                }
                Map<String, Object> model = new LinkedHashMap<>();
                map.forEach((key, value) -> model.put(String.valueOf(key), value));
                model.put("shell", shell(targets, params.get("shellPermissions"), member));
                return model;
            });
        }
    }

    /** The shell map every delegated page's model carries: the switcher plus this member. */
    private static Map<String, Object> shell(WorkshopTargets targets, Object permissions,
            String member) {
        Map<String, Object> shell = new LinkedHashMap<>();
        shell.put("entries", visible(targets, permissions));
        shell.put("member", member);
        shell.put("base", "/_tesseraql/studio/" + member);
        return shell;
    }

    private static List<Map<String, Object>> visible(WorkshopTargets targets,
            Object permissions) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (String name : targets.memberNames()) {
            if (holds(permissions, name)) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", name);
                entry.put("href", "/_tesseraql/studio/" + name + "/ui");
                entries.add(entry);
            }
        }
        return entries;
    }

    private static boolean holds(Object permissions, String member) {
        return Atoms.holds(permissionList(permissions), Atoms.STUDIO_EDIT_PREFIX, member);
    }

    private static List<String> permissionList(Object permissions) {
        if (!(permissions instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }
}
