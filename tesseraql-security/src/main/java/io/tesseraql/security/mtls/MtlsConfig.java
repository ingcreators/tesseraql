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
     * One mTLS client and the principal its certificate authenticates as. Exactly one matcher
     * ({@code subjectDn}, {@code san}, or {@code sha256}) identifies the certificate; the lint
     * rejects a client that declares none or more than one.
     *
     * @param subjectDn   exact subject distinguished name (compared in canonical RFC 2253 form)
     * @param san         a Subject Alternative Name value (DNS, URI, email, or IP) the cert carries
     * @param sha256      hex SHA-256 fingerprint of the DER certificate (colons/whitespace optional)
     * @param subject     the principal subject; defaults to the client id
     * @param tenantId    the principal tenant, bound from the client (not the request)
     * @param roles       granted roles
     * @param permissions granted permissions
     * @param active      whether the client is usable; a disabled client never authenticates
     */
    public record MtlsClient(
            String subjectDn,
            String san,
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
