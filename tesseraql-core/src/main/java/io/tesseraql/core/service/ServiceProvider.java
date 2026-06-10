package io.tesseraql.core.service;

import java.util.Map;

/**
 * A named provider callable from a route's {@code sql.service} / {@code queries.<name>.service}
 * binding (design ch. 6.3, 47). Providers expose runtime state that is not reachable through SQL
 * (execution lanes, traces, file trees, ...) to yaml/template apps. Providers bound on query
 * routes must be side-effect free; command routes may bind providers performing runtime
 * administration (Studio drafts, route reload).
 *
 * <p>Implementations must return template-ready data: maps, lists and scalars only, with any
 * display formatting (status classes, indents, ISO timestamps) precomputed.
 */
@FunctionalInterface
public interface ServiceProvider {

    /** Invokes the provider with the route-resolved parameters. */
    Object invoke(Map<String, Object> params);
}
