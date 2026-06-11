package io.tesseraql.yaml.apps;

import io.tesseraql.yaml.config.AppConfig;
import java.util.List;

/**
 * A plugin hook contributing {@link AppSource}s, discovered via {@link java.util.ServiceLoader}
 * (design ch. 32, 47). Feature modules (operations console, Studio, IAM admin, SCIM, SAML) register
 * an implementation in {@code META-INF/services} so their bundled apps mount automatically when the
 * jar is on the classpath; users opt out per app with {@code tesseraql.apps.<name>.enabled: false}.
 */
public interface AppSourceProvider {

    /** The app sources this module contributes, given the main app's configuration. */
    List<AppSource> appSources(AppConfig config);
}
