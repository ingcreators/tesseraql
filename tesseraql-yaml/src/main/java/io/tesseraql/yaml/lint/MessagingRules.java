package io.tesseraql.yaml.lint;

import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.model.RouteDefinition;
import java.util.List;
import java.util.Set;

/**
 * Outbound messaging on a document: {@code webhook:}, {@code publish:} and
 * {@code notify:} — shared by routes, consumers and batch jobs.
 *
 * <p>Extracted verbatim from {@code AppLinter} (docs/lint-restructure.md decision 1).
 */
final class MessagingRules {

    private MessagingRules() {
    }

    /** Recipes a {@code publish:} block may ride - the transactional commands (roadmap Phase 27). */
    static final Set<String> PUBLISH_RECIPES = Set.of("command-json", "webhook",
            "queue-consume");

    /**
     * Statically checks the inbound {@code webhook} recipe (roadmap Phase 26): the route names a
     * verifier ({@code TQL-SEC-4082}) that is configured under
     * {@code tesseraql.connectors.webhooks} ({@code TQL-SEC-4083}, so a webhook is never served
     * unverified), and runs a SQL pipeline ({@code TQL-YAML-1008}). A {@code webhook:} block on a
     * non-webhook recipe is a misuse.
     */
    static void lintWebhook(AppConfig config, RouteDefinition definition, String source,
            List<LintFinding> findings) {
        if (!"webhook".equals(definition.recipe())) {
            if (definition.webhook() != null) {
                findings.add(new LintFinding("TQL-YAML-1008", "error", source,
                        "webhook: is only supported on the webhook recipe, not '"
                                + definition.recipe() + "'"));
            }
            return;
        }
        String provider = definition.webhook() == null ? null : definition.webhook().provider();
        if (provider == null || provider.isBlank()) {
            findings.add(new LintFinding("TQL-SEC-4082", "error", source,
                    "webhook route '" + definition.id() + "' needs a webhook.provider"));
        } else if (config.navigate("tesseraql.connectors.webhooks." + provider) == null) {
            findings.add(new LintFinding("TQL-SEC-4083", "error", source, "webhook route '"
                    + definition.id() + "' references verifier '" + provider
                    + "' not configured under tesseraql.connectors.webhooks"));
        }
        // A webhook always compiles through the transactional command processor, which refuses
        // an empty steps: at build. `sources.main` used to satisfy this check (the deleted "a
        // sql: or steps: pipeline" vocabulary), so the document passed lint and failed startup.
        if (definition.steps().isEmpty()) {
            findings.add(new LintFinding("TQL-YAML-1008", "error", source, "webhook route '"
                    + definition.id() + "' needs a steps: pipeline"));
        }
    }

    /**
     * Statically checks a {@code publish:} block (roadmap Phase 27): it rides a transactional
     * command ({@code TQL-YAML-1010}) and names a channel configured under
     * {@code tesseraql.messaging.channels} ({@code TQL-SEC-4091}), so a publish never targets a
     * channel that does not exist.
     */
    static void lintPublish(AppConfig config, RouteDefinition definition, String source,
            List<LintFinding> findings) {
        var publish = definition.publish();
        if (publish == null) {
            return;
        }
        if (!PUBLISH_RECIPES.contains(definition.recipe())) {
            findings.add(new LintFinding("TQL-YAML-1010", "error", source, "publish: is only"
                    + " supported on command routes (command-json, webhook, queue-consume), not '"
                    + definition.recipe() + "'"));
            return;
        }
        if (publish.channel() == null || publish.channel().isBlank()) {
            findings.add(new LintFinding("TQL-SEC-4091", "error", source,
                    "publish: of '" + definition.id() + "' needs a channel"));
        } else if (config.navigate("tesseraql.messaging.channels." + publish.channel()) == null) {
            findings.add(new LintFinding("TQL-SEC-4091", "error", source, "publish: of '"
                    + definition.id() + "' references channel '" + publish.channel()
                    + "' not configured under tesseraql.messaging.channels"));
        }
    }

    /**
     * Statically checks the {@code notify:} block of a command route (roadmap Phase 20):
     * notifications only apply to command routes, each declares a {@code channel:} that the
     * config knows, and its {@code when:} guard parses.
     */
    static void lintNotify(AppConfig config, RouteDefinition definition, String source,
            List<LintFinding> findings) {
        if (definition.notifications().isEmpty()) {
            return;
        }
        if (!"command-json".equals(definition.recipe())) {
            findings.add(new LintFinding("TQL-YAML-1004", "error", source,
                    "notify: is only supported on command-json routes, not '"
                            + definition.recipe() + "'"));
        }
        definition.notifications()
                .forEach((id, spec) -> lintNotifySpec(config, id, spec, source, findings));
    }

    static void lintNotifySpec(AppConfig config, String id,
            io.tesseraql.yaml.model.NotifySpec spec, String source, List<LintFinding> findings) {
        if (spec.channel() == null || spec.channel().isBlank()) {
            findings.add(new LintFinding("TQL-FIELD-2004", "error", source,
                    "Notification '" + id + "' needs a channel:"));
        } else if (config
                .navigate("tesseraql.notifications.channels." + spec.channel()) == null) {
            // A warning, not an error: another environment's config may declare the channel.
            findings.add(new LintFinding("TQL-YAML-1102", "warning", source,
                    "Notification '" + id + "' references undeclared channel '"
                            + spec.channel() + "'"));
        }
        if (spec.when() != null && !spec.when().isBlank()) {
            try {
                io.tesseraql.core.expr.ExpressionParser.parse(spec.when());
            } catch (RuntimeException ex) {
                findings.add(new LintFinding("TQL-SQL-2101", "error", source,
                        "Notification '" + id + "' has a malformed when: expression: "
                                + ex.getMessage()));
            }
        }
        // An inbox message must be addressed (roadmap Phase 49): without a recipient there
        // is no user to deliver to, so this fails the build instead of dead-lettering.
        if (spec.channel() != null && "inbox".equals(config.getString(
                "tesseraql.notifications.channels." + spec.channel() + ".type")
                .orElse(null))
                && (spec.recipient() == null || spec.recipient().isBlank())) {
            findings.add(new LintFinding("TQL-YAML-1034", "error", source,
                    "Notification '" + id + "' delivers to inbox channel '" + spec.channel()
                            + "' but declares no recipient:"));
        }
        // attach: rides only mail (docs/analytics-experience.md): a webhook posts JSON and an
        // inbox message links, so declaring an attachment there would silently drop it — the
        // build says so instead. The check only fires when the channel's declared type says
        // it is not mail; an undeclared channel already warned above.
        if (spec.attach() != null && !spec.attach().isBlank() && spec.channel() != null) {
            String type = config.getString("tesseraql.notifications.channels."
                    + spec.channel() + ".type").orElse(null);
            if (type != null && !"mail".equals(type)) {
                findings.add(new LintFinding("TQL-FIELD-2004", "error", source,
                        "Notification '" + id + "' declares attach: but channel '"
                                + spec.channel() + "' is type " + type
                                + " — attachments ride mail channels only"));
            }
        }
    }
}
