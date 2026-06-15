package io.tesseraql.yaml.workflow;

import io.tesseraql.yaml.config.AppConfig;

/**
 * Approval-workflow configuration (roadmap Phase 28), read from {@code tesseraql.workflow.*}. It
 * expresses the managed/app duality, mirroring the IAM realm model and the Phase 29 org-unit model:
 *
 * <ul>
 *   <li>{@code mode: managed} — the runtime provisions and maintains the managed
 *       {@code tql_workflow_instance} / {@code tql_workflow_history} tables; a transition advances
 *       the instance row.</li>
 *   <li>{@code mode: app} (default) — the application keeps state in its business table's
 *       {@code stateColumn}; nothing managed is provisioned.</li>
 * </ul>
 *
 * <p>A workflow document may override the mode per workflow via its {@code mode} field; this is the
 * app-wide default.
 *
 * @param mode {@code managed} or {@code app}
 */
public record WorkflowSettings(String mode) {

    public WorkflowSettings {
        mode = mode == null || mode.isBlank() ? "app" : mode;
    }

    /** Whether the managed {@code tql_workflow_*} tables are provisioned and maintained. */
    public boolean managed() {
        return "managed".equalsIgnoreCase(mode);
    }

    /** Reads the settings from config, defaulting to {@code app} mode. */
    public static WorkflowSettings from(AppConfig config) {
        return new WorkflowSettings(config.getString("tesseraql.workflow.mode").orElse(null));
    }

    /**
     * The deadline sweeper's interval in milliseconds, from {@code tesseraql.workflow.sweep.interval}
     * (default 60s), roadmap Phase 28 slice 3.
     */
    public static long sweepIntervalMillis(AppConfig config) {
        return config.getString("tesseraql.workflow.sweep.interval")
                .map(io.tesseraql.core.util.Durations::toMillis)
                .orElse(60_000L);
    }
}
