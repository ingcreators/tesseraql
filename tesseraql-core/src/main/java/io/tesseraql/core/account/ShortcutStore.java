package io.tesseraql.core.account;

import java.time.Instant;
import java.util.List;

/**
 * Per-user pins and recents (roadmap Phase 51, design in docs/productivity.md): two small,
 * capped lists of {@code {label, href}} per subject. A pin is user-curated (and a pinned
 * URL with a query string IS a saved filter); a recent is the automatic ring of viewed
 * records. The account-surface construction invariant applies: the subject is always the
 * session principal's, and hrefs are relative paths only — callers validate before
 * {@link #put}.
 */
public interface ShortcutStore {

    String PIN = "pin";

    String RECENT = "recent";

    /** One shortcut; ordering is by {@code touchedAt} descending everywhere. */
    record Shortcut(String href, String label, Instant touchedAt) {
    }

    /** The subject's shortcuts of a kind, most recently touched first. */
    List<Shortcut> list(String tenantId, String subject, String kind, int limit);

    /**
     * Creates or bumps one shortcut (same href = relabel + touch), then trims the kind's
     * oldest entries beyond {@code cap} — the ring semantics both kinds share.
     */
    void put(String tenantId, String subject, String kind, String href, String label,
            int cap);

    /** Removes one shortcut; false when it was not there. */
    boolean remove(String tenantId, String subject, String kind, String href);
}
