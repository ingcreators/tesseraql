package io.tesseraql.saml;

import java.util.Objects;

/**
 * Generates SP SAML 2.0 metadata (design ch. 10.14, OASIS SAML Metadata): an {@code EntityDescriptor}
 * with an {@code SPSSODescriptor} that advertises this SP's entity id and its HTTP-POST Assertion
 * Consumer Service, so an IdP can be configured to trust and target this SP.
 *
 * @param entityId     the SP entity id (matches the assertion audience)
 * @param acsUrl       the Assertion Consumer Service URL (HTTP-POST binding)
 * @param nameIdFormat the requested NameID format (defaults to {@code unspecified} when null)
 */
public record SpMetadata(String entityId, String acsUrl, String nameIdFormat) {

    private static final String MD_NS = "urn:oasis:names:tc:SAML:2.0:metadata";
    private static final String POST_BINDING = "urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST";
    private static final String DEFAULT_NAMEID = "urn:oasis:names:tc:SAML:2.0:nameid-format:unspecified";

    public SpMetadata {
        Objects.requireNonNull(entityId, "entityId");
        Objects.requireNonNull(acsUrl, "acsUrl");
        nameIdFormat = nameIdFormat == null ? DEFAULT_NAMEID : nameIdFormat;
    }

    /** The metadata as an XML document. */
    public String toXml() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <md:EntityDescriptor xmlns:md="%s" entityID="%s">
                  <md:SPSSODescriptor protocolSupportEnumeration="urn:oasis:names:tc:SAML:2.0:protocol"\
                 AuthnRequestsSigned="false" WantAssertionsSigned="true">
                    <md:NameIDFormat>%s</md:NameIDFormat>
                    <md:AssertionConsumerService Binding="%s" Location="%s" index="0" isDefault="true"/>
                  </md:SPSSODescriptor>
                </md:EntityDescriptor>
                """
                .formatted(MD_NS, escape(entityId), escape(nameIdFormat), POST_BINDING,
                        escape(acsUrl));
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
