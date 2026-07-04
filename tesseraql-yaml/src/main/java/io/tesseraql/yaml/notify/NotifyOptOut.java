package io.tesseraql.yaml.notify;

import io.tesseraql.core.account.PreferenceStore;
import java.util.Map;

/**
 * The per-user notification opt-out decision (roadmap Phase 48): a notification that names
 * its recipient is skipped when that subject stored {@code notify.<channel>.optOut} for the
 * channel. Shared by every enqueue path (command routes, batch notify steps) so the semantics
 * cannot drift.
 *
 * <p>The caller supplies the tenant scope — command routes pass the acting principal's tenant
 * (the recipient of a business notification lives in the caller's tenant); job contexts
 * without a principal pass {@code null} and check the untenanted scope. A notification
 * without a {@code recipient:} is channel-level and never consults preferences.
 */
public final class NotifyOptOut {

    private static final System.Logger LOG = System.getLogger(NotifyOptOut.class.getName());

    private NotifyOptOut() {
    }

    /** Whether this event's declared recipient opted out of the channel. */
    public static boolean optedOut(NotifyEvents.CompiledNotify notification,
            Map<String, Object> context, PreferenceStore preferences, String tenantId) {
        if (preferences == null) {
            return false;
        }
        String recipient = notification.resolveRecipient(context);
        if (recipient == null) {
            return false;
        }
        boolean optedOut = "true".equals(preferences.preferences(tenantId, recipient)
                .get("notify." + notification.channel() + ".optOut"));
        if (optedOut) {
            LOG.log(System.Logger.Level.DEBUG,
                    "Notification {0} skipped: recipient {1} opted out of channel {2}",
                    notification.source(), recipient, notification.channel());
        }
        return optedOut;
    }
}
