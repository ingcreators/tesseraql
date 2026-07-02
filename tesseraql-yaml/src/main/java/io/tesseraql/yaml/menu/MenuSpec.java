package io.tesseraql.yaml.menu;

import io.tesseraql.yaml.SimpleYamlParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The application's declarative sidebar menu ({@code config/menu.yml}) with per-item role/permission
 * visibility. Opt-in: an app without the file has an empty spec and keeps its hand-authored
 * {@code templates/nav.html}. The menu is rendered server-side and role-filtered (hidden items are
 * never emitted) by the HTML response renderer and the app shell.
 *
 * <p>File shape:
 * <pre>
 * menu:
 *   - { label: Home,  href: /,            icon: home }
 *   - { label: Users, href: /users,       roles: [ADMIN, STAFF] }
 *   - { label: Admin, href: /admin/users, permissions: [iam.admin] }
 * </pre>
 * An item with neither {@code roles} nor {@code permissions} is public; otherwise it is shown when
 * the caller holds at least one listed role or permission. {@code icon} is an optional sprite id.
 */
public final class MenuSpec {

    /** One sidebar entry. Empty {@code roles} and {@code permissions} means public. */
    public record MenuItem(String label, String href, String icon, List<String> roles,
            List<String> permissions) {
        public MenuItem {
            roles = roles == null ? List.of() : List.copyOf(roles);
            permissions = permissions == null ? List.of() : List.copyOf(permissions);
        }
    }

    private static final MenuSpec EMPTY = new MenuSpec(List.of());

    private final List<MenuItem> items;

    private MenuSpec(List<MenuItem> items) {
        this.items = List.copyOf(items);
    }

    /** The empty menu (an app with no {@code config/menu.yml}). */
    public static MenuSpec empty() {
        return EMPTY;
    }

    /** Loads {@code config/menu.yml} under {@code appHome}; an empty spec when the file is absent. */
    public static MenuSpec load(Path appHome) {
        Path file = appHome.resolve("config/menu.yml").normalize();
        if (!Files.isRegularFile(file)) {
            return EMPTY;
        }
        Object raw = new SimpleYamlParser().parseTree(file).get("menu");
        if (!(raw instanceof List<?> list)) {
            return EMPTY;
        }
        List<MenuItem> items = new ArrayList<>();
        for (Object element : list) {
            if (element instanceof Map<?, ?> map) {
                items.add(new MenuItem(str(map.get("label")), str(map.get("href")),
                        str(map.get("icon")), strList(map.get("roles")),
                        strList(map.get("permissions"))));
            }
        }
        return new MenuSpec(items);
    }

    public List<MenuItem> items() {
        return items;
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    /**
     * The items visible to a caller holding {@code roles}/{@code permissions}: every public item
     * (no roles and no permissions) plus any item listing one of the caller's roles or permissions.
     */
    public List<MenuItem> visibleFor(List<String> roles, List<String> permissions) {
        List<String> callerRoles = roles == null ? List.of() : roles;
        List<String> callerPerms = permissions == null ? List.of() : permissions;
        List<MenuItem> visible = new ArrayList<>();
        for (MenuItem item : items) {
            boolean isPublic = item.roles().isEmpty() && item.permissions().isEmpty();
            if (isPublic
                    || item.roles().stream().anyMatch(callerRoles::contains)
                    || item.permissions().stream().anyMatch(callerPerms::contains)) {
                visible.add(item);
            }
        }
        return visible;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static List<String> strList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }
}
