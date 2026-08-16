package io.tesseraql.compiler.binding;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.expr.EvaluationContext;
import io.tesseraql.core.expr.Expr;
import io.tesseraql.core.expr.ExpressionParser;
import io.tesseraql.yaml.model.ResponseSpec.HtmlResponse;
import io.tesseraql.yaml.template.Templates;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

/**
 * Renders an HTML page or fragment response from a Thymeleaf template and model (design ch. 6.4,
 * 12). The template path resolves like a source's {@code file:}: first relative to the route's own
 * directory (the colocated yml + sql + html unit), falling back to the app's shared
 * {@code templates/} directory for cross-route fragments and layouts. Existence is verified at
 * build time (fail-fast); at request time the model expressions are resolved against the execution
 * context, the template is rendered, and configured response headers (such as {@code HX-Trigger})
 * are emitted, serializing nested values to JSON. The shell's reserved model variables are
 * contributed by {@link ShellChrome}; template resolution and placeholder interpolation live in
 * {@link TemplateResolution} and {@link Interpolation}.
 */
public final class HtmlResponseRenderer implements Processor {

    // TQL-TPL-2001 is declared once, on TemplateResolution (the guard refuses a second site).
    private static final TqlErrorCode RENDER_ERROR = TemplateResolution.RENDER_ERROR;

    /** TQL-VIEW-3317: response.html.shell must be auto, always, or never. */
    static final TqlErrorCode INVALID_SHELL = new TqlErrorCode(TqlDomain.VIEW, 3317);

    /** TQL-VIEW-3319: model: must not declare the reserved view-model names (v, views). */
    static final TqlErrorCode RESERVED_MODEL = new TqlErrorCode(TqlDomain.VIEW, 3319);

    /** The region shell negotiation serves to htmx requests (docs/view-composition.md 2a). */
    private static final String PAGE_CONTENT_SELECTOR = "#page-content";

    private final HtmlResponse response;
    private final Path appHome;
    private final String templateName;
    private final String defaultLocaleTag;
    private final ViewBinding viewBinding;
    private final Map<String, ViewBinding> boundViews;
    private final ResponseHeaders headers;
    private final Map<String, Expr> compiledModel = new LinkedHashMap<>();
    private final java.util.List<JsonResponseRenderer.CompiledStatus> statusWhen;
    private final ObjectMapper mapper = new ObjectMapper();
    /**
     * The application's base path, published to every template as {@code base}
     * (docs/base-path.md decision 2). Empty unless the deployment asked for a prefix, which
     * makes {@code |${base}/assets/x|} render as {@code /assets/x} — byte-identical to what
     * shipped before the prefix existed.
     */
    private String basePath = "";

