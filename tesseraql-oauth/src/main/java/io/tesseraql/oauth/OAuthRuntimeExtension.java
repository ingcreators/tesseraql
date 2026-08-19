package io.tesseraql.oauth;

import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.compiler.ext.ExtensionContext;
import io.tesseraql.compiler.ext.RuntimeExtension;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.util.Durations;
import io.tesseraql.yaml.config.AppConfig;
import java.time.Clock;
import java.time.Duration;

/**
 * Installs the authorization server's surface when the stack file turns it on
 * (docs/token-issuance.md decision 8): {@code security.oauth.enabled} in
 * {@code tesseraql-stack.yml} grafts onto the stack surface runtime's config, which is the only
 * config that legitimately carries the key. Off by default — a component that issues
 * credentials should exist because somebody decided it should.
 *
 * <p>This slice serves the JWKS and owns the key lifecycle; {@code /authorize}, {@code /token}
 * and {@code /register} arrive with their own slices.
 */
public final class OAuthRuntimeExtension implements RuntimeExtension {

    /**
     * TQL-OAUTH-3000: the authorization server was enabled on a stack member. The issuer is a
     * stack-scoped surface; a member carrying {@code tesseraql.security.oauth.enabled} declared
     * it in its own application config, and the place to declare it is the stack file's
     * {@code security.oauth} block.
     */
    private static final TqlErrorCode MEMBER_SCOPE = new TqlErrorCode(TqlDomain.OAUTH, 3000);

    /** The {@link SigningKeys} bean, for the surfaces later slices mount. */
    public static final String SIGNING_KEYS_BEAN = "tesseraqlOAuthSigningKeys";

    /** The {@link AccessTokenSigner} bean the token slice mints through. */
    public static final String TOKEN_SIGNER_BEAN = "tesseraqlOAuthTokenSigner";

    @Override
    public String name() {
        return "oauth";
    }

    @Override
    public boolean enabled(AppConfig config) {
        return config.getString("tesseraql.security.oauth.enabled")
                .map(Boolean::parseBoolean).orElse(false);
    }

    @Override
    public void install(ExtensionContext context) throws Exception {
        if (context.bean(TesseraqlProperties.STACK_MEMBER_BEAN, String.class) != null) {
            throw new TqlException(MEMBER_SCOPE, "The authorization server is enabled on stack"
                    + " member '" + context.bean(TesseraqlProperties.STACK_MEMBER_BEAN,
                            String.class)
                    + "', but the issuer is the stack's — declare security.oauth.enabled in"
                    + " tesseraql-stack.yml, not in an application's config");
        }
        AppConfig config = context.manifest().config();
        Duration accessTokenLifetime = config
                .getString("tesseraql.security.oauth.accessTokenTtl")
                .map(Durations::parse)
                .orElse(TesseraqlOAuthDataProvider.DEFAULT_ACCESS_TOKEN_LIFETIME);

        SigningKeys keys = new SigningKeys(context.frameworkDataSource(), Clock.systemUTC());
        keys.ensureActive();
        context.bind(SIGNING_KEYS_BEAN, keys);
        context.bind(TOKEN_SIGNER_BEAN, new Rs256TokenSigner(keys));
        context.camel().addRoutes(new OAuthRouteBuilder(keys, accessTokenLifetime));
    }
}
