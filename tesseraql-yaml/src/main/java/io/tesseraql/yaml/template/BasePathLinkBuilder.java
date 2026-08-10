package io.tesseraql.yaml.template;

import java.util.Map;
import org.thymeleaf.context.IExpressionContext;
import org.thymeleaf.linkbuilder.StandardLinkBuilder;

/**
 * Resolves Thymeleaf link expressions against the application's base path
 * (docs/base-path.md): {@code th:href="@{/assets/x}"} renders {@code /assets/x} normally and
 * {@code /apps/shop-a/assets/x} for an application served under {@code /apps/shop-a}.
 *
 * <p>This is the whole of the prefix logic. The alternative — writing
 * {@code th:href="|${base}/assets/x|"} at every one of the four hundred-odd URLs the framework
 * and its bundled apps emit — puts the same rule in four hundred string concatenations, and the
 * first attempt at it produced three classes of bug in an afternoon: a duplicated
 * {@code th:src} where an element already had one, silently skipped URLs that were already
 * expressions, and no way to notice either before running the page.
 *
 * <p>Thymeleaf's own {@code StandardLinkBuilder} already does everything else a link needs —
 * query parameters, fragment identifiers, leaving absolute and protocol-relative URLs alone.
 * Only the context path is missing outside a servlet environment, and that is exactly the one
 * method it exposes for the purpose.
 */
final class BasePathLinkBuilder extends StandardLinkBuilder {

    /** The model variable the renderer publishes; absent or empty means no prefix. */
    static final String BASE_PATH_VARIABLE = "base";

    /**
     * The prefix comes from the rendering context rather than from this instance, so one engine
     * — which Thymeleaf caches per application home — serves every render regardless of how the
     * runtime was started.
     */
    @Override
    protected String computeContextPath(IExpressionContext context, String base,
            Map<String, Object> parameters) {
        Object configured = context.getVariable(BASE_PATH_VARIABLE);
        if (configured == null) {
            return "";
        }
        String prefix = String.valueOf(configured).trim();
        return "/".equals(prefix) ? "" : prefix;
    }
}
