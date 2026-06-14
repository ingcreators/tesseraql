package io.tesseraql.oidc;

import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.compiler.ext.ExtensionContext;
import io.tesseraql.compiler.ext.RuntimeExtension;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.util.Durations;
import io.tesseraql.identity.IdentityService;
import io.tesseraql.identity.RealmConfig;
import io.tesseraql.security.session.SessionStore;
import io.tesseraql.yaml.config.AppConfig;
import java.time.Duration;

/**
 * Installs the OIDC relying-party routes when the tesseraql-oidc jar is on the classpath and
 * {@code tesseraql.oidc.enabled} is true (design ch. 10.14, 47, roadmap Phase 25): the
 * {@code /login} redirect, the {@code /callback} code exchange and session issue, and
 * {@code /logout}. The provider's endpoints are discovered lazily at first use, so app startup does
 * not depend on the OP being reachable.
 */
public final class OidcRuntimeExtension implements RuntimeExtension {

    private static final TqlErrorCode CONFIG = new TqlErrorCode(TqlDomain.OIDC, 3000);

    @Override
    public String name() {
        return "oidc";
    }

    @Override
    public boolean enabled(AppConfig config) {
        return config.getString("tesseraql.oidc.enabled").map(Boolean::parseBoolean).orElse(false);
    }

    @Override
    public void install(ExtensionContext context) throws Exception {
        AppConfig appConfig = context.manifest().config();
        OidcConfig config = OidcConfig.parse(appConfig);
        require(config.discoveryUri(), "tesseraql.oidc.discoveryUri");
        require(config.clientId(), "tesseraql.oidc.clientId");
        require(config.redirectUri(), "tesseraql.oidc.redirectUri");

        SessionStore sessions = context.bean(TesseraqlProperties.SESSION_STORE_BEAN,
                SessionStore.class);
        Duration timeout = appConfig.getString("tesseraql.oidc.requestTimeout")
                .map(Durations::parse).orElse(Duration.ofSeconds(5));
        OidcHttp http = new OidcHttp(timeout);
        OidcDiscovery discovery = OidcDiscovery.overHttp(config.discoveryUri(), http);
        OidcStateStore stateStore = new OidcStateStore(context.dataSource());
        stateStore.ensureSchema();

        OidcUserLinker linker = null;
        if (config.linkEnabled()) {
            IdentityService identity = context.bean(TesseraqlProperties.IDENTITY_SERVICE_BEAN,
                    IdentityService.class);
            RealmConfig realm = context.bean(TesseraqlProperties.IDENTITY_REALM_BEAN,
                    RealmConfig.class);
            linker = new OidcUserLinker(identity, realm, config.provision());
        }
        context.camel().addRoutes(
                new OidcRouteBuilder(config, discovery, stateStore, http, sessions, linker));
    }

    private static void require(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new TqlException(CONFIG, "OIDC is enabled but '" + key + "' is not configured");
        }
    }
}
