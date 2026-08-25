package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.expr.EvaluationContext;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;

/**
 * When an outbox entry becomes deliverable (docs/notifications.md, "Scheduled delivery").
 *
 * <p>Everything on the outbox was delivered as soon after commit as the dispatcher got to it,
 * and the only future-time construct on the whole surface was a workflow's {@code deadlines:},
 * which serves workflow documents alone. "Remind the customer three days after the order ships"
 * was therefore a cron job scanning an app table the command had to remember to populate.
 *
 * <p>Two declared forms, and exactly one of them: {@code delay:} is relative to the commit,
 * {@code deliverAt:} is a bindable path resolving to an instant. Declaring both is a build
 * error, because the answer to "when" cannot be two answers.
 *
 * @param delay     a duration from the commit, e.g. {@code 72h}
 * @param deliverAt a bindable path resolving to an instant, e.g. {@code params.pickupStart}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScheduleSpec(String delay, String deliverAt) {

    /** TQL-BATCH-5317: a scheduled entry declares both forms, or an unusable instant. */
    public static final TqlErrorCode INVALID_SCHEDULE = new TqlErrorCode(TqlDomain.BATCH, 5317);

    /** Whether anything is declared here at all. */
    public boolean isEmpty() {
        return blank(delay) && blank(deliverAt);
    }

    /** Whether both forms are declared, which is a build error wherever it is checked. */
    public boolean isAmbiguous() {
        return !blank(delay) && !blank(deliverAt);
    }

    /**
     * The instant this entry becomes deliverable, or null when it is deliverable at once.
     *
     * <p>{@code deliverAt:} resolves against the command's own context, so it reads the same
     * paths every other binding reads; a path resolving to nothing means "no schedule" rather
     * than an error, the way an absent optional input does.
     */
    public Instant resolve(Map<String, Object> context, Instant now) {
        if (isAmbiguous()) {
            throw new TqlException(INVALID_SCHEDULE,
                    "delay: and deliverAt: are two answers to one question — declare one");
        }
        if (!blank(delay)) {
            return now.plusMillis(io.tesseraql.core.util.Durations.toMillis(delay));
        }
        if (blank(deliverAt)) {
            return null;
        }
        Object value = new EvaluationContext(context)
                .resolve(Arrays.asList(deliverAt.split("\\.")));
        return instant(value);
    }

    /** A resolved {@code deliverAt:} value as an instant; an unusable one names itself. */
    private Instant instant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Instant already) {
            return already;
        }
        if (value instanceof java.time.OffsetDateTime offset) {
            return offset.toInstant();
        }
        if (value instanceof java.time.ZonedDateTime zoned) {
            return zoned.toInstant();
        }
        if (value instanceof java.time.LocalDateTime local) {
            return local.atZone(java.time.ZoneId.systemDefault()).toInstant();
        }
        if (value instanceof java.time.LocalDate date) {
            return date.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant();
        }
        if (value instanceof java.util.Date legacy) {
            return legacy.toInstant();
        }
        try {
            return Instant.parse(String.valueOf(value));
        } catch (java.time.format.DateTimeParseException ex) {
            throw new TqlException(INVALID_SCHEDULE, "deliverAt: '" + deliverAt
                    + "' resolved to '" + value + "', which is not an instant");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
