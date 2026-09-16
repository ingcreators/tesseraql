package io.tesseraql.yaml.app;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import java.text.ParseException;
import java.util.Optional;
import org.quartz.CronExpression;

/**
 * A job schedule's {@code cron:}, judged where it is written by the grammar the scheduler
 * fires it by — Quartz's own, seconds first (docs/jobs.md), the one the runtime computes fire
 * times with (docs/audit-low-leads.md decision 10). One predicate on both altitudes: the
 * linter reports from it and the runtime refuses from it, so the ordinary mistake — the
 * five-field crontab reflex, {@code 0 3 * * *} — is a finding at the job rather than an uncoded
 * exception that took the whole application down at boot.
 */
public final class CronExpressions {

    /**
     * TQL-YAML-1068: a job's {@code schedule.cron} is not a Quartz cron expression the
     * scheduler can fire — six or seven fields, seconds first, with {@code ?} in one of the two
     * day fields. Reported at lint and refused at boot from the same predicate.
     */
    public static final TqlErrorCode INVALID = new TqlErrorCode(TqlDomain.YAML, 1068);

    private CronExpressions() {
    }

    /**
     * Why {@code expression} is not a cron the scheduler can fire, or empty when it is: the
     * parser's own reason, after the expression itself — so a reader sees what was written
     * and what the grammar made of it.
     */
    public static Optional<String> problem(String expression) {
        try {
            new CronExpression(expression);
            return Optional.empty();
        } catch (ParseException | RuntimeException invalid) {
            return Optional.of("'" + ExportDeclarations.bounded(expression) + "' is not a cron"
                    + " expression the scheduler can fire (Quartz cron: six or seven fields,"
                    + " seconds first, with ? in one of the two day fields): "
                    + invalid.getMessage());
        }
    }
}
