package io.tesseraql.saml;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The validated, trusted contents of a SAML 2.0 assertion (design ch. 10.14, RFC/OASIS SAML Core
 * §2.3): the authenticated subject and its attributes. An instance only ever exists for an assertion
 * whose signature, conditions, and subject confirmation have all passed validation.
 *
 * @param nameId       the subject NameID value (the federated identifier)
 * @param nameIdFormat the NameID {@code Format} URI, if present
 * @param sessionIndex the authentication {@code SessionIndex}, if present (used for single logout)
 * @param attributes   attribute name to its (possibly multiple) values
 * @param notOnOrAfter the assertion's conditions {@code NotOnOrAfter}, if present
 */
public record SamlAssertion(String nameId, String nameIdFormat, String sessionIndex,
        Map<String, List<String>> attributes, Instant notOnOrAfter) {

    public SamlAssertion {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    /** The first value of an attribute, if present. */
    public Optional<String> attribute(String name) {
        List<String> values = attributes.get(name);
        return values == null || values.isEmpty() ? Optional.empty() : Optional.of(values.get(0));
    }
}
