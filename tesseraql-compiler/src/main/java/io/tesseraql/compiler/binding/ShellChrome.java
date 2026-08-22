package io.tesseraql.compiler.binding;

import io.tesseraql.core.account.PreferenceStore;
import io.tesseraql.core.account.ShortcutStore;
import io.tesseraql.core.expr.EvaluationContext;
import io.tesseraql.core.inbox.InboxStore;
import io.tesseraql.pipeline.CookiePath;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.Headers;
import io.tesseraql.pipeline.TesseraqlProperties;
import io.tesseraql.security.Principal;
import io.tesseraql.yaml.menu.MenuSpec;
import io.tesseraql.yaml.menu.MenuSpec.MenuItem;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The shell chrome contributors: the reserved {@code _menu}, {@code _account}, {@code _theme},
 * {@code _inbox}, {@code _shortcuts}, {@code _neutral} and {@code _density} model variables the
 * shared shell renders around every page. One instance serves one render;
 * {@link HtmlResponseRenderer#process} calls the contributors in a fixed order, and each puts its
 * reserved variable into the shared model (or leaves it unset, which the shell reads as "absent").
 * Extracted from the renderer so {@code process()} stays a readable sequence — every block here
 * is behaviorally identical to where it grew inside the renderer.
 */
final class ShellChrome {

    private final Exchange exchange;
    private final EvaluationContext evaluation;
    private final Map<String, Object> model;
    private final String csrfToken;
    /** The request cookie's theme, read by {@link #readThemePreference()} (hostile input). */
    private String cookieTheme;
    /** The signed-in user's stored {@code ui.theme}, read by {@link #readThemePreference()}. */
    private String storedTheme;

    ShellChrome(Exchange exchange, EvaluationContext evaluation, Map<String, Object> model,
            String csrfToken) {
        this.exchange = exchange;
        this.evaluation = evaluation;
        this.model = model;
        this.csrfToken = csrfToken;
    }

    /**
     * Publishes the reserved {@code _system} variable: where this runtime's pages link the
     * system surfaces (operations console, Studio, IAM Admin), bound by the runtime
     * topology-aware — a hosted member links the stack's origin-scope console, the unhosted
     * boot its own mounted copy, and an unmounted surface has no entry so the shell omits the
     * link (docs/stack-shells.md structural decision 2).
     */
    void system() {
        Object nav = exchange.beans().lookup(TesseraqlProperties.SYSTEM_NAV_BEAN);
        if (nav instanceof Map<?, ?> links && !links.isEmpty()) {
            model.put("_system", links);
        }
    }

    /**
     * The browser-session principal: non-null only when the request rides a browser session (the
     * CSRF token stashed on authentication is the marker, the same one {@code _csrf} keys off)
     * AND the request principal is present. The one guard the per-user contributors (theme
     * preference, inbox, shortcuts) share.
     */
    private Principal sessionPrincipal() {
        return csrfToken != null
                && exchange
                        .getProperty(TesseraqlProperties.PRINCIPAL) instanceof Principal principal
                                ? principal
                                : null;
    }

    /**
     * Publishes the app's declarative sidebar menu (config/menu.yml), filtered to the items the
     * caller's roles/permissions may see, as the reserved {@code _menu} variable — the shell
     * renders it in the nav slot in place of the app's hand-authored nav fragment. Hidden items
     * are never emitted (server-side filter). An absent/empty menu leaves {@code _menu} unset, so
     * the shell falls back to the passed nav fragment. Roles/permissions come via the same
     * principal.* the execution context resolves for routes, so no extra dependency is needed
     * here. The menu is read via MenuSpec.live: an edit takes effect on the next render (no
     * reload), and an unchanged file costs a single stat.
     */
    void menu(Path appHome) {
        MenuSpec menu = MenuSpec.live(appHome);
        if (!menu.isEmpty()) {
            List<MenuItem> visible = menu.visibleFor(
                    stringList(evaluation.resolve(List.of("principal", "roles"))),
                    stringList(evaluation.resolve(List.of("principal", "permissions"))));
            if (!visible.isEmpty()) {
                // Expose as plain maps (not records) so the Thymeleaf/OGNL template can read
                // `item.href`/`item.label`/`item.icon`; a null icon is simply omitted.
                List<Map<String, Object>> menuModel = new ArrayList<>();
                for (MenuItem item : visible) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("label", item.label());
                    entry.put("href", item.href());
                    entry.put("icon", item.icon());
                    menuModel.add(entry);
                }
                model.put("_menu", menuModel);
            }
        }
    }

    /**
     * Publishes the account chrome (roadmap Phase 48) as the reserved {@code _account} variable
     * when the request rides a browser session — the CSRF token stashed on authentication is the
     * marker, the same one {@code _csrf} keys off. The shared shell renders the avatar + popover
     * user menu from it; the settings link appears only when the bundled account app is mounted
     * (the runtime binds the marker bean), so the shell never links a 404.
     */
    void account() {
        if (csrfToken != null) {
            Object name = evaluation.resolve(List.of("principal", "displayName"));
            if (name == null || String.valueOf(name).isBlank()) {
                name = evaluation.resolve(List.of("principal", "loginId"));
            }
            if (name == null || String.valueOf(name).isBlank()) {
                name = evaluation.resolve(List.of("principal", "subject"));
            }
            if (name != null && !String.valueOf(name).isBlank()) {
                Map<String, Object> account = new LinkedHashMap<>();
                account.put("name", String.valueOf(name));
                account.put("initials", initials(String.valueOf(name)));
                // A hosted member's account surface is the stack's, served once at the origin
                // scope — its own copy no longer mounts (docs/stack-shells.md structural
                // decision 3) — so the link goes origin-absolute; everywhere else it is this
                // runtime's own mounted copy at its own prefix. Values are emitted verbatim by
                // the shell, so the unhosted form carries its prefix here.
                if (hostedMember()) {
                    account.put("accountHref", "/_tesseraql/account");
                } else if (exchange.beans()
                        .lookup(TesseraqlProperties.ACCOUNT_SURFACE_BEAN) != null) {
                    account.put("accountHref",
                            io.tesseraql.pipeline.BasePath.url(exchange, "/_tesseraql/account"));
                }
                // Sign-out stays this runtime's own route: the member validates the same shared
                // session either way, and a drained member must still be able to sign out.
                account.put("logoutHref",
                        io.tesseraql.pipeline.BasePath.url(exchange, "/_tesseraql/logout"));
                model.put("_account", account);
            }
        }
    }

    /**
     * Publishes the role switcher (docs/application-roles.md structural decision 4)
     * as the reserved {@code _acting} variable: on a hosted member whose caller holds
     * application roles here, the active role plus each other held role as a direct link that
     * swaps the {@code /_as/<role>} activation segment in place — the ops-shell
     * member-switcher shape, carried by the address and nothing else. The swapped principal
     * keeps its full grant attribution, which is exactly what lists the other capacities.
     */
    void acting() {
        Principal principal = sessionPrincipal();
        if (principal == null || !hostedMember()) {
            return;
        }
        String member = String
                .valueOf(exchange.beans().lookup(TesseraqlProperties.STACK_MEMBER_BEAN));
        List<io.tesseraql.security.Principal.RoleGrant> held = io.tesseraql.security.Activation
                .grantsFor(principal, member);
        if (held.isEmpty()) {
            return;
        }
        String active = io.tesseraql.security.Activation.actingRole(principal);
        String base = io.tesseraql.pipeline.BasePath.of(exchange.beans());
        String uri = exchange.getMessage().getHeader(Headers.HTTP_URI, String.class);
        String path = uri == null
                ? "/"
                : uri.indexOf('?') < 0
                        ? uri
                        : uri.substring(0, uri.indexOf('?'));
        String within = path.startsWith(base) ? path.substring(base.length()) : path;
        String query = exchange.getMessage().getHeader(Headers.HTTP_QUERY, String.class);
        String suffix = query == null || query.isBlank() ? "" : "?" + query;
        List<Map<String, Object>> options = new ArrayList<>();
        for (io.tesseraql.security.Principal.RoleGrant grant : held) {
            if (grant.role().equals(active)) {
                continue;
            }
            Map<String, Object> option = new LinkedHashMap<>();
            option.put("role", grant.role());
            option.put("href", base + "/_as/"
                    + io.tesseraql.pipeline.BasePath.encodeSegment(grant.role()) + within + suffix);
            options.add(option);
        }
        Map<String, Object> acting = new LinkedHashMap<>();
        acting.put("active", active);
        acting.put("options", options);
        model.put("_acting", acting);
    }

    /**
     * Reads the page theme's two inputs (roadmap Phase 48): the request's theme cookie and the
     * signed-in user's stored {@code ui.theme}. Values are an enum lookup — a hostile cookie
     * value reads as absent, and nothing here is echoed as markup. {@link #themeAndUiDefaults()}
     * publishes the resolved choice later in the sequence and re-syncs the cookie when the
     * stored choice differs, so pre-login pages (the login screen) render in the chosen theme
     * too.
     */
    void readThemePreference() {
        cookieTheme = validTheme(cookieValue(
                exchange.getMessage().getHeader("Cookie", String.class), "tesseraql_theme"));
        storedTheme = null;
        Principal principal = sessionPrincipal();
        if (principal != null) {
            PreferenceStore preferences = exchange.beans().lookup(
                    TesseraqlProperties.PREFERENCE_STORE_BEAN,
                    PreferenceStore.class);
            if (preferences != null) {
                storedTheme = validTheme(preferences
                        .preferences(principal.tenantId(), principal.subject())
                        .get("ui.theme"));
            }
        }
    }

    /**
     * Publishes the inbox badge (roadmap Phase 49) as the reserved {@code _inbox} variable when a
     * browser session rides the request AND an inbox channel is configured (the runtime binds the
     * store then). The count is a cached read - a map lookup per page.
     */
    void inbox() {
        Principal principal = sessionPrincipal();
        if (principal != null) {
            InboxStore inbox = exchange.beans().lookup(TesseraqlProperties.INBOX_STORE_BEAN,
                    InboxStore.class);
            if (inbox != null) {
                int unread = inbox.unreadCount(principal.tenantId(), principal.subject());
                // The badge is pre-rendered (InboxBadge, the single markup source shared
                // with the /_tesseraql/events inbox:badge payload), so the shell's initial
                // render and a pushed update are byte-identical.
                model.put("_inbox", Map.of(
                        "unread", unread,
                        "badge", InboxBadge.html(unread),
                        // The inbox page rides the account surface: origin-absolute on a
                        // hosted member, this runtime's own copy otherwise (see account()).
                        "href", hostedMember()
                                ? "/_tesseraql/inbox"
                                : io.tesseraql.pipeline.BasePath.url(exchange,
                                        "/_tesseraql/inbox")));
            }
        }
    }

    /**
     * Publishes pins (roadmap Phase 51) as the reserved {@code _shortcuts} variable when a
     * browser session rides the request and the account surface is on: the sidebar's Pinned
     * group and the header's Pin/Unpin toggle render from it. The list read is TTL-cached (the
     * inbox badge's trade-off). Also records the recently-viewed entry — a store WRITE inside
     * rendering (see below), kept exactly where it grew in the render sequence.
     */
    void shortcuts(ViewBinding viewBinding) {
        Principal principal = sessionPrincipal();
        if (principal != null) {
            ShortcutStore shortcutStore = exchange.beans().lookup(
                    TesseraqlProperties.SHORTCUT_STORE_BEAN,
                    ShortcutStore.class);
            if (shortcutStore != null) {
                String currentHref = exchange.getMessage().getHeader(Headers.HTTP_URI,
                        String.class);
                List<Map<String, Object>> pins = new ArrayList<>();
                boolean pinnedCurrent = false;
                for (ShortcutStore.Shortcut pin : shortcutStore
                        .list(principal.tenantId(), principal.subject(),
                                ShortcutStore.PIN, 20)) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("href", pin.href());
                    row.put("label", pin.label());
                    pins.add(row);
                    pinnedCurrent = pinnedCurrent || pin.href().equals(currentHref);
                }
                Map<String, Object> shortcuts = new LinkedHashMap<>();
                shortcuts.put("pins", pins);
                shortcuts.put("current", currentHref == null ? "/" : currentHref);
                shortcuts.put("pinnedCurrent", pinnedCurrent);
                // The pin toggle posts to the account surface's route: the stack's at the
                // origin on a hosted member, this runtime's own otherwise (see account()).
                // One store behind both, so a pin toggled at the origin shows here.
                shortcuts.put("toggleHref", hostedMember()
                        ? "/_tesseraql/account/pins/toggle"
                        : io.tesseraql.pipeline.BasePath.url(exchange,
                                "/_tesseraql/account/pins/toggle"));
                model.put("_shortcuts", shortcuts);
                // Recently viewed records (roadmap Phase 51 slice 2): a detail view render
                // IS the framework's definition of "viewing a record". Deduped and bumped
                // by the store wrapper; labelled by the view's own title. NOTE: this is a
                // ShortcutStore WRITE inside rendering — deliberately left in place here.
                if (viewBinding != null && "detail".equals(viewBinding.spec().view())
                        && currentHref != null) {
                    Object viewModel = model.get("v");
                    String label = viewModel instanceof Map<?, ?> v
                            && v.get("title") != null
                                    ? String.valueOf(v.get("title"))
                                    : currentHref;
                    if (label.length() > 200) {
                        label = label.substring(0, 200);
                    }
                    shortcutStore.put(principal.tenantId(), principal.subject(),
                            ShortcutStore.RECENT, currentHref, label, 20);
                }
            }
        }
    }

    /**
     * Publishes the resolved page theme as the reserved {@code _theme} variable: the signed-in
     * user's stored {@code ui.theme}, else the request's theme cookie, else the operator default
     * the runtime binds (both read by {@link #readThemePreference()}). When the stored choice
     * differs from the cookie the response re-syncs the cookie. Also publishes the app's UI
     * defaults (docs/hypermedia-ui.md "UI defaults"): the neutral color ramp and control density
     * every shell renders, operator-overridable via tesseraql.ui.* and defaulting to the
     * framework's slate + compact — TesseraQL apps are data-dense work surfaces, and slate is
     * the brand's neutral. Values are an enum lookup (the runtime binds only validated
     * overrides); the kit defaults ("neutral" ramp / "comfortable" density) publish nothing, so
     * the shell emits no attribute and links no extra sheet.
     */
    void themeAndUiDefaults() {
        String theme = storedTheme != null
                ? storedTheme
                : cookieTheme != null
                        ? cookieTheme
                        : validTheme(exchange.beans().lookup(
                                TesseraqlProperties.UI_THEME_BEAN, String.class));
        if (theme != null) {
            model.put("_theme", theme);
        }
        String neutral = validNeutral(exchange.beans().lookup(
                TesseraqlProperties.UI_NEUTRAL_BEAN, String.class));
        if (neutral == null) {
            neutral = "slate";
        }
        if (!"neutral".equals(neutral)) {
            model.put("_neutral", neutral);
        }
        String density = validDensity(exchange.beans().lookup(
                TesseraqlProperties.UI_DENSITY_BEAN, String.class));
        if (density == null) {
            density = "compact";
        }
        if (!"comfortable".equals(density)) {
            model.put("_density", density);
        }
        if (storedTheme != null && !storedTheme.equals(cookieTheme)) {
            // The same Path as the session cookie: the preference belongs to whoever is signed
            // in, and follows the sign-in across the stack or stays with the one application
            // (docs/base-path.md decision 4).
            exchange.response().header("Set-Cookie", "tesseraql_theme=" + storedTheme
                    + "; Path=" + CookiePath.of(exchange)
                    + "; Max-Age=31536000; SameSite=Lax");
        }
    }

    /**
     * Whether this runtime serves a hosted stack member — the topology signal the runtime binds
     * (docs/stack-shells.md structural decision 3). The account-family surfaces then live at the
     * stack's origin scope, and their links leave this member's prefix behind.
     */
    private boolean hostedMember() {
        return exchange.beans().lookup(TesseraqlProperties.STACK_MEMBER_BEAN) != null;
    }

    /** Coerces a resolved {@code principal.roles}/{@code permissions} value to a string list. */
    private static List<String> stringList(Object value) {
        return value instanceof List<?> list
                ? list.stream().map(String::valueOf).toList()
                : List.of();
    }

    /** The theme enum: anything but the known values reads as absent (cookies are hostile). */
    private static String validTheme(String value) {
        return "light".equals(value) || "dark".equals(value) ? value : null;
    }

    /** The kit's neutral ramps (hc.tokens.neutral-*.css); anything else reads as absent. */
    private static String validNeutral(String value) {
        return value != null && java.util.Set.of("neutral", "slate", "zinc", "stone")
                .contains(value) ? value : null;
    }

    /** The kit's density sheets (comfortable = the base tokens); anything else is absent. */
    private static String validDensity(String value) {
        return value != null && java.util.Set.of("comfortable", "compact", "dense")
                .contains(value) ? value : null;
    }

    /** A minimal request-cookie read (the session store's parser is package-private). */
    private static String cookieValue(String cookieHeader, String name) {
        if (cookieHeader == null) {
            return null;
        }
        for (String part : cookieHeader.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).trim().equals(name)) {
                return part.substring(eq + 1).trim();
            }
        }
        return null;
    }

    /** The avatar fallback: the first letters of up to two words (one glyph for CJK names). */
    private static String initials(String name) {
        StringBuilder initials = new StringBuilder();
        for (String word : name.trim().split("\\s+")) {
            if (word.isEmpty()) {
                continue;
            }
            initials.appendCodePoint(Character.toUpperCase(word.codePointAt(0)));
            if (initials.codePointCount(0, initials.length()) == 2) {
                break;
            }
        }
        return initials.toString();
    }
}
