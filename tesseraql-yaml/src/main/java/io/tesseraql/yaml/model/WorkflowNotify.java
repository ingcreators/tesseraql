package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A workflow's reminder notifications (roadmap Phase 28 slice 3, Phase 20 channels): a
 * {@link NotifySpec} fired when a task is assigned and one fired when a task is escalated. Each rides
 * the transactional outbox as a {@code NOTIFICATION} event — same at-least-once delivery, retries,
 * and dead-letters as a route's {@code notify:} block — addressed via its {@code payload} (the
 * resolved {@code assignee} is in scope).
 *
 * @param assigned  fired when a transition opens a task for the resolved assignee, or {@code null}
 * @param escalated fired when the sweeper reassigns an overdue task, or {@code null}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkflowNotify(NotifySpec assigned, NotifySpec escalated) {
}
