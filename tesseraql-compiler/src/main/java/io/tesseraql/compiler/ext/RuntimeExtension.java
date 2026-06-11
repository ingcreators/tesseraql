package io.tesseraql.compiler.ext;

import io.tesseraql.yaml.config.AppConfig;

/**
 * A runtime plugin hook discovered via {@link java.util.ServiceLoader} (design ch. 47). Optional
 * feature modules (SCIM, SAML, ...) implement this to install their Camel routes and beans into the
 * starting runtime, so the runtime itself carries no compile-time dependency on them: putting the
 * feature jar on the classpath and enabling it in configuration is the whole install.
 */
public interface RuntimeExtension {

    /** The extension name, for diagnostics and logs. */
    String name();

    /** Whether the extension should install, given the main app's configuration. */
    boolean enabled(AppConfig config);

    /**
     * Installs the extension into the runtime being assembled. Called after the core beans
     * (datasources, security, session store, identity service/realm) are bound and before the
     * Camel context starts.
     */
    void install(ExtensionContext context) throws Exception;
}
