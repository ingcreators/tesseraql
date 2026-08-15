package io.tesseraql.yaml.lint;

import static io.tesseraql.yaml.lint.LintFinding.Severity.ERROR;

import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.manifest.AppManifest;
import java.time.Duration;
import java.util.List;

/**
 * The liveness window has to be wider than the heartbeat that fills it
 * (docs/audit-hardening.md Decision 6).
 *
 * <p>A running job writes its pulse on a timer; {@code overlap: skip} believes a previous run only
 * while that pulse is inside the window. Configure the window shorter than the interval and every
 * run looks dead between its own heartbeats — the reaper kills live runs and firings that should
 * skip go ahead. That is the exact false positive the alert-only SLA decision was written to avoid,
 * so it is a refusal rather than a warning.
 *
 * <p>Equal values are refused too. A window the same width as the interval leaves no room for the
 * scheduling jitter and database latency that a real pulse carries, so it fails intermittently —
 * which is worse than failing at all.
 */
final class BatchHeartbeatRules implements LintRule {

    private static final String LIVENESS_WINDOW_TOO_SHORT = "TQL-BATCH-4211";

    private static final String INTERVAL = "tesseraql.batch.heartbeat.interval";

    private static final String WINDOW = "tesseraql.batch.heartbeat.livenessWindow";

    @Override
    public void lint(LintContext context, AppManifest manifest, List<LintFinding> findings) {
        AppConfig config = manifest.config();
        Duration interval = duration(config, INTERVAL, "30s");
        Duration window = duration(config, WINDOW, "5m");
        if (interval == null || window == null) {
            // An unparseable duration is TQL-YAML-1301's to report; this rule has nothing to
            // compare and says nothing rather than guessing.
            return;
        }
        if (window.compareTo(interval) <= 0) {
            findings.add(new LintFinding(LIVENESS_WINDOW_TOO_SHORT, ERROR, "config",
                    WINDOW + " (" + window + ") is not longer than " + INTERVAL + " ("
                            + interval + "), so a run would look dead between its own"
                            + " heartbeats — live runs would be reaped and overlapping firings"
                            + " would go ahead"));
        }
    }

    private static Duration duration(AppConfig config, String key, String fallback) {
        try {
            return io.tesseraql.core.util.Durations.parse(config.getString(key).orElse(fallback));
        } catch (RuntimeException unparseable) {
            return null;
        }
    }
}
