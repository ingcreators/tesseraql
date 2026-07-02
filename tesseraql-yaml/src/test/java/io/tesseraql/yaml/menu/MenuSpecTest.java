package io.tesseraql.yaml.menu;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.yaml.menu.MenuSpec.MenuItem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MenuSpecTest {

    @Test
    void anAppWithoutTheFileHasAnEmptySpec(@TempDir Path appHome) {
        MenuSpec menu = MenuSpec.load(appHome);

        assertThat(menu.isEmpty()).isTrue();
        assertThat(menu.items()).isEmpty();
    }

    @Test
    void loadsLabelHrefIconRolesAndPermissions(@TempDir Path appHome) throws Exception {
        writeMenu(appHome, """
                menu:
                  - { label: Home,  href: /,      icon: home }
                  - { label: Users, href: /users, roles: [ADMIN, STAFF] }
                  - { label: Admin, href: /admin, permissions: [iam.admin] }
                """);

        MenuSpec menu = MenuSpec.load(appHome);

        assertThat(menu.isEmpty()).isFalse();
        assertThat(menu.items()).hasSize(3);
        MenuItem home = menu.items().get(0);
        assertThat(home.label()).isEqualTo("Home");
        assertThat(home.href()).isEqualTo("/");
        assertThat(home.icon()).isEqualTo("home");
        assertThat(home.roles()).isEmpty();
        assertThat(home.permissions()).isEmpty();
        assertThat(menu.items().get(1).roles()).containsExactly("ADMIN", "STAFF");
        assertThat(menu.items().get(1).icon()).isNull();
        assertThat(menu.items().get(2).permissions()).containsExactly("iam.admin");
    }

    @Test
    void visibleForShowsPublicRoleMatchedAndPermissionMatchedItems(@TempDir Path appHome)
            throws Exception {
        writeMenu(appHome, """
                menu:
                  - { label: Home,  href: /,      icon: home }
                  - { label: Users, href: /users, roles: [ADMIN, STAFF] }
                  - { label: Admin, href: /admin, permissions: [iam.admin] }
                """);
        MenuSpec menu = MenuSpec.load(appHome);

        // Anonymous caller: only the public item.
        assertThat(labels(menu.visibleFor(List.of(), List.of()))).containsExactly("Home");
        // A matching role reveals the role-gated item (any one listed role is enough).
        assertThat(labels(menu.visibleFor(List.of("STAFF"), List.of())))
                .containsExactly("Home", "Users");
        // A matching permission reveals the permission-gated item.
        assertThat(labels(menu.visibleFor(List.of(), List.of("iam.admin"))))
                .containsExactly("Home", "Admin");
        // A non-matching role/permission sees only the public item (server-side filter).
        assertThat(labels(menu.visibleFor(List.of("OTHER"), List.of("nope"))))
                .containsExactly("Home");
        // Null role/permission lists are tolerated as the anonymous case.
        assertThat(labels(menu.visibleFor(null, null))).containsExactly("Home");
    }

    @Test
    void aNonListMenuKeyYieldsAnEmptySpec(@TempDir Path appHome) throws Exception {
        writeMenu(appHome, "menu: not-a-list\n");

        assertThat(MenuSpec.load(appHome).isEmpty()).isTrue();
    }

    private static List<String> labels(List<MenuItem> items) {
        return items.stream().map(MenuItem::label).toList();
    }

    private static void writeMenu(Path appHome, String yaml) throws Exception {
        Path config = Files.createDirectories(appHome.resolve("config"));
        Files.writeString(config.resolve("menu.yml"), yaml);
    }
}
