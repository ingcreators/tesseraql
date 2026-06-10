package io.tesseraql.identity;

import io.tesseraql.core.template.HtmlTemplateEngine;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Renders the read-only IAM admin console (design ch. 10) as self-contained HTML pages: a user list
 * and a per-user detail view (roles, groups, permissions). The pages render the rows returned by the
 * identity SQL contracts ({@link IdentityContracts}), escape all dynamic values, inline their styles
 * with no external resources (so they serve under a strict {@code default-src 'self'} content
 * security policy), and never mutate identity state.
 */
public final class IamAdminConsole {

    private static final String BASE = "/_tesseraql/admin/users";

    private IamAdminConsole() {
    }

    /** Renders the user list page with the total user count. */
    public static String renderUsers(List<Map<String, Object>> users, long count) {
        StringBuilder out = open("TesseraQL IAM Admin &middot; users");
        out.append("<header class=\"topbar\"><h1>IAM Admin &middot; Users</h1>")
                .append("<span class=\"badge\">").append(count).append(" total</span>")
                .append("</header>\n<main>\n<section>");
        if (users.isEmpty()) {
            out.append("<p class=\"empty\">No users found.</p>");
        } else {
            out.append("<table><thead><tr><th>Login</th><th>Display name</th><th>Email</th>")
                    .append("<th>Status</th><th>Tenant</th></tr></thead><tbody>");
            for (Map<String, Object> user : users) {
                String id = str(user, "user_id");
                out.append("<tr><td><a href=\"").append(BASE).append('/')
                        .append(escape(URLEncoder.encode(id, StandardCharsets.UTF_8)))
                        .append("\">").append(escape(str(user, "login_id"))).append("</a></td>")
                        .append(td(str(user, "display_name")))
                        .append(td(str(user, "email")))
                        .append("<td class=\"status-").append(escape(str(user, "status").toLowerCase()))
                        .append("\">").append(escape(str(user, "status"))).append("</td>")
                        .append(td(str(user, "tenant_id")))
                        .append("</tr>");
            }
            out.append("</tbody></table>");
        }
        out.append("</section>\n</main>\n</body>\n</html>\n");
        return out.toString();
    }

    /** Renders the user detail page: summary plus roles, groups and effective permissions. */
    public static String renderUser(Map<String, Object> user, List<Map<String, Object>> roles,
            List<Map<String, Object>> groups, List<Map<String, Object>> permissions) {
        StringBuilder out = open("TesseraQL IAM Admin &middot; user");
        out.append("<header class=\"topbar\"><h1>").append(escape(str(user, "login_id")))
                .append("</h1><a class=\"back\" href=\"").append(BASE)
                .append("\">&larr; users</a></header>\n<main>\n");

        out.append("<section><h2>Summary</h2><table class=\"kv\"><tbody>")
                .append(kv("User id", str(user, "user_id")))
                .append(kv("Display name", str(user, "display_name")))
                .append(kv("Email", str(user, "email")))
                .append(kv("Status", str(user, "status")))
                .append(kv("Tenant", str(user, "tenant_id")))
                .append("</tbody></table></section>\n");

        table(out, "roles", "Roles", roles,
                List.of("role_code", "role_name", "grant_type"),
                List.of("Code", "Name", "Grant"), "No roles assigned.");
        table(out, "groups", "Groups", groups,
                List.of("group_code", "group_name", "tenant_id"),
                List.of("Code", "Name", "Tenant"), "No groups assigned.");
        table(out, "permissions", "Effective permissions", permissions,
                List.of("permission_code", "permission_name"),
                List.of("Code", "Name"), "No permissions granted.");

        out.append("</main>\n</body>\n</html>\n");
        return out.toString();
    }

    private static void table(StringBuilder out, String id, String title,
            List<Map<String, Object>> rows, List<String> keys, List<String> headers, String empty) {
        out.append("<section id=\"").append(id).append("\"><h2>").append(escape(title)).append("</h2>");
        if (rows.isEmpty()) {
            out.append("<p class=\"empty\">").append(escape(empty)).append("</p>");
        } else {
            out.append("<table><thead><tr>");
            for (String header : headers) {
                out.append("<th>").append(escape(header)).append("</th>");
            }
            out.append("</tr></thead><tbody>");
            for (Map<String, Object> row : rows) {
                out.append("<tr>");
                for (String key : keys) {
                    out.append(td(str(row, key)));
                }
                out.append("</tr>");
            }
            out.append("</tbody></table>");
        }
        out.append("</section>\n");
    }

    private static StringBuilder open(String title) {
        return new StringBuilder(2048)
                .append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n")
                .append("<meta charset=\"utf-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<title>").append(title).append("</title>\n")
                .append("<style>").append(styles()).append("</style>\n")
                .append("</head>\n<body>\n");
    }

    private static String kv(String key, String value) {
        return "<tr><th>" + escape(key) + "</th>" + td(value) + "</tr>";
    }

    private static String td(String value) {
        return "<td>" + escape(value) + "</td>";
    }

    private static String str(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static String escape(String value) {
        return HtmlTemplateEngine.escape(value == null ? "" : value);
    }

    private static String styles() {
        return "*{box-sizing:border-box}"
                + "body{margin:0;font-family:system-ui,sans-serif;background:#0f172a;color:#e2e8f0}"
                + ".topbar{display:flex;align-items:center;justify-content:space-between;"
                + "padding:16px 24px;background:#1e293b;border-bottom:1px solid #334155}"
                + ".topbar h1{font-size:16px;margin:0}"
                + "main{padding:24px;max-width:1000px;margin:0 auto}"
                + "section{background:#1e293b;border:1px solid #334155;border-radius:8px;"
                + "padding:16px 20px;margin-bottom:20px}"
                + "h2{font-size:15px;margin:0 0 12px;color:#93c5fd}"
                + "table{width:100%;border-collapse:collapse;font-size:13px}"
                + "th,td{text-align:left;padding:6px 10px;border-bottom:1px solid #334155}"
                + "th{color:#94a3b8;font-weight:600}"
                + "table.kv th{width:160px}"
                + "a{color:#93c5fd}"
                + ".back{font-size:13px;text-decoration:none}"
                + ".badge{padding:4px 12px;border-radius:999px;font-size:12px;font-weight:700;"
                + "background:#334155}"
                + ".empty{color:#94a3b8;font-style:italic;margin:0}"
                + ".status-active{color:#86efac}.status-disabled{color:#fca5a5}"
                + ".status-locked{color:#fca5a5}";
    }
}
