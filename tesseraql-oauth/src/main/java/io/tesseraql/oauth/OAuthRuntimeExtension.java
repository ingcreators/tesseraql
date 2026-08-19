package io.tesseraql.oauth;

import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.compiler.ext.ExtensionContext;
import io.tesseraql.compiler.ext.RuntimeExtension;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.util.Durations;
import io.tesseraql.security.session.SessionStore;
import io.tesseraql.yaml.config.AppConfig;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;

/**
 * Installs the authorization server's surface when the stack file turns it on
 * (docs/token-issuance.md decision 8): {@code security.oauth.enabled} in
 * {@code tesseraql-stack.yml} grafts onto the stack surface runtime's config, which is the only
 * config that legitimately carries the key. Off by default — a component that issues
 * credentials should exist because somebody decided it should.
 *
 * <p>Serves the JWKS and owns the key lifecycle everywhere it is enabled; on the stack surface
 * — where the member list and origin are bound — it also mounts {@code /authorize} and the
 * consent decision, and registers the consent page's model provider. {@code /token} and
 * {@code /register} arrive with their own slices.
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

    /** The {@link OAuthStore} bean — the grant storage later slices and pages read. */
    public static final String STORE_BEAN = "tesseraqlOAuthStore";

    /**
     * Bound by the surface runtime before extensions install: the stack's member addresses
     * (name to base path) and external origin, which the authorize endpoint resolves
     * resources against. Absent anywhere that is not the stack surface — and with them absent,
     * only the JWKS mounts.
     */
    public static final String MEMBER_ADDRESSES_BEAN = "tesseraqlOAuthMemberAddresses";
    public static final String EXTERNAL_ORIGIN_BEAN = "tesseraqlOAuthExternalOrigin";

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

        Duration refreshTokenLifetime = config
                .getString("tesseraql.security.oauth.refreshTokenTtl")
                .map(Durations::parse)
                .orElse(TesseraqlOAuthDataProvider.DEFAULT_REFRESH_TOKEN_LIFETIME);

        SigningKeys keys = new SigningKeys(context.frameworkDataSource(), Clock.systemUTC());
        keys.ensureActive();
        Rs256TokenSigner signer = new Rs256TokenSigner(keys);
        context.bind(SIGNING_KEYS_BEAN, keys);
        context.bind(TOKEN_SIGNER_BEAN, signer);

        OAuthStore store = new JdbcOAuthStore(context.frameworkDataSource());
        context.bind(STORE_BEAN, store);

        // The authorize surface exists exactly where the member list does — the stack surface
        // runtime, whose start binds the addresses and origin before extensions install.
        @SuppressWarnings("unchecked")
        Map<String, String> memberAddresses = context.bean(MEMBER_ADDRESSES_BEAN, Map.class);
        String externalOrigin = context.bean(EXTERNAL_ORIGIN_BEAN, String.class);
        AuthorizeFlow flow = null;
        SessionStore sessions = null;
        if (memberAddresses != null && externalOrigin != null) {
            TesseraqlOAuthDataProvider provider = new TesseraqlOAuthDataProvider(store, signer,
                    Clock.systemUTC(), accessTokenLifetime, refreshTokenLifetime);
            flow = new AuthorizeFlow(store, provider, memberAddresses, externalOrigin,
                    Clock.systemUTC());
            sessions = context.bean(TesseraqlProperties.SESSION_STORE_BEAN, SessionStore.class);
            registerConsentReview(context, flow);
        }
        context.camel().addRoutes(new OAuthRouteBuilder(keys, accessTokenLifetime, flow,
                sessions));
    }

    /** The consent page's model provider — the page re-validates what its query echoed. */
    private static void registerConsentReview(ExtensionContext context, AuthorizeFlow flow) {
        io.tesseraql.core.service.ServiceProviders services = context.bean(
                TesseraqlProperties.SERVICE_PROVIDERS_BEAN,
                io.tesseraql.core.service.ServiceProviders.class);
        services.register("oauth.consent.review", params -> {
            Map<String, String> query = new java.util.LinkedHashMap<>();
            for (String key : java.util.List.of("client_id", "redirect_uri", "state",
                    "code_challenge", "code_challenge_method", "resource")) {
                Object value = params.get(key);
                if (value != null && !String.valueOf(value).isBlank()) {
                    query.put(key, String.valueOf(value));
                }
            }
            AuthorizeFlow.Outcome outcome = flow.review(query, grants(params.get("roleGrants")));
            if (outcome.consent() != null) {
                return outcome.consent();
            }
            // A page cannot redirect and must not leak: any refusal — including one that
            // would be redirectable on the protocol path — renders as the page's error state.
            return Map.of("error", outcome.pageError() != null
                    ? outcome.pageError()
                    : "invalid_request");
        });
    }

    /** The ambient {@code principal.roleGrants} maps, as typed grants. */
    private static java.util.List<io.tesseraql.security.Principal.RoleGrant> grants(
            Object value) {
        java.util.List<io.tesseraql.security.Principal.RoleGrant> grants = new java.util.ArrayList<>();
        if (value instanceof java.util.List<?> list) {
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> map) {
                    Object permissions = map.get("permissions");
                    java.util.List<String> held = new java.util.ArrayList<>();
                    if (permissions instanceof java.util.List<?> values) {
                        values.forEach(permission -> held.add(String.valueOf(permission)));
                    }
                    grants.add(new io.tesseraql.security.Principal.RoleGrant(
                            String.valueOf(map.get("role")),
                            map.get("application") == null
                                    ? null
                                    : String.valueOf(map.get("application")),
                            held));
                }
            }
        }
        return grants;
    }
}
