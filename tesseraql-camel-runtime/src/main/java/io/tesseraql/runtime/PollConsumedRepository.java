package io.tesseraql.runtime;

import io.tesseraql.operations.poll.JdbcPollConsumedStore;
import org.apache.camel.spi.IdempotentRepository;
import org.apache.camel.support.service.ServiceSupport;

/**
 * Adapts {@link JdbcPollConsumedStore} to Camel's {@link IdempotentRepository}, so a file consumer
 * arbitrates across replicas through TesseraQL's own table (docs/audit-hardening.md Decision 4).
 *
 * <p>This is the whole Camel surface of the feature: five methods of an SPI that is already on the
 * classpath, with a TesseraQL store behind it. The YAML says {@code consumeOnce: true} and never
 * names a Camel concept.
 *
 * <p><b>{@code add} claims rather than remembers</b>, which is the point. The consumer is wired
 * with {@code idempotentEager=true}, so Camel calls {@code add} before the exchange runs and
 * rejects a false return. The lazy default instead calls {@code contains} first and adds on
 * completion, which is check-then-act: two replicas can both pass {@code contains} and both import
 * the file. Specifying the idempotent flag without the eagerness would have shipped the same defect
 * one option over.
 */
final class PollConsumedRepository extends ServiceSupport implements IdempotentRepository {

    private final JdbcPollConsumedStore store;
    private final String sourceId;

    PollConsumedRepository(JdbcPollConsumedStore store, String sourceId) {
        this.store = store;
        this.sourceId = sourceId;
    }

    @Override
    public boolean add(String key) {
        return store.claim(sourceId, key);
    }

    @Override
    public boolean contains(String key) {
        return store.claimed(sourceId, key);
    }

    @Override
    public boolean remove(String key) {
        return store.release(sourceId, key);
    }

    /**
     * Confirms a claim the exchange completed.
     *
     * <p>Nothing to do: the eager {@code add} already wrote the row, and there is no pending state
     * to promote. Returning true rather than false matters — Camel logs a warning for an
     * unconfirmed key, which would be one per imported file.
     */
    @Override
    public boolean confirm(String key) {
        return true;
    }

    @Override
    public void clear() {
        store.clear(sourceId);
    }
}
