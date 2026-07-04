package io.tesseraql.compiler.binding;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.expr.EvaluationContext;
import io.tesseraql.core.expr.Expr;
import io.tesseraql.core.expr.ExpressionParser;
import io.tesseraql.yaml.menu.MenuSpec;
import io.tesseraql.yaml.menu.MenuSpec.MenuItem;
import io.tesseraql.yaml.model.ResponseSpec.HtmlResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

/**
 * Renders an HTML page or fragment response from a Thymeleaf template and model (design ch. 6.4,
 * 12). The template path resolves like {@code sql.file}: first relative to the route's own
 * directory (the colocated yml + sql + html unit), falling back to the app's shared
 * {@code templates/} directory for cross-route fragments and layouts. Existence is verified at
 * build time (fail-fast); at request time the model expressions are resolved against the execution
 * context, the template is rendered, and configured response headers (such as {@code HX-Trigger})
 * are emitted, serializing nested values to JSON.
 */
public final class HtmlResponseRenderer implements Processor {

    private static final TqlErrorCode RENDER_ERROR = new TqlErrorCode(TqlDomain.TPL, 2001);

    /** A {@code {expression}} placeholder in a header value, resolved like the redirect location. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^}]+)}");

    private final HtmlResponse response;
    private final Path appHome;
    private final String templateName;
    private final String defaultLocaleTag;
    private final ViewBinding viewBinding;
    private final Map<String, Expr> headerGuards;
    private final Map<String, Expr> compiledModel = new LinkedHashMap<>();
    private final java.util.List<JsonResponseRenderer.CompiledStatus> statusWhen;
    private final ObjectMapper mapper = new ObjectMapper();

    public HtmlResponseRenderer(HtmlResponse response, Path appHome, Path routeDir) {
        this(response, appHome, routeDir, "en");
    }

    public HtmlResponseRenderer(HtmlResponse response, Path appHome, Path routeDir,
            String defaultLocaleTag) {
        this(response, appHome, routeDir, defaultLocaleTag, null);
    }

    /**
     * @param viewBinding the compiled {@code response.html.view} reference (roadmap Phase 39), or
     *                    null when the route renders a hand-written {@code template:}
     */
    public HtmlResponseRenderer(HtmlResponse response, Path appHome, Path routeDir,
            String defaultLocaleTag, ViewBinding viewBinding) {
        this.response = response;
        this.appHome = appHome.toAbsolutePath().normalize();
        this.viewBinding = viewBinding;
        if (viewBinding != null && response.template() != null) {
            throw new TqlException(new TqlErrorCode(TqlDomain.VIEW, 3302),
                    "response.html declares both template: and view: — they are mutually exclusive");
        }
        this.templateName = viewBinding != null
                ? viewBinding.entryTemplate()
                : resolveTemplate(this.appHome, routeDir, response.template());
        this.defaultLocaleTag = defaultLocaleTag;
        // Model values compile in the core expression language (roadmap Phase 41) — a plain
        // dotted path is unchanged, a computed leaf comes for free, and an unparsable legacy
        // value falls back to dotted-path resolution.
        response.model().forEach((key, expr) -> {
            String source = String.valueOf(expr);
            Expr compiled;
            try {
                compiled = ExpressionParser.parse(source);
            } catch (RuntimeException ex) {
                compiled = new Expr.Path(Arrays.asList(source.split("\\.")));
            }
            compiledModel.put(key, compiled);
        });
        this.statusWhen = response.statusWhen().stream()
                .map(arm -> new JsonResponseRenderer.CompiledStatus(
                        ExpressionParser.parse(arm.when()), arm.status()))
                .toList();
        // Pre-compile each header's optional guard expression so a syntax error fails the build.
        this.headerGuards = new LinkedHashMap<>();
        response.headersWhen().forEach((name, when) -> {
            if (when != null && !when.isBlank()) {
                headerGuards.put(name, ExpressionParser.parse(when));
            }
        });
    }

