package io.tesseraql.saml.camel;

import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.compiler.ext.ExtensionContext;
import io.tesseraql.compiler.ext.RuntimeExtension;
import io.tesseraql.identity.IdentityService;
import io.tesseraql.identity.RealmConfig;
import io.tesseraql.saml.IdpMetadata;
import io.tesseraql.saml.SamlAttributeMapping;
import io.tesseraql.saml.SamlKeys;
import io.tesseraql.saml.SamlResponseValidator;
import io.tesseraql.saml.SamlValidationConfig;
import io.tesseraql.saml.SpMetadata;
import io.tesseraql.security.session.SessionStore;
import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.manifest.AppManifest;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Installs the SAML SP routes when the tesseraql-saml jar is on the classpath and
 * {@code tesseraql.saml.enabled} is true (design ch. 10.14, 47): the ACS endpoint, SP-initiated
 * login/logout redirects and SP metadata, validated against the pinned IdP signing key, with
 * optional link-or-provision of local users.
 */
public final class SamlRuntimeExtension implements RuntimeExtension {

    @Override
    public String name() {
        return "saml";
    }

    @Override
    public boolean enabled(AppConfig config) {
        return config.getString("tesseraql.saml.enabled").map(Boolean::parseBoolean).orElse(false);
    }

    @Override
    public void install(ExtensionContext context) throws Exception {
        AppManifest manifest = context.manifest();
        AppConfig config = manifest.config();
        SessionStore sessions = context.bean(TesseraqlProperties.SESSION_STORE_BEAN,
                SessionStore.class);
        IdentityService identity = context.bean(TesseraqlProperties.IDENTITY_SERVICE_BEAN,
                IdentityService.class);
        RealmConfig realm = context.bean(TesseraqlProperties.IDENTITY_REALM_BEAN,
                RealmConfig.class);

        String audience = config.requireString("tesseraql.saml.sp.audience");
        String recipient = config.getString("tesseraql.saml.sp.acsUrl").orElse(null);
        // The pinned IdP signing key comes from IdP metadata when configured — a file, or an
        // https URL fetched at boot under the egress allow-list — else a key/cert file.
        java.security.PublicKey idpKey = config.getString("tesseraql.saml.idp.metadata")
                .map(source -> IdpMetadata.signingKey(
                        SamlMetadataSource.load(manifest, config, source)))
                .orElseGet(() -> SamlKeys.publicKey(
                        readBytes(manifest, config.requireString("tesseraql.saml.idp.publicKey"))));
        // Allowed clock skew for the assertion's time-bound conditions; unset keeps the
        // 5-minute default (SamlValidationConfig).
        java.time.Duration clockSkew = config.getString("tesseraql.saml.clockSkew")
                .map(io.tesseraql.core.util.Durations::parse).orElse(null);
        SamlResponseValidator validator = new SamlResponseValidator(
                new SamlValidationConfig(audience, idpKey, recipient, clockSkew));
        SamlAttributeMapping mapping = new SamlAttributeMapping(
                config.getString("tesseraql.saml.attributes.loginId").orElse(null),
                config.getString("tesseraql.saml.attributes.displayName").orElse(null),
                config.getString("tesseraql.saml.attributes.email").orElse(null),
                config.getString("tesseraql.saml.attributes.roles").orElse(null),
                config.getString("tesseraql.saml.attributes.groups").orElse(null),
                config.getString("tesseraql.saml.attributes.tenant").orElse(null),
                config.getString("tesseraql.saml.link.subjectAttribute").orElse(null),
                attributeMap(config));
        // When link mode is on, resolve (and optionally provision) a local user so authorization
        // uses locally-managed roles instead of IdP-asserted ones (design ch. 10.14 userLink).
        boolean link = config.getString("tesseraql.saml.link.enabled")
                .map(Boolean::parseBoolean).orElse(false);
        SamlUserLinker linker = link
                ? new SamlUserLinker(identity, realm,
                        config.getString("tesseraql.saml.link.provision")
                                .map(Boolean::parseBoolean).orElse(false))
                : null;
        // Advertise SP metadata only when the ACS URL is known.
        SpMetadata metadata = recipient == null
                ? null
                : new SpMetadata(audience, recipient,
                        config.getString("tesseraql.saml.sp.nameIdFormat").orElse(null));
        SamlEndpoints endpoints = new SamlEndpoints(audience, recipient,
                config.getString("tesseraql.saml.idp.ssoUrl").orElse(null),
                config.getString("tesseraql.saml.idp.sloUrl").orElse(null));
        // Hardening (design ch. 10.14, 20): the JDBC replay guard enforces single-use
        // InResponseTo/RelayState and assertion-replay rejection across nodes; an SP signing key
        // signs HTTP-Redirect messages; inbound logout must be signed unless explicitly relaxed.
        // Replay state protects sessions, not business writes: ambient framework state
        // (docs/framework-datasource.md). SCIM provisioning stays on dataSource().
        SamlReplayGuard replayGuard = new SamlReplayGuard(context.frameworkDataSource());
        replayGuard.ensureSchema();
        java.security.PrivateKey spKey = config.getString("tesseraql.saml.sp.signingKey")
                .map(path -> SamlKeys.privateKey(readBytes(manifest, path)))
                .orElse(null);
        boolean allowIdpInitiated = config.getString("tesseraql.saml.allowIdpInitiated")
                .map(Boolean::parseBoolean).orElse(false);
        boolean requireSignedLogout = config.getBoolean("tesseraql.saml.requireSignedLogout", true);
        SamlAcsRouteBuilder.SamlSecurity security = new SamlAcsRouteBuilder.SamlSecurity(
                replayGuard, spKey, idpKey, allowIdpInitiated, requireSignedLogout);
        new SamlAcsRouteBuilder(
                validator, mapping, sessions, linker, metadata, endpoints, security,
                context.bean(TesseraqlProperties.CREDENTIAL_THROTTLE_BEAN,
                        io.tesseraql.security.throttle.CredentialThrottle.class))
                .install(context.camel());
    }

    /**
     * The declared attribute capture ({@code tesseraql.saml.attributes.map}): assertion attribute
     * name → store attribute name, re-synced at every linked login (docs/application-roles.md
     * structural decision 3). Unmapped assertion attributes stay discarded.
     */
    private static java.util.Map<String, String> attributeMap(AppConfig config) {
        java.util.Map<String, String> mapped = new java.util.LinkedHashMap<>();
        if (config
                .navigate("tesseraql.saml.attributes.map") instanceof java.util.Map<?, ?> entries) {
            entries.forEach((name, target) -> mapped.put(String.valueOf(name),
                    String.valueOf(target)));
        }
        return mapped;
    }

    private static byte[] readBytes(AppManifest manifest, String relative) {
        try {
            return Files.readAllBytes(manifest.appHome().resolve(relative).normalize());
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot read SAML key material: " + relative, ex);
        }
    }
}