    /**
     * Sets the application's base path, returning {@code this} so the compiler can apply it at
     * construction. Separate from the constructors because five of them chain, and a prefix is
     * an application-wide fact rather than a per-route one.
     */
    public HtmlResponseRenderer basePath(String basePath) {
        this.basePath = basePath == null ? "" : basePath;
        return this;
    }

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
        this(response, appHome, routeDir, defaultLocaleTag, viewBinding, Map.of());
    }

    /**
     * @param boundViews the compiled {@code response.html.views} bindings by id
     *                   (docs/view-composition.md wave 2c) — declarative parts a
     *                   {@code template:} route publishes as {@code views['<id>']}
     */
    public HtmlResponseRenderer(HtmlResponse response, Path appHome, Path routeDir,
            String defaultLocaleTag, ViewBinding viewBinding,
            Map<String, ViewBinding> boundViews) {
        this.response = response;
        this.appHome = appHome.toAbsolutePath().normalize();
        this.viewBinding = viewBinding;
        this.boundViews = boundViews;
        if (viewBinding != null && response.template() != null) {
            throw new TqlException(new TqlErrorCode(TqlDomain.VIEW, 3302),
                    "response.html declares both template: and view: — they are mutually exclusive");
        }
        if (viewBinding != null && !boundViews.isEmpty()) {
            throw new TqlException(new TqlErrorCode(TqlDomain.VIEW, 3302),
                    "response.html.views binds declarative parts to a template: route — a view:"
                            + " route embeds through its own document instead");
        }
        if (response.model().containsKey("v") || response.model().containsKey("views")
                || response.model().containsKey(TesseraqlProperties.CODES)) {
            throw new TqlException(RESERVED_MODEL, "response.html.model must not declare 'v',"
                    + " 'views' or 'codes' — they are the reserved model names");
        }
        this.templateName = viewBinding != null
                ? viewBinding.entryTemplate()
                : TemplateResolution.resolve(this.appHome, routeDir, response.template());
        if (!java.util.Set.of("auto", "always", "never").contains(response.effectiveShell())) {
            throw new TqlException(INVALID_SHELL, "response.html.shell must be 'auto',"
                    + " 'always' or 'never', got: " + response.shell());
        }
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
        this.statusWhen = JsonResponseRenderer.CompiledStatus
                .compileAll(response.statusWhen());
        this.headers = new ResponseHeaders(response.headers(), response.headersWhen());
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
        // The app's code catalogs, so a template resolves the name behind a code with no query
        // and no per-route declaration (docs/lookups.md, decision 8). Reserved like `v`, and put
        // in first so a route model can never shadow it.
        Object codes = context.get(TesseraqlProperties.CODES);
        if (codes != null) {
            model.put(TesseraqlProperties.CODES, codes);
        }
        // Route model: entries always evaluate (docs/view-composition.md wave 2b — they used
        // to be discarded on view: routes); `v` and `views` are reserved names the constructor
        // refuses, so the view models below can never be shadowed.
        compiledModel.forEach((key, expr) -> model.put(key, expr.eval(evaluation)));
        String uri = exchange.getMessage().getHeader(Exchange.HTTP_URI, String.class);
        String pagePath = uri == null
                ? ""
                : uri.indexOf('?') < 0 ? uri : uri.substring(0, uri.indexOf('?'));
        // HTML output masking (docs/view-composition.md wave 3b): the views' explicit domain:
        // references carry classification/mask, applied to the execution context with the
        // exact FieldPolicyApplier the JSON renderer uses — one row can never render masked
        // in JSON and raw in HTML.
        Map<String, io.tesseraql.yaml.model.ResponseSpec.FieldPolicy> readPolicies = new LinkedHashMap<>();
        if (viewBinding != null) {
            readPolicies.putAll(viewBinding.readPolicies());
        }
        boundViews.values().forEach(binding -> readPolicies.putAll(binding.readPolicies()));
        Map<String, Object> viewContext = context;
        io.tesseraql.security.policy.PolicyEngine policyEngine = exchange.getContext()
                .getRegistry().lookupByNameAndType(TesseraqlProperties.POLICY_ENGINE_BEAN,
                        io.tesseraql.security.policy.PolicyEngine.class);
        io.tesseraql.security.Principal requestPrincipal = exchange.getProperty(
                TesseraqlProperties.PRINCIPAL, io.tesseraql.security.Principal.class);
        if (!readPolicies.isEmpty()) {
            viewContext = (Map<String, Object>) new FieldPolicyApplier(readPolicies,
                    policyEngine, requestPrincipal).apply(context);
        }
        // A form field's write policy: evaluates per principal (wave 4) — the same check the
        // request binder enforces; without an engine, policy-gated fields fail safe (hidden).
        java.util.function.Predicate<String> permits = policyId -> policyEngine != null
                && policyEngine.permits(policyId, requestPrincipal);
        if (viewBinding != null) {
            // A declarative view (roadmap Phase 39): the reserved `v` model is the whole contract
            // between the route and the tql/view/* pattern fragments. The request path anchors
            // the list pattern's self-rendering search/sort links.
            model.put("v", viewBinding.model(viewContext, java.util.Locale.forLanguageTag(tag),
                    pagePath, permits));
        }
        if (!boundViews.isEmpty()) {
            // Declarative parts on a hand-owned template (wave 2c): each bound view renders
            // into views['<id>'], inserted by the template via its pattern fragment.
            Map<String, Object> views = new LinkedHashMap<>();
            java.util.Locale viewLocale = java.util.Locale.forLanguageTag(tag);
            Map<String, Object> boundContext = viewContext;
            boundViews.forEach((id, binding) -> views.put(id,
                    binding.model(boundContext, viewLocale, pagePath, permits)));
            model.put("views", views);
        }

        // Publish the browser session's CSRF token (stashed on authentication) as the reserved
        // model variable `_csrf`, so the shell can emit <meta name="csrf-token"> for the
        // Hypermedia Components installCsrfHeader convention and forms can carry a hidden field.
        // Every template resolves its own URLs against this, so an application under a prefix
        // emits the URLs it also serves (docs/base-path.md).
        model.put("base", basePath);

        String csrfToken = exchange.getProperty(TesseraqlProperties.CSRF_TOKEN, String.class);
        if (csrfToken != null) {
            model.put("_csrf", csrfToken);
        }

        // The shell chrome's reserved model variables, contributed in a fixed order
        // (ShellChrome documents each block): the sidebar menu, the account popover, the
        // theme preference reads, the inbox badge, pins (including the recently-viewed store
        // write), and finally the resolved theme + UI defaults with the theme-cookie re-sync.
        ShellChrome chrome = new ShellChrome(exchange, evaluation, model, csrfToken);
        chrome.menu(appHome);
        chrome.account();
        chrome.readThemePreference();
        chrome.inbox();
        chrome.shortcuts(viewBinding);
        chrome.themeAndUiDefaults();

        // Shell negotiation (docs/view-composition.md wave 2a): one URL serves both shapes.
        // An htmx partial request (HX-Request, minus boosted navigation and history restore,
        // which both expect a full document) gets the bare #page-content region; direct
        // navigation gets the shell-wrapped page. `shell: never` declares an htmx-only region
        // endpoint; `always` restores unconditional wrapping. A template without a
        // #page-content region (a hand-written bare fragment) renders whole either way.
        String shellMode = response.effectiveShell();
        boolean partialRequest = "true".equals(
                exchange.getMessage().getHeader("HX-Request", String.class))
                && !"true".equals(exchange.getMessage().getHeader("HX-Boosted", String.class))
                && !"true".equals(exchange.getMessage()
                        .getHeader("HX-History-Restore-Request", String.class));
        boolean region = "never".equals(shellMode)
                || ("auto".equals(shellMode) && partialRequest);
        java.util.Locale locale = java.util.Locale.forLanguageTag(tag);
        String html = region
                ? Templates.render(appHome, templateName, model, locale, PAGE_CONTENT_SELECTOR)
                : Templates.render(appHome, templateName, model, locale);
        if (region && html.isBlank()) {
            html = Templates.render(appHome, templateName, model, locale);
        }

        int status = JsonResponseRenderer.CompiledStatus.resolve(statusWhen,
                response.effectiveStatus(), evaluation);
        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, status);
        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "text/html; charset=utf-8");
        headers.apply(exchange, evaluation);
        if ("auto".equals(shellMode)) {
            // The negotiated response differs by HX-Request, so caches must key on it.
            String vary = exchange.getMessage().getHeader("Vary", String.class);
            exchange.getMessage().setHeader("Vary", vary == null || vary.isBlank()
                    ? "HX-Request"
                    : vary.contains("HX-Request") ? vary : vary + ", HX-Request");
        }
        exchange.getMessage().setBody(html);
    }

    static Object interpolate(Object value, EvaluationContext evaluation) {
        return Interpolation.interpolate(value, evaluation);
    }
}
