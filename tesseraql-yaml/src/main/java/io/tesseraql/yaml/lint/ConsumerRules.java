package io.tesseraql.yaml.lint;

import static io.tesseraql.yaml.lint.LintFinding.Severity.ERROR;

import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.RouteFile;
import io.tesseraql.yaml.model.RouteDefinition;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Queue consumers ({@code consume/**}).
 *
 * <p>Extracted verbatim from {@code AppLinter} (docs/lint-restructure.md decision 1).
 */
final class ConsumerRules implements LintRule {

    private static final String INCOMPLETE_QUEUE_CONSUMER = "TQL-YAML-1009";

    private static final String UNCONFIGURED_CONSUMER_CHANNEL = "TQL-SEC-4090";

    private static final String CONSUMER_DECLARES_SOURCES = "TQL-YAML-1051";

    /** The run's memoized IO and cross-rule state, set at the top of {@link #lint}. */
    private LintContext context;

    @Override
    public void lint(LintContext context, AppManifest manifest,
            List<LintFinding> findings) {
        this.context = context;
        Path appHome = context.appHome();
        for (RouteFile consumer : manifest.consumers()) {
            lintConsumer(appHome, manifest.config(), consumer, findings);
        }
    }

    /**
     * Statically checks a {@code queue-consume} route under {@code consume/} (roadmap Phase 27): it
     * uses the {@code queue-consume} recipe, names a channel/topic ({@code TQL-YAML-1009}) whose
     * channel is configured ({@code TQL-SEC-4090}, so a consumer is never wired to a channel that
     * does not exist), and runs a SQL pipeline. Its {@code publish:} and {@code notify:} blocks are
     * linted the same way a command route's are.
     */
    void lintConsumer(Path appHome, AppConfig config, RouteFile consumer,
            List<LintFinding> findings) {
        RouteDefinition definition = consumer.definition();
        String source = appHome.relativize(consumer.source()).toString().replace('\\', '/');
        UnknownKeyRules.lintUnknownKeys(context, appHome, consumer.source(), RouteDefinition.class,
                Set.of(),
                findings);
        if (!"queue-consume".equals(definition.recipe())) {
            findings.add(new LintFinding(LintCodes.MESSAGING_KEY_ON_WRONG_RECIPE, ERROR, source,
                    "a consume/ route must"
                            + " use the queue-consume recipe, not '" + definition.recipe() + "'"));
            return;
        }
        var consume = definition.consume();
        if (consume == null || consume.channel() == null || consume.channel().isBlank()
                || consume.topic() == null || consume.topic().isBlank()) {
            findings.add(new LintFinding(INCOMPLETE_QUEUE_CONSUMER, ERROR, source,
                    "queue-consume route '"
                            + definition.id() + "' needs consume.channel and consume.topic"));
        } else if (config.navigate("tesseraql.messaging.channels." + consume.channel()) == null) {
            findings.add(new LintFinding(UNCONFIGURED_CONSUMER_CHANNEL, ERROR, source,
                    "queue-consume route '"
                            + definition.id() + "' references channel '" + consume.channel()
                            + "' not configured under tesseraql.messaging.channels"));
        }
        // The compiled pipeline is the steps: array — TransactionalCommandProcessor refuses an
        // empty one at build, and a consumer passes no workflow: that could make it state-only.
        // `sources.main` used to satisfy this check ("a sql: or steps: pipeline", in the deleted
        // vocabulary), which let a consumer pass lint and then fail at startup.
        if (definition.steps().isEmpty()) {
            findings.add(new LintFinding(INCOMPLETE_QUEUE_CONSUMER, ERROR, source,
                    "queue-consume route '"
                            + definition.id() + "' needs a steps: pipeline"));
        }
        // A consumer mounts no sources: nothing runs before its transaction, and nothing reads a
        // result after it — there is no response. Refusing the key beats compiling it to nothing.
        if (!definition.sources().isEmpty()) {
            findings.add(new LintFinding(CONSUMER_DECLARES_SOURCES, ERROR, source,
                    "queue-consume route '"
                            + definition.id() + "' declares sources: — a consumer's pipeline is its"
                            + " steps:, and a declared source compiles to nothing here"));
        }
        definition.steps().forEach((name, step) -> {
            if (step.file() != null && !Files.isRegularFile(
                    consumer.source().getParent().resolve(step.file()))) {
                findings.add(new LintFinding(LintCodes.MISSING_SQL_FILE, ERROR, source,
                        "Step '" + name + "' references a missing SQL file: " + step.file()));
            }
        });
        // A consumer's validate: is compiled and run exactly like a command's, so its rules get
        // the same static checks — a typo'd validation SQL filename used to reach startup.
        DocumentRules.lintValidation(context, consumer.source(), definition, source, findings);
        LiveViewRules.lintEmit(definition, source, findings);
        DocumentRules.lintInvalidates(context, definition, source, findings);
        MessagingRules.lintPublish(config, definition, source, findings);
        MessagingRules.lintNotify(config, definition, source, findings, context.functions());
        DocumentRules.lintDatasource(context, config, consumer.source(), definition, source,
                findings);
        // A consumer's SQL is fed by an external message payload — as untrusted as an HTTP body —
        // so the embedded-variable injection guard (TQL-SQL-2109) applies here, not just on routes.
        DocumentRules.lintEmbeddedVariables(context, consumer.source(), definition, source,
                findings);
        DocumentRules.lintOptimisticLocking(context, consumer.source(), definition, source,
                findings);
        DocumentRules.lintTenantPredicate(context, config, consumer.source(), definition, source,
                findings);
    }
}
