package io.tesseraql.saml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import org.junit.jupiter.api.Test;

class IdpMetadataTest {

    /** A fixed self-signed test certificate (X.509 DER, base64). */
    private static final String CERT = "MIIDGzCCAgOgAwIBAgIUaqx2kaHliMER8us/H8pOYXTD5HcwDQYJKoZIhvcNAQELBQAwHTEbMBkGA1UEAww"
            + "SdGVzc2VyYXFsLXRlc3QtaWRwMB4XDTI2MDYxMDAyMjg1M1oXDTQ2MDYwNTAyMjg1M1owHTEbMBkGA1UEAww"
            + "SdGVzc2VyYXFsLXRlc3QtaWRwMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAq7cPz5X3OIGRxyo"
            + "haCSkB7c9u6/b5/VFi3RsY4XLCO0ZODUMmjB1CFXmfyZgFWnhJxUYNSLDDQanCFPCWcGybbwRK7bzQ4V2JRE"
            + "dypCaq5V2ZZ2sLmaPffHgjjeM7La0HyWuzGYqrzejNnu7ETowrD3zobvm7P872gCJPZchJETeuFZ9qWMDtGc"
            + "orEgI3fVDgZNjYZ8ddQyT62uBtjHeaysucAChdyStaQeDMatIpNGdA5eVSL79WJ1JoNvhhdEnECkK0jqcxvJ"
            + "Flhe0OZuAdBjVQGMHDzOQPvNr1MlgxAFKWBH2OYhyfChruSfJWcx+6QoFRf7SO/OmgwvRKk1c5QIDAQABo1M"
            + "wUTAdBgNVHQ4EFgQUQ4T30jhga6kxX+exigPjvHFVIUswHwYDVR0jBBgwFoAUQ4T30jhga6kxX+exigPjvHF"
            + "VIUswDwYDVR0TAQH/BAUwAwEB/zANBgkqhkiG9w0BAQsFAAOCAQEACtfYnTSijsE0wy/gTPMN0vfRPkQsgLv"
            + "mBHYMAD64YUhlpbwWFTdUJBNr8XIpk04gcD/wSMvuSpDHeSD6le6N7GMi/hmyvATrWyov56Jp9KztzWfRaU1"
            + "RLolkoIgRzPmHWoiDMsLvvM1nR7P7OaIpxTNLkTc8wJ4uDa8YE3Jdj0RLzyGZtkK7ewZS/wQnhDu2F6tm/gl"
            + "BRTNxzz0vuNAxjOP17K+9iq0ne0xaSS7HZXlAbVhFFVob4d5s5G5c1uW53JYnkAMUBSPcWjJLOX06MIKMOSC"
            + "iobhRn3u7jS5mlxRpL/+81Brek03uXnS/QcuqRUcfOnbtJguYSAw6TEluXg==";

    private static byte[] metadata(String use) {
        String useAttr = use == null ? "" : " use=\"" + use + "\"";
        return ("""
                <md:EntityDescriptor xmlns:md="urn:oasis:names:tc:SAML:2.0:metadata"
                                     entityID="https://idp.example.com">
                  <md:IDPSSODescriptor protocolSupportEnumeration="urn:oasis:names:tc:SAML:2.0:protocol">
                    <md:KeyDescriptor%s>
                      <ds:KeyInfo xmlns:ds="http://www.w3.org/2000/09/xmldsig#">
                        <ds:X509Data><ds:X509Certificate>%s</ds:X509Certificate></ds:X509Data>
                      </ds:KeyInfo>
                    </md:KeyDescriptor>
                  </md:IDPSSODescriptor>
                </md:EntityDescriptor>
                """
                .formatted(useAttr, CERT)).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void extractsSigningKey() {
        PublicKey key = IdpMetadata.signingKey(metadata("signing"));
        assertThat(key.getAlgorithm()).isEqualTo("RSA");
    }

    @Test
    void acceptsKeyDescriptorWithoutUse() {
        assertThat(IdpMetadata.signingKey(metadata(null)).getAlgorithm()).isEqualTo("RSA");
    }

    @Test
    void rejectsMetadataWithoutCertificate() {
        byte[] empty = ("<md:EntityDescriptor xmlns:md=\"urn:oasis:names:tc:SAML:2.0:metadata\" "
                + "entityID=\"x\"><md:IDPSSODescriptor "
                + "protocolSupportEnumeration=\"urn:oasis:names:tc:SAML:2.0:protocol\"/>"
                + "</md:EntityDescriptor>").getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> IdpMetadata.signingKey(empty))
                .isInstanceOf(SamlException.class)
                .hasMessageContaining("no signing certificate");
    }
}
