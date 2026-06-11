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
        // The pinned IdP signing key comes from IdP metadata when configured, else a key/cert file.
        java.security.PublicKey idpKey = config.getString("tesseraql.saml.idp.metadata")
                .map(path -> IdpMetadata.signingKey(readBytes(manifest, path)))
                .orElseGet(() -> SamlKeys.publicKey(
                        readBytes(manifest, config.requireString("tesseraql.saml.idp.publicKey"))));
        SamlResponseValidator validator = new SamlResponseValidator(
                new SamlValidationConfig(audience, idpKey, recipient, null));
        SamlAttributeMapping mapping = new SamlAttributeMapping(
                config.getString("tesseraql.saml.attributes.loginId").orElse(null),
                config.getString("tesseraql.saml.attributes.displayName").orElse(null),
                config.getString("tesseraql.saml.attributes.email").orElse(null),
                config.getString("tesseraql.saml.attributes.roles").orElse(null),
                config.getString("tesseraql.saml.attributes.groups").orElse(null),
                config.getString("tesseraql.saml.attributes.tenant").orElse(null));
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
        SamlReplayGuard replayGuard = new SamlReplayGuard(context.dataSource());
        replayGuard.ensureSchema();
        java.security.PrivateKey spKey = config.getString("tesseraql.saml.sp.signingKey")
                .map(path -> SamlKeys.privateKey(readBytes(manifest, path)))
                .orElse(null);
        boolean allowIdpInitiated = config.getString("tesseraql.saml.allowIdpInitiated")
                .map(Boolean::parseBoolean).orElse(false);
        boolean requireSignedLogout = config.getString("tesseraql.saml.requireSignedLogout")
                .map(Boolean::parseBoolean).orElse(true);
        SamlAcsRouteBuilder.SamlSecurity security = new SamlAcsRouteBuilder.SamlSecurity(
                replayGuard, spKey, idpKey, allowIdpInitiated, requireSignedLogout);
        context.camel().addRoutes(new SamlAcsRouteBuilder(
                validator, mapping, sessions, linker, metadata, endpoints, security));
    }

    private static byte[] readBytes(AppManifest manifest, String relative) {
        try {
            return Files.readAllBytes(manifest.appHome().resolve(relative).normalize());
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot read SAML key material: " + relative, ex);
        }
    }
}
