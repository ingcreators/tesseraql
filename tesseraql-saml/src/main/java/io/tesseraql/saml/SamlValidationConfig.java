package io.tesseraql.saml;

import java.security.PublicKey;
import java.time.Duration;
import java.util.Objects;

/**
 * The SP-side configuration for validating SAML responses (design ch. 10.14).
 *
 * <p>The IdP signing key is <em>pinned</em>: the validator trusts only {@code idpSigningKey} and
 * ignores any {@code KeyInfo} embedded in the message, so an attacker cannot present a self-chosen
 * certificate. Extracting this key from IdP metadata is a separate concern.
 *
 * @param audience      the SP entity id that must appear in the assertion's audience restriction
 * @param idpSigningKey the pinned IdP public key the signature is verified against
 * @param recipient     the expected {@code Recipient} (our ACS URL); when null, recipient is not checked
 * @param clockSkew     allowed clock skew for time-bound conditions (defaults to 5 minutes)
 */
public record SamlValidationConfig(String audience, PublicKey idpSigningKey, String recipient,
        Duration clockSkew) {

    public SamlValidationConfig {
        Objects.requireNonNull(audience, "audience");
        Objects.requireNonNull(idpSigningKey, "idpSigningKey");
        clockSkew = clockSkew == null ? Duration.ofMinutes(5) : clockSkew;
    }

    public SamlValidationConfig(String audience, PublicKey idpSigningKey) {
        this(audience, idpSigningKey, null, null);
    }
}
