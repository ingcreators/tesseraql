package io.tesseraql.compiler.ext;

import io.tesseraql.yaml.manifest.AppManifest;
import javax.sql.DataSource;
import org.apache.camel.CamelContext;

/**
 * What a {@link RuntimeExtension} sees of the runtime being assembled: the Camel context (add
 * routes, bind/look up registry beans), the loaded app manifest (configuration and app home), and
 * the main datasource. Framework beans bound earlier (session store, identity service, realm, ...)
 * are reachable through the registry via {@link #bean}.
 *
 * @param camel      the Camel context being assembled (not yet started)
 * @param manifest   the main app manifest
 * @param dataSource the main datasource
 */
public record ExtensionContext(CamelContext camel, AppManifest manifest, DataSource dataSource) {

    /** Looks up a framework bean by name, or null when absent. */
    public <T> T bean(String name, Class<T> type) {
        return camel.getRegistry().lookupByNameAndType(name, type);
    }

    /** Binds a bean into the Camel registry under {@code name}. */
    public void bind(String name, Object bean) {
        camel.getRegistry().bind(name, bean);
    }
}
