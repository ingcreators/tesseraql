package io.tesseraql.pipeline;

/**
 * What the runtime bound, looked up by name (docs/camel-removal.md structural decision 2).
 *
 * <p>Sixty-three of the sixty-four calls a step made on the Camel context were this lookup, so
 * this is the whole of what a step needs from the runtime it is running in — which is why it is
 * an interface and not the {@link RuntimeContext}: a step that only reads bindings should not be
 * handed something startable and closeable.
 */
@FunctionalInterface
public interface Beans {

    /**
     * Nothing is bound.
     *
     * <p>For the exchanges a test builds to exercise a renderer or a binder. Standing a whole
     * {@link RuntimeContext} up for its empty registry answers every lookup the same way, and
     * leaves a closeable open to do it.
     */
    Beans NONE = new Beans() {
        @Override
        public <T> T lookup(String name, Class<T> type) {
            return null;
        }
    };

    /** The bean bound under {@code name}, or null when nothing is. */
    <T> T lookup(String name, Class<T> type);

    /** Whether anything is bound under {@code name}, for the checks that only ask that. */
    default Object lookup(String name) {
        return lookup(name, Object.class);
    }
}
