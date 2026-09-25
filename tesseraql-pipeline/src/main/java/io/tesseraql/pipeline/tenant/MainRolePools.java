package io.tesseraql.pipeline.tenant;

import javax.sql.DataSource;

/**
 * The role pools {@code main} declares (docs/capacity-defaults.md decision 5), bound once per
 * runtime under {@link io.tesseraql.pipeline.TesseraqlProperties#MAIN_ROLE_POOLS_BEAN}.
 *
 * <p>Bound as one object rather than as more {@link DataSource} beans on purpose: a role pool is
 * not a datasource a route can name, and nothing that enumerates the registry's datasources should
 * find one.
 */
@FunctionalInterface
public interface MainRolePools {

    /** The pool {@code main} declares for {@code role}, or {@code null} when that work stays on it. */
    DataSource of(PoolRole role);
}