    /**
     * Resolves a route's template: colocated next to the route first, then the shared
     * {@code templates/} root; confined to the app home. Returns the app-home-relative name used
     * with the app's template engine.
     */
    static String resolveTemplate(Path appHome, Path routeDir, String template) {
        Path colocated = routeDir.toAbsolutePath().normalize().resolve(template).normalize();
        Path file = Files.isRegularFile(colocated)
                ? colocated
                : appHome.resolve("templates").resolve(template).normalize();
        if (!file.startsWith(appHome)) {
            throw new TqlException(RENDER_ERROR, "Template escapes app home: " + template);
        }
        if (!Files.isRegularFile(file)) {
            throw new TqlException(RENDER_ERROR, "Template not found: " + template);
        }
        return appHome.relativize(file).toString().replace('\\', '/');
    }

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) throws Exception {
        Map<String, Object> context = exchange.getProperty(
                TesseraqlProperties.CONTEXT, Map.of(), Map.class);
        EvaluationContext evaluation = new EvaluationContext(context);

        // The negotiated request locale (roadmap Phase 22) drives #{key} lookups, #locale, and
        // the view model's resolved labels.
        String tag = exchange.getProperty(TesseraqlProperties.LOCALE, defaultLocaleTag,
                String.class);

        Map<String, Object> model = new LinkedHashMap<>();
        if (viewBinding != null) {
            // A declarative view (roadmap Phase 39): the reserved `v` model is the whole contract
            // between the route and the tql/view/* pattern fragments. The request path anchors
            // the list pattern's self-rendering search/sort links.
            String uri = exchange.getMessage().getHeader(Exchange.HTTP_URI, String.class);
            String pagePath = uri == null
                    ? ""
                    : uri.indexOf('?') < 0 ? uri : uri.substring(0, uri.indexOf('?'));
            model.put("v", viewBinding.model(context, java.util.Locale.forLanguageTag(tag),
                    pagePath));
        } else {
            compiledModel.forEach((key, expr) -> model.put(key, expr.eval(evaluation)));
        }

        // Publish the browser session's CSRF token (stashed on authentication) as the reserved
        // model variable `_csrf`, so the shell can emit <meta name="csrf-token"> for the
        // Hypermedia Components installCsrfHeader convention and forms can carry a hidden field.
        String csrfToken = exchange.getProperty(TesseraqlProperties.CSRF_TOKEN, String.class);
        if (csrfToken != null) {
            model.put("_csrf", csrfToken);
        }

        // Publish the app's declarative sidebar menu (config/menu.yml), filtered to the items the
        // caller's roles/permissions may see, as the reserved `_menu` variable — the shell renders it
        // in the nav slot in place of the app's hand-authored nav fragment. Hidden items are never
        // emitted (server-side filter). An absent/empty menu leaves `_menu` unset, so the shell falls
        // back to the passed nav fragment. Roles/permissions come via the same principal.* the
        // execution context resolves for routes, so no extra dependency is needed here. The menu is
        // read via MenuSpec.live: an edit takes effect on the next render (no reload), and an
        // unchanged file costs a single stat.
        MenuSpec menu = MenuSpec.live(appHome);
        if (!menu.isEmpty()) {
            List<MenuItem> visible = menu.visibleFor(
                    stringList(evaluation.resolve(List.of("principal", "roles"))),
                    stringList(evaluation.resolve(List.of("principal", "permissions"))));
            if (!visible.isEmpty()) {
                // Expose as plain maps (not records) so the Thymeleaf/OGNL template can read
                // `item.href`/`item.label`/`item.icon`; a null icon is simply omitted.
                List<Map<String, Object>> menuModel = new java.util.ArrayList<>();
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

        // Publish the account chrome (roadmap Phase 48) as the reserved `_account` variable when
        // the request rides a browser session — the CSRF token stashed on authentication is the
        // marker, the same one `_csrf` keys off. The shared shell renders the avatar + popover
        // user menu from it; the settings link appears only when the bundled account app is
        // mounted (the runtime binds the marker bean), so the shell never links a 404.
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
                if (exchange.getContext().getRegistry()
                        .lookupByName(TesseraqlProperties.ACCOUNT_SURFACE_BEAN) != null) {
                    account.put("accountHref", "/_tesseraql/account");
                }
                account.put("logoutHref", "/_tesseraql/logout");
                model.put("_account", account);
            }
        }

        // Publish the page theme (roadmap Phase 48) as the reserved `_theme` variable: the
        // signed-in user's stored `ui.theme`, else the request's theme cookie, else the
        // operator default the runtime binds. Values are an enum lookup — a hostile cookie
        // value reads as absent, and nothing here is echoed as markup. When the stored choice
        // differs from the cookie the response re-syncs the cookie, so pre-login pages (the
        // login screen) render in the chosen theme too.
        String cookieTheme = validTheme(cookieValue(
                exchange.getMessage().getHeader("Cookie", String.class), "tesseraql_theme"));
        String storedTheme = null;
        if (csrfToken != null
                && exchange.getProperty(
                        TesseraqlProperties.PRINCIPAL) instanceof io.tesseraql.security.Principal principal) {
            io.tesseraql.core.account.PreferenceStore preferences = exchange.getContext()
                    .getRegistry().lookupByNameAndType(
                            TesseraqlProperties.PREFERENCE_STORE_BEAN,
                            io.tesseraql.core.account.PreferenceStore.class);
            if (preferences != null) {
                storedTheme = validTheme(preferences
                        .preferences(principal.tenantId(), principal.subject())
                        .get("ui.theme"));
            }
        }
        // Publish the inbox badge (roadmap Phase 49) as the reserved `_inbox` variable when a
        // browser session rides the request AND an inbox channel is configured (the runtime
        // binds the store then). The count is a cached read - a map lookup per page.
        if (csrfToken != null
                && exchange.getProperty(
                        TesseraqlProperties.PRINCIPAL) instanceof io.tesseraql.security.Principal inboxPrincipal) {
            io.tesseraql.core.inbox.InboxStore inbox = exchange.getContext().getRegistry()
                    .lookupByNameAndType(TesseraqlProperties.INBOX_STORE_BEAN,
                            io.tesseraql.core.inbox.InboxStore.class);
            if (inbox != null) {
                model.put("_inbox", Map.of(
                        "unread", inbox.unreadCount(inboxPrincipal.tenantId(),
                                inboxPrincipal.subject()),
                        "href", "/_tesseraql/inbox"));
            }
        }
        // Publish pins (roadmap Phase 51) as the reserved `_shortcuts` variable when a
        // browser session rides the request and the account surface is on: the sidebar's
        // Pinned group and the header's Pin/Unpin toggle render from it. The list read is
        // TTL-cached (the inbox badge's trade-off).
        if (csrfToken != null
                && exchange.getProperty(
                        TesseraqlProperties.PRINCIPAL) instanceof io.tesseraql.security.Principal pinPrincipal) {
            io.tesseraql.core.account.ShortcutStore shortcutStore = exchange.getContext()
                    .getRegistry().lookupByNameAndType(TesseraqlProperties.SHORTCUT_STORE_BEAN,
                            io.tesseraql.core.account.ShortcutStore.class);
            if (shortcutStore != null) {
                String currentHref = exchange.getMessage().getHeader(Exchange.HTTP_URI,
                        String.class);
                java.util.List<Map<String, Object>> pins = new java.util.ArrayList<>();
                boolean pinnedCurrent = false;
                for (io.tesseraql.core.account.ShortcutStore.Shortcut pin : shortcutStore
                        .list(pinPrincipal.tenantId(), pinPrincipal.subject(),
                                io.tesseraql.core.account.ShortcutStore.PIN, 20)) {
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
                model.put("_shortcuts", shortcuts);
                // Recently viewed records (roadmap Phase 51 slice 2): a detail view render
                // IS the framework's definition of "viewing a record". Deduped and bumped
                // by the store wrapper; labelled by the view's own title.
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
                    shortcutStore.put(pinPrincipal.tenantId(), pinPrincipal.subject(),
                            io.tesseraql.core.account.ShortcutStore.RECENT, currentHref,
                            label, 20);
                }
            }
        }
        String theme = storedTheme != null
                ? storedTheme
                : cookieTheme != null
                        ? cookieTheme
                        : validTheme(exchange.getContext().getRegistry().lookupByNameAndType(
                                TesseraqlProperties.UI_THEME_BEAN, String.class));
        if (theme != null) {
            model.put("_theme", theme);
        }
        if (storedTheme != null && !storedTheme.equals(cookieTheme)) {
            exchange.getMessage().setHeader("Set-Cookie", "tesseraql_theme=" + storedTheme
                    + "; Path=/; Max-Age=31536000; SameSite=Lax");
        }

        String html = Templates.render(appHome, templateName, model,
                java.util.Locale.forLanguageTag(tag));

        int status = response.effectiveStatus();
        for (JsonResponseRenderer.CompiledStatus arm : statusWhen) {
            if (arm.when().evalBoolean(evaluation)) {
                status = arm.status();
                break;
            }
        }
        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, status);
        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "text/html; charset=utf-8");
        applyHeaders(exchange, evaluation);
        exchange.getMessage().setBody(html);
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

    private void applyHeaders(Exchange exchange, EvaluationContext evaluation) {
        response.headers().forEach((name, value) -> {
            // A header with a guard (headersWhen) is emitted only when its condition is truthy, so a
            // single fragment can fire e.g. HX-Trigger on success but not on a handled error.
            Expr guard = headerGuards.get(name);
            if (guard != null && !guard.evalBoolean(evaluation)) {
                return;
            }
            try {
                // Resolve {expression} placeholders against the execution context (recursively for a
                // nested map/list), so a header like HX-Trigger can carry per-request data; a value
                // with no placeholder is unchanged. Nested map/list values then serialize to JSON.
                Object resolved = interpolate(value, evaluation);
                String headerValue = resolved instanceof Map || resolved instanceof List
                        ? mapper.writeValueAsString(resolved)
                        : String.valueOf(resolved);
                exchange.getMessage().setHeader(name, headerValue);
            } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
                throw new TqlException(RENDER_ERROR, "Failed to serialize header " + name);
            }
        });
    }

    /** Resolves {@code {expression}} placeholders in a header value (recursively into maps/lists). */
    @SuppressWarnings("unchecked")
    static Object interpolate(Object value, EvaluationContext evaluation) {
        if (value instanceof String string) {
            return interpolateString(string, evaluation);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            ((Map<String, Object>) map).forEach((k, v) -> out.put(k, interpolate(v, evaluation)));
            return out;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(element -> interpolate(element, evaluation)).toList();
        }
        return value;
    }

    static String interpolateString(String template, EvaluationContext evaluation) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            Object resolved = evaluation.resolve(Arrays.asList(matcher.group(1).split("\\.")));
            matcher.appendReplacement(out,
                    Matcher.quoteReplacement(resolved == null ? "" : String.valueOf(resolved)));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
