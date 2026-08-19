package io.tesseraql.yaml.template;

import java.util.Map;
import org.thymeleaf.context.IExpressionContext;
import org.thymeleaf.linkbuilder.StandardLinkBuilder;

/**
 * Resolves Thymeleaf link expressions against the application's base path
 * (docs/base-path.md): {@code th:href="@{/assets/x}"} renders {@code /assets/x} normally and
 * {@code /shop-a/assets/x} for an application served under {@code /shop-a}.
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
 *
 * <p>Every engine in the codebase installs it, not only the one that renders pages. Thymeleaf's
 * own builder <em>refuses</em> a context-relative {@code @{/x}} outside a web context rather than
 * passing it through, so an engine without this one fails on any shared framework template the
 * moment those templates use link expressions — which is how Studio's preview engine announced
 * itself.
 */
public final class BasePathLinkBuilder extends StandardLinkBuilder {

    /** The model variable the renderer publishes; absent or empty means no prefix. */
    public static final String BASE_PATH_VARIABLE = "base";

    /**
     * The studio shell's member segment (docs/studio-shell.md structural decision 2): published
     * only when a studio page renders under {@code /_tesseraql/studio/<member>/}, and applied
     * to studio-addressed link targets below — the same one-place philosophy as the base path,
     * so the studio app tree's two-hundred-odd link expressions stay member-agnostic.
     */
    public static final String STUDIO_MEMBER_VARIABLE = "_studioMember";

    @Override
    protected String processLink(org.thymeleaf.context.IExpressionContext context, String link) {
        Object member = context.getVariable(STUDIO_MEMBER_VARIABLE);
        if (member != null && link != null) {
            int at = link.indexOf("/_tesseraql/studio/ui");
            if (at >= 0) {
                link = link.substring(0, at) + "/_tesseraql/studio/" + member
                        + link.substring(at + "/_tesseraql/studio".length());
            }
        }
        return super.processLink(context, link);
    }

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
        if ("/".equals(prefix)) {
            return "";
        }
        // An asset is role-independent (docs/application-roles.md structural decision 5):
        // keying the browser cache by acting role would duplicate it, so a target under
        // /assets sheds the /_as/<role> activation segment the prefix may carry. `_as` cannot
        // occur in a real base path — the leading-underscore name rule reserves it — so the
        // marker is unambiguous.
        if (base != null && (base.equals("/assets") || base.startsWith("/assets/"))) {
            int segment = prefix.indexOf("/_as/");
            if (segment >= 0) {
                return prefix.substring(0, segment);
            }
        }
        return prefix;
    }
}
