package io.tesseraql.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IamAdminConsoleTest {

    @Test
    void rendersUserListWithCountAndLinks() {
        List<Map<String, Object>> users = List.of(
                Map.of("user_id", "u1", "login_id", "sato", "display_name", "Sato",
                        "email", "sato@example.com", "status", "ACTIVE", "tenant_id", "t1"));

        String html = IamAdminConsole.renderUsers(users, 1);

        assertThat(html).startsWith("<!DOCTYPE html>");
        assertThat(html).contains("IAM Admin").contains("1 total");
        assertThat(html).contains("sato").contains("sato@example.com").contains("ACTIVE");
        assertThat(html).contains("/_tesseraql/admin/users/u1");
    }

    @Test
    void rendersEmptyUserList() {
        assertThat(IamAdminConsole.renderUsers(List.of(), 0)).contains("No users found.");
    }

    @Test
    void rendersUserDetailWithRolesGroupsPermissions() {
        Map<String, Object> user = Map.of("user_id", "u1", "login_id", "sato",
                "display_name", "Sato", "email", "sato@example.com", "status", "ACTIVE",
                "tenant_id", "t1");
        List<Map<String, Object>> roles = List.of(
                Map.of("role_code", "ADMIN", "role_name", "Administrator", "grant_type", "DIRECT"));
        List<Map<String, Object>> groups = List.of(
                Map.of("group_code", "OPS", "group_name", "Operations", "tenant_id", "t1"));
        List<Map<String, Object>> perms = List.of(
                Map.of("permission_code", "users:read", "permission_name", "Read users"));

        String html = IamAdminConsole.renderUser(user, roles, groups, perms);

        assertThat(html).contains("sato").contains("sato@example.com");
        assertThat(html).contains("Administrator").contains("DIRECT");
        assertThat(html).contains("Operations");
        assertThat(html).contains("users:read").contains("Read users");
        assertThat(html).contains("href=\"/_tesseraql/admin/users\"");
    }

    @Test
    void rendersEmptyDetailSections() {
        String html = IamAdminConsole.renderUser(
                Map.of("user_id", "u1", "login_id", "x"), List.of(), List.of(), List.of());

        assertThat(html).contains("No roles assigned.");
        assertThat(html).contains("No groups assigned.");
        assertThat(html).contains("No permissions granted.");
    }

    @Test
    void rendersDisableActionWhenWritableAndActive() {
        Map<String, Object> user = Map.of("user_id", "u1", "login_id", "sato", "status", "ACTIVE");

        String html = IamAdminConsole.renderUser(user, List.of(), List.of(), List.of(), true, null);

        assertThat(html).contains("id=\"actions\"").contains("Disable user");
        assertThat(html).contains("action=\"/_tesseraql/admin/users/u1/disable\"");
        assertThat(html).doesNotContain("Enable user");
    }

    @Test
    void rendersEnableActionWhenWritableAndDisabled() {
        Map<String, Object> user = Map.of("user_id", "u1", "login_id", "sato", "status", "DISABLED");

        String html = IamAdminConsole.renderUser(user, List.of(), List.of(), List.of(), true,
                "User disabled.");

        assertThat(html).contains("Enable user");
        assertThat(html).contains("action=\"/_tesseraql/admin/users/u1/enable\"");
        assertThat(html).contains("class=\"status\"").contains("User disabled.");
        assertThat(html).doesNotContain("Disable user");
    }

    @Test
    void omitsActionsWhenReadOnly() {
        Map<String, Object> user = Map.of("user_id", "u1", "login_id", "sato", "status", "ACTIVE");

        String html = IamAdminConsole.renderUser(user, List.of(), List.of(), List.of(), false, null);

        assertThat(html).doesNotContain("id=\"actions\"")
                .doesNotContain("Disable user").doesNotContain("Enable user");
    }

    @Test
    void escapesDynamicValues() {
        List<Map<String, Object>> users = List.of(
                Map.of("user_id", "u1", "login_id", "<script>", "display_name", "a&b",
                        "email", "", "status", "ACTIVE", "tenant_id", ""));

        String html = IamAdminConsole.renderUsers(users, 1);

        assertThat(html).doesNotContain("<script>");
        assertThat(html).contains("&lt;script&gt;").contains("a&amp;b");
    }
}
