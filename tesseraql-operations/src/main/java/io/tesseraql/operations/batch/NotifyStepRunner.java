package io.tesseraql.operations.batch;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.model.PipelineStep;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The {@code notify:} step kind (roadmap Phase 20): enqueues the step's notification on the
 * transactional outbox instead of executing SQL.
 */
final class NotifyStepRunner {

    private NotifyStepRunner() {
    }

    /**
     * Enqueues the step's notification on the outbox (roadmap Phase 20). The event always goes
     * to the framework's outbox table — not a per-tenant datasource — because the dispatcher of
     * this runtime claims it from there. A skipped guard reports zero affected rows.
     */
    static Map<String, Object> run(StepContext context) {
        PipelineStep step = context.step();
        if (step.sql() != null) {
            throw TqlException.builder(StepContext.STEP_ERROR)
                    .message("Step '" + step.id() + "' must declare exactly one of sql: or"
                            + " notify:")
                    .build();
        }
        if (context.notificationOutbox() == null) {
            throw TqlException.builder(StepContext.STEP_ERROR)
                    .message("Step '" + step.id() + "': notify steps need the runtime's outbox"
                            + " store")
                    .build();
        }
        io.tesseraql.yaml.notify.NotifyEvents.CompiledNotify notification = io.tesseraql.yaml.notify.NotifyEvents
                .compile(context.jobFile().definition().id(), step.id(), step.notification());
        if (!notification.fires(context.context())) {
            return Map.of("affectedRows", 0);
        }
        // A recipient-naming notification honors that subject's per-channel opt-out (roadmap
        // Phase 48). Job contexts carry no acting principal, so the untenanted scope applies.
        if (io.tesseraql.yaml.notify.NotifyOptOut.optedOut(notification, context.context(),
                context.preferences(), null)) {
            return Map.of("affectedRows", 0, "optedOut", true);
        }
        String eventId = context.notificationOutbox().insert(notification.build(context.context(),
                context.appName() == null ? "app" : context.appName(),
                notification.resolveRecipient(context.context()), null));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("affectedRows", 1);
        result.put("eventId", eventId);
        return result;
    }
}
