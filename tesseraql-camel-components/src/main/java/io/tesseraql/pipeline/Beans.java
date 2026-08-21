package io.tesseraql.pipeline;

/**
 * What the runtime bound, looked up by name (docs/camel-removal.md structural decision 2).
 *
 * <p>Sixty-three of the sixty-four calls a step made on the Camel context were this lookup, so
 * this is the whole of what a step needs from the runtime it is running in. It stays an interface
 * because the implementation is still Camel's registry until the context itself goes.
 */
@FunctionalInterface
public interface Beans {

    /** The bean bound under {@code name}, or null when nothing is. */
    <T> T lookup(String name, Class<T> type);

    /** Whether anything is bound under {@code name}, for the checks that only ask that. */
    default Object lookup(String name) {
        return lookup(name, Object.class);
    }
}
