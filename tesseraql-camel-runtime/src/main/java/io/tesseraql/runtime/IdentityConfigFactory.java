package io.tesseraql.runtime;

import io.tesseraql.identity.Capabilities;
import io.tesseraql.identity.RealmConfig;
import io.tesseraql.yaml.config.AppConfig;
import java.nio.file.Path;

/**
 * Builds the default identity realm from {@code tesseraql.identity.*} (design ch. 10.2). Defaults to
 * a managed realm named {@code local} on the {@code main} datasource when nothing is configured.
 */
final class IdentityConfigFactory {

    private IdentityConfigFactory() {
    }

    static RealmConfig defaultRealm(AppConfig config, Path appHome) {
        String realmId = config.getString("tesseraql.identity.defaultRealm").orElse("local");
        String prefix = "tesseraql.identity.realms." + realmId + ".";
        String type = config.getString(prefix + "type").orElse("managed");
        String datasource = config.getString(prefix + "datasource").orElse("main");
        boolean managed = !"sql".equalsIgnoreCase(type);
        Capabilities capabilities = capabilities(config, prefix, managed);

        if (managed) {
            return RealmConfig.managed(realmId, datasource, capabilities);
        }
        Path sqlRoot = config.getString(prefix + "sqlRoot")
                .map(Path::of)
                .orElseGet(() -> appHome.resolve("security/identity/" + realmId));
        return RealmConfig.sql(realmId, datasource, sqlRoot, capabilities);
    }

    private static Capabilities capabilities(AppConfig config, String prefix, boolean managed) {
        String defaultLevel = managed ? Capabilities.READ_WRITE : Capabilities.READ_ONLY;
        return new Capabilities(
                config.getString(prefix + "capabilities.userManagement").orElse(defaultLevel),
                config.getString(prefix + "capabilities.groupManagement").orElse(defaultLevel),
                config.getString(prefix + "capabilities.roleManagement").orElse(defaultLevel));
    }
}
