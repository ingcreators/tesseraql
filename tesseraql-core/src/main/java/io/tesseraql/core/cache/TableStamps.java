package io.tesseraql.core.cache;

import java.util.Collection;

/**
 * A version per source table, shared by every runtime of an application (docs/lookups.md
 * decision 14, docs/caching.md decision 5): a write that names its tables raises their
 * versions, and a hold — a code catalog's or a held source's — compares the version it loaded
 * under with the current one to learn that another node's write made it stale.
 *
 * <p>The implementation is a database row per table on the main connector, read at most once
 * per interval for every table at once ({@code TableVersions} in the operations module). The
 * stamp is an optimization, never the guarantee: a reader that cannot reach it falls back to
 * its own TTL, and a bump that fails is logged, never the write's failure.
 */
public interface TableStamps {

    /**
     * The highest version among {@code tables}, or {@code 0} when none of them has been
     * written through a declaring command yet (or when stamping is unavailable).
     */
    long versionOf(Collection<String> tables);

    /** Raises the version of each of {@code tables} this runtime stamps; unknown ones are skipped. */
    void bump(Collection<String> tables);

    /** No stamps at all: every hold expires on its TTL alone. */
    TableStamps NONE = new TableStamps() {
        @Override
        public long versionOf(Collection<String> tables) {
            return 0L;
        }

        @Override
        public void bump(Collection<String> tables) {
            // nothing to raise
        }
    };
}
