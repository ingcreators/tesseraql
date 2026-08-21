package io.tesseraql.camel;

import io.tesseraql.pipeline.Beans;
import org.apache.camel.CamelContext;

/**
 * The runtime's bound services, read out of the Camel registry that still holds them
 * (docs/camel-removal.md structural decision 2).
 *
 * <p>The last thing a step asked the Camel context for was a bean by name, so this is the seam the
 * context leaves through: a step sees {@link Beans}, and what is behind it stops being Camel's
 * registry in the slice that removes the context — without a step noticing.
 */
public final class CamelBeans {

    private CamelBeans() {
    }

    /** A view of {@code context}'s registry. */
    public static Beans of(CamelContext context) {
        return new Beans() {
            @Override
            public <T> T lookup(String name, Class<T> type) {
                return context == null
                        ? null
                        : context.getRegistry()
                                .lookupByNameAndType(name, type);
            }
        };
    }
}
