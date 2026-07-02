package io.tesseraql.yaml.menu;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.SimpleYamlParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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

    private static final TqlErrorCode WRITE_ERROR = new TqlErrorCode(TqlDomain.YAML, 1010);

    /** Writes clean block YAML with no leading document marker, matching hand-authored menus. */
    private static final ObjectMapper YAML = new ObjectMapper(
            new YAMLFactory().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER));

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

    /**
     * Serializes menu items to the {@code config/menu.yml} document text — the inverse of
     * {@link #load(Path)}. {@code label} and {@code href} are always written; {@code icon},
     * {@code roles}, and {@code permissions} only when set, so an item's on-disk form stays as
     * terse as the hand-authored one. An empty list writes {@code menu: []}, which loads back as an
     * empty (nav-fallback) menu.
     */
    public static String toYaml(List<MenuItem> items) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (MenuItem item : items) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("label", item.label());
            entry.put("href", item.href());
            if (item.icon() != null && !item.icon().isBlank()) {
                entry.put("icon", item.icon());
            }
            if (!item.roles().isEmpty()) {
                entry.put("roles", item.roles());
            }
            if (!item.permissions().isEmpty()) {
                entry.put("permissions", item.permissions());
            }
            out.add(entry);
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("menu", out);
        try {
            return YAML.writeValueAsString(doc);
        } catch (JsonProcessingException ex) {
            throw new TqlException(WRITE_ERROR, "Failed to serialize menu.yml");
        }
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
