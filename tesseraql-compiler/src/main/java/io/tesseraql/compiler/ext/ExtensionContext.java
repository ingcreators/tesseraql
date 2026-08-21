package io.tesseraql.compiler.ext;

import io.tesseraql.pipeline.RuntimeContext;
import io.tesseraql.yaml.manifest.AppManifest;
import javax.sql.DataSource;

/**
 * What a {@link RuntimeExtension} sees of the runtime being assembled: the runtime context (add
 * routes, bind/look up registry beans), the loaded app manifest (configuration and app home), and
 * the main datasource. Framework beans bound earlier (session store, identity service, realm, ...)
 * are reachable through the registry via {@link #bean}.
 *
 * @param runtime    the runtime context being assembled (not yet started)
 * @param manifest   the main app manifest
 * @param dataSource the main datasource
 */
public record ExtensionContext(RuntimeContext runtime, AppManifest manifest, DataSource dataSource,
        DataSource frameworkDataSource) {

    /**
     * Ambient framework state (docs/framework-datasource.md) constructs against this;
     * business and identity data stays on {@link #dataSource()}. Same pool unless
     * {@code tesseraql.framework.datasource} says otherwise.
     */
    @Override
    public DataSource frameworkDataSource() {
        return frameworkDataSource == null ? dataSource : frameworkDataSource;
    }

    /** Looks up a framework bean by name, or null when absent. */
    public <T> T bean(String name, Class<T> type) {
        return runtime.lookup(name, type);
    }

    /** Binds a bean into the runtime's registry under {@code name}. */
    public void bind(String name, Object bean) {
        runtime.bind(name, bean);
    }
}
