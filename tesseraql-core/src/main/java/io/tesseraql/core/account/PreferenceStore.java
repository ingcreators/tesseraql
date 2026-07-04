package io.tesseraql.core.account;

import java.util.Map;

/**
 * Per-user preferences (roadmap Phase 48, design in docs/account.md): small, namespaced
 * key/value pairs scoped to a tenant + subject — {@code ui.locale}, {@code ui.theme},
 * {@code notify.<channel>.optOut}, and app-declared {@code app.<key>} entries.
 *
 * <p>The subject is always the authenticated session principal's; callers never pass a
 * subject taken from request input, so cross-subject reads and writes are impossible by
 * construction. A {@code null} tenant is normalized to the empty string by implementations
 * (untenanted apps).
 */
public interface PreferenceStore {

    /** All preferences for the subject, keyed by preference name (empty when none). */
    Map<String, String> preferences(String tenantId, String subject);

    /** Creates or replaces one preference. */
    void put(String tenantId, String subject, String key, String value);

    /** Removes one preference (a no-op when absent). */
    void remove(String tenantId, String subject, String key);
}
