package io.tesseraql.core.events;

/**
 * The live-view topic bus (docs/realtime.md): a command route that declares {@code emit:}
 * broadcasts its topics here after a successful commit, and every open topic stream of the
 * same tenant is signalled. The signal carries the topic name only — never data — so a
 * subscriber refetches what it is looking at through the ordinary, fully-authorized route.
 *
 * <p>Signals are per-node and best-effort by design (the per-node stance of
 * docs/deployment.md): on a multi-node deployment, viewers connected to another node
 * converge on their next reload or poll. Reliable delivery is what the outbox is for.
 */
public interface TopicBus {

    /** Signals the tenant's open subscriptions of {@code topic}; a no-op when none exist. */
    void emit(String tenantId, String topic);
}
