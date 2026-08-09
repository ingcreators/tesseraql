package io.tesseraql.security.mtls;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Mutual-TLS authentication settings for service callers (design ch. 11.1, roadmap Phase 25). TLS is
 * terminated at a trusted edge (reverse proxy, ingress, or service-mesh sidecar) that validates the
 * client certificate and forwards it to the runtime in a configured header (URL-encoded PEM, the
 * de-facto {@code ssl_client_escaped_cert} convention). Each declared client maps a certificate
 * identity to an explicit principal, so the same authorization policies apply as for any other
 * caller. Deny-by-default: a certificate that matches no declared client never authenticates.
 *
 * @param forwardedHeader the header the edge forwards the client certificate in (no default — the
 *                        credential source is opt-in; lint flags an mTLS config without one)
 * @param trustBundle     an optional PEM bundle of the CA certificate(s) that issue client
 *                        certificates; when set, the forwarded chain is PKIX-validated in the
 *                        runtime as defense-in-depth, in addition to the edge's own validation
 * @param clockSkew       leeway applied to the certificate's validity window (default zero)
 * @param clients         clients keyed by a stable client id
 */
public record MtlsConfig(
        String forwardedHeader,
        String trustBundle,
        Duration clockSkew,
        Map<String, MtlsClient> clients) {

    public MtlsConfig {
        clockSkew = clockSkew == null ? Duration.ZERO : clockSkew;
        clients = clients == null ? Map.of() : Map.copyOf(clients);
    }

    /**
     * The kind of Subject Alternative Name a {@link SanMatcher} compares, with the ASN.1
     * {@code GeneralName} tag {@code X509Certificate.getSubjectAlternativeNames()} reports it under.
     * A matcher only ever compares within its own kind: a certificate's {@code email} or {@code URI}
     * name can never satisfy a {@code sanDns} matcher, which is what makes the identity unambiguous.
     */
    public enum SanType {
        /** {@code sanDns} — a {@code dNSName} (tag 2); compared case-insensitively (RFC 4343). */
        DNS(2),
        /** {@code sanUri} — a {@code uniformResourceIdentifier} (tag 6), e.g. a SPIFFE ID. */
        URI(6),
        /** {@code sanEmail} — an {@code rfc822Name} (tag 1). */
        EMAIL(1),
        /** {@code sanIp} — an {@code iPAddress} (tag 7), in the JDK's textual form. */
        IP(7);

        private final int tag;

        SanType(int tag) {
            this.tag = tag;
        }

        /** The ASN.1 {@code GeneralName} tag this kind is encoded under. */
        public int tag() {
            return tag;
        }

        /** The kind for a {@code GeneralName} tag, or null for a kind no matcher can express. */
        public static SanType ofTag(int tag) {
            for (SanType type : values()) {
                if (type.tag == tag) {
                    return type;
                }
            }
            return null;
        }
    }

    /**
     * One type-qualified Subject Alternative Name matcher: the kind of name and the value it must
     * equal. Declared as exactly one of {@code sanDns}/{@code sanUri}/{@code sanEmail}/{@code sanIp}.
     *
     * @param type  the kind of Subject Alternative Name to compare
     * @param value the value the certificate's name of that kind must equal
     */
    public record SanMatcher(SanType type, String value) {
    }

    /**
     * One mTLS client and the principal its certificate authenticates as. Exactly one matcher
     * ({@code subjectDn}, a typed SAN, or {@code sha256}) identifies the certificate; the lint
     * rejects a client that declares none or more than one.
     *
     * @param subjectDn   exact subject distinguished name (compared in canonical RFC 2253 form)
     * @param san         a type-qualified Subject Alternative Name the certificate carries; only a
     *                    name of the same kind can satisfy it
     * @param sha256      hex SHA-256 fingerprint of the DER certificate (colons/whitespace optional)
     * @param subject     the principal subject; defaults to the client id
     * @param tenantId    the principal tenant, bound from the client (not the request)
     * @param roles       granted roles
     * @param permissions granted permissions
     * @param active      whether the client is usable; a disabled client never authenticates
     */
    public record MtlsClient(
            String subjectDn,
            SanMatcher san,
            String sha256,
            String subject,
            String tenantId,
            List<String> roles,
            List<String> permissions,
            boolean active) {

        public MtlsClient {
            roles = roles == null ? List.of() : List.copyOf(roles);
            permissions = permissions == null ? List.of() : List.copyOf(permissions);
        }
    }
}
