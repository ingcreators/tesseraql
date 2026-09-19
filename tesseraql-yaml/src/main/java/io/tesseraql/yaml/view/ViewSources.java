package io.tesseraql.yaml.view;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.yaml.model.RouteDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * The one judgement of what a bound view reads (docs/view-composition.md): every
 * {@code source:} a view document names — its own, a child's, a panel's, and those of the
 * documents it embeds — must be {@code main} or a {@code sources:} entry of the route that
 * binds it, whatever arm the entry declares ({@code TQL-VIEW-3308}). The lint and the compiler
 * both ask here, so a view the lint passes is a view the build binds
 * (docs/audit-low-leads.md slice 21): the lint used to judge the {@code response.html.view}
 * document's children and panels alone, and the compiler judged the embedded documents and
 * the {@code views:}-bound ones as well — every difference between the two was a lint that
 * said nothing where the boot refused. Neither judged the document's own {@code source:}, so a
 * typo there rendered an empty page.
 */
public final class ViewSources {

    /**
     * TQL-VIEW-3308: a view's own {@code source:}, a child's, a panel's, or an embedded
     * document's is not a source of the binding route (a sources: entry, or main).
     */
    public static final TqlErrorCode UNDECLARED = new TqlErrorCode(TqlDomain.VIEW, 3308);

    private ViewSources() {
    }

    /**
     * One name a view reads that the route does not declare: which document, at which position
     * ({@code source}, {@code children source}, {@code panel source}), and the name.
     */
    public record Undeclared(String viewId, String position, String name) {

        /** The sentence both altitudes raise, so the lint's finding reads as the boot's refusal. */
        public String message() {
            return "view " + viewId + ": " + position + " " + name
                    + " is not a source of the route (a sources: entry, or main)";
        }
    }

    /**
     * Whether {@code source} is one the route publishes — {@code main} always (a route without
     * a {@code main} source still renders a create form or a dashboard from it), else a key of
     * its {@code sources:}.
     */
    public static boolean declares(RouteDefinition route, String source) {
        if (RouteDefinition.MAIN.equals(source)) {
            return true;
        }
        var sources = route == null ? null : route.sources();
        return sources != null && sources.containsKey(source);
    }

    /**
     * Every source name {@code spec} and the documents it embeds read that {@code route} does
     * not declare, in document order. An embedding entry's own {@code source:} is the override
     * the embedded model reads through, so it is judged in the embedded document's stead; an
     * entry without one leaves the embedded document's {@code source:} to be judged as its own.
     * {@code embeddedById} resolves an embedded id to its parsed document, or {@code null} when
     * the id is unknown — an unresolved embed is the registry rule's finding, not a source
     * judgement.
     */
    public static List<Undeclared> undeclared(ViewSpec spec, RouteDefinition route,
            Function<String, ViewSpec> embeddedById) {
        List<Undeclared> undeclared = new ArrayList<>();
        judge(spec, route, true, undeclared);
        for (ViewSpec.Child child : spec.children()) {
            if (child.view() != null) {
                judgeEmbedded(child.view(), child.source(), route, embeddedById, undeclared);
            }
        }
        for (ViewSpec.Panel panel : spec.panels()) {
            if (panel.view() != null) {
                judgeEmbedded(panel.view(), panel.source(), route, embeddedById, undeclared);
            }
        }
        return undeclared;
    }

    private static void judgeEmbedded(String embeddedId, String override, RouteDefinition route,
            Function<String, ViewSpec> embeddedById, List<Undeclared> undeclared) {
        ViewSpec embedded = embeddedById.apply(embeddedId);
        if (embedded == null) {
            return;
        }
        judge(embedded, route, override == null || override.isBlank(), undeclared);
    }

    /**
     * One document's names against the route: its own {@code source:} when {@code ownSource}
     * (not when a host entry overrides it), each child's, each panel's. An embedding entry's
     * {@code source:} is the override, judged as that entry's; an embedding entry without one
     * reads no source of its own.
     */
    private static void judge(ViewSpec spec, RouteDefinition route, boolean ownSource,
            List<Undeclared> undeclared) {
        if (ownSource && !declares(route, spec.source())) {
            undeclared.add(new Undeclared(spec.id(), "source", spec.source()));
        }
        for (ViewSpec.Child child : spec.children()) {
            if (child.view() != null && (child.source() == null || child.source().isBlank())) {
                continue;
            }
            if (!declares(route, child.source())) {
                undeclared.add(new Undeclared(spec.id(), "children source", child.source()));
            }
        }
        for (ViewSpec.Panel panel : spec.panels()) {
            if (panel.view() != null && (panel.source() == null || panel.source().isBlank())) {
                continue;
            }
            String source = panel.source() == null || panel.source().isBlank()
                    ? RouteDefinition.MAIN
                    : panel.source();
            if (!declares(route, source)) {
                undeclared.add(new Undeclared(spec.id(), "panel source", source));
            }
        }
    }
}
