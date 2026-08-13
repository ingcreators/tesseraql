package io.tesseraql.yaml.lint;

import static io.tesseraql.yaml.lint.LintFinding.Severity.ERROR;
import static io.tesseraql.yaml.lint.LintFinding.Severity.WARNING;

import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.model.RouteDefinition;
import java.util.List;

/**
 * Live-view wiring: a command's {@code emit:} topics and a view's
 * {@code refreshOn:} subscription (docs/realtime.md).
 *
 * <p>Extracted verbatim from {@code AppLinter} (docs/lint-restructure.md decision 1).
 */
final class LiveViewRules {

    private static final String INVALID_TOPIC_NAME = "TQL-YAML-1039";

    private static final String REFRESH_ON_FORM_VIEW = "TQL-VIEW-3311";

    private static final String REFRESH_ON_UNEMITTED_TOPIC = "TQL-VIEW-3312";

    private LiveViewRules() {
    }

    /** A live-view topic name: lowercase dot/dash-separated segments (docs/realtime.md). */
    static final java.util.regex.Pattern TOPIC_NAME = java.util.regex.Pattern
            .compile("[a-z0-9]+(?:[.-][a-z0-9]+)*");

    /**
     * Live-view emit lints (docs/realtime.md): emit: is a command-json key (TQL-YAML-1038, the
     * topics broadcast after that command's commit), and a topic name must match the slug shape
     * (TQL-YAML-1039) so it survives URL, SSE event-name, and selector contexts unquoted.
     *
     * <p>Reached from routes, queue consumers, and MCP tools alike. The file the definition came
     * from was never read here, and taking it as a parameter is what made this look like a
     * route-only lint for as long as tools went unchecked.
     */
    static void lintEmit(RouteDefinition definition, String source,
            List<LintFinding> findings) {
        if (definition.emit().isEmpty()) {
            return;
        }
        if (!"command-json".equals(definition.recipe())) {
            findings.add(new LintFinding(LintCodes.EMIT_UNSUPPORTED, ERROR, source,
                    "emit: is only supported on command-json routes, not '"
                            + definition.recipe() + "'"));
        }
        for (String topic : definition.emit()) {
            if (topic == null || !TOPIC_NAME.matcher(topic).matches()) {
                findings.add(new LintFinding(INVALID_TOPIC_NAME, ERROR, source,
                        "emit: topic '" + topic + "' is not a legal topic name"
                                + " (lowercase dot/dash-separated segments)"));
            }
        }
    }

    /**
     * refreshOn: lints (docs/realtime.md): live refresh replaces the region wholesale, so a
     * form — which would lose in-progress input — cannot declare it (TQL-VIEW-3311), and a
     * topic no command emits will never fire — almost always a typo (TQL-VIEW-3312, a
     * warning: another environment's routes may emit it).
     */
    static void lintRefreshOn(AppManifest manifest, String source,
            io.tesseraql.yaml.view.ViewSpec spec, List<LintFinding> findings) {
        String topic = spec.refreshOn();
        if (topic == null || topic.isBlank()) {
            return;
        }
        if (io.tesseraql.yaml.view.ViewSpec.FORM.equals(spec.view())) {
            findings.add(new LintFinding(REFRESH_ON_FORM_VIEW, ERROR, source,
                    "view " + spec.id() + ": refreshOn: is not a form-view key — a live"
                            + " replacement would discard in-progress input"));
            return;
        }
        if (!TOPIC_NAME.matcher(topic.trim()).matches()) {
            findings.add(new LintFinding(INVALID_TOPIC_NAME, ERROR, source,
                    "refreshOn: topic '" + topic + "' is not a legal topic name"
                            + " (lowercase dot/dash-separated segments)"));
            return;
        }
        boolean emitted = manifest.routes().stream()
                .anyMatch(route -> route.definition().emit().contains(topic.trim()));
        if (!emitted) {
            findings.add(new LintFinding(REFRESH_ON_UNEMITTED_TOPIC, WARNING, source,
                    "view " + spec.id() + ": refreshOn: topic '" + topic.trim()
                            + "' is emitted by no route — the view will never refresh"));
        }
    }
}
