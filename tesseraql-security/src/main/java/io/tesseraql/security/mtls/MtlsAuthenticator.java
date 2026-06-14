package io.tesseraql.security.mtls;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.security.Principal;
import io.tesseraql.security.mtls.MtlsConfig.MtlsClient;
import io.tesseraql.security.policy.PolicyEngine;
import java.io.ByteArrayInputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateFactory;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import javax.security.auth.x500.X500Principal;

/**
 * Authenticates a service caller by the X.509 client certificate a trusted TLS-terminating edge
 * forwards in a header (design ch. 11.1, roadmap Phase 25). The certificate is parsed, its validity
 * window checked, optionally PKIX-validated against a configured CA trust bundle, and its identity
 * (subject DN, a SAN value, or its DER SHA-256 fingerprint) matched against the declared clients. A
 * match resolves to that client's principal so existing authorization policies apply; anything else
 * denies (deny-by-default). The certificate is never logged.
 *
 * <p>Unlike an API key, a client certificate is <em>public</em> — possession of the private key was
 * proven during the TLS handshake at the edge — so identity matching is a plain lookup, not a
 * constant-time secret comparison. The cryptographic guarantee is the edge's handshake plus, when
 * configured, the runtime's own PKIX check; the forwarded header is trustworthy only because the
 * edge sets it and the runtime is not directly reachable.
 */
public final class MtlsAuthenticator {

    private final String header;
    private final Duration clockSkew;
    private final List<Entry> entries;
    private final Set<TrustAnchor> trustAnchors;

    /** A declared client's certificate matcher and the principal it grants. */
    private record Entry(Matcher matcher, Principal principal) {
    }

    private enum MatchKind {
        SUBJECT_DN, SAN, SHA256
    }

    /** One certificate-identity predicate built from a client's single declared matcher. */
    private record Matcher(MatchKind kind, String value) {
    }

    public MtlsAuthenticator(MtlsConfig config) {
        this.header = config.forwardedHeader();
        this.clockSkew = config.clockSkew();
        this.trustAnchors = parseTrustAnchors(config.trustBundle());
        List<Entry> active = new ArrayList<>();
        config.clients().forEach((id, client) -> {
            if (client.active()) {
                Matcher matcher = matcherOf(client);
                if (matcher != null) {
                    active.add(new Entry(matcher, principalOf(id, client)));
                }
            }
        });
        this.entries = List.copyOf(active);
    }

    /** The request header carrying the forwarded client certificate. */
    public String header() {
        return header;
    }

    /** Authenticates a forwarded client certificate, or throws {@code TQL-SEC-4011}. */
    public Principal authenticate(String forwardedCertificate) {
        if (forwardedCertificate == null || forwardedCertificate.isBlank()) {
            throw new TqlException(PolicyEngine.UNAUTHORIZED, "Missing client certificate");
        }
        List<X509Certificate> chain = parse(forwardedCertificate);
        X509Certificate leaf = chain.get(0);
        checkValidity(leaf);
        if (!trustAnchors.isEmpty()) {
            verifyChain(chain);
        }
        Principal match = matchIdentity(leaf);
        if (match == null) {
            throw new TqlException(PolicyEngine.UNAUTHORIZED, "Client certificate not recognized");
        }
        return match;
    }

    private static List<X509Certificate> parse(String value) {
        // nginx/ingress forward URL-encoded PEM (ssl_client_escaped_cert); a raw PEM still carries
        // the literal "BEGIN CERTIFICATE" (with a space), which URL-encoding would have escaped.
        String pem = value.contains("BEGIN CERTIFICATE")
                ? value
                : URLDecoder.decode(value, StandardCharsets.UTF_8);
        try {
            Collection<? extends java.security.cert.Certificate> certs = CertificateFactory
                    .getInstance("X.509")
                    .generateCertificates(
                            new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)));
            List<X509Certificate> chain = new ArrayList<>(certs.size());
            for (java.security.cert.Certificate cert : certs) {
                if (cert instanceof X509Certificate x509) {
                    chain.add(x509);
                }
            }
            if (chain.isEmpty()) {
                throw new TqlException(PolicyEngine.UNAUTHORIZED, "Invalid client certificate");
            }
            return chain;
        } catch (java.security.cert.CertificateException ex) {
            throw new TqlException(PolicyEngine.UNAUTHORIZED, "Invalid client certificate");
        }
    }

    private void checkValidity(X509Certificate leaf) {
        Instant now = Instant.now();
        try {
            leaf.checkValidity(Date.from(now.plus(clockSkew)));
            leaf.checkValidity(Date.from(now.minus(clockSkew)));
        } catch (CertificateExpiredException | CertificateNotYetValidException ex) {
            throw new TqlException(PolicyEngine.UNAUTHORIZED, "Client certificate is not valid");
        }
    }

    private void verifyChain(List<X509Certificate> chain) {
        // The forwarded header normally carries only the leaf; any anchor that travels with it is
        // dropped so the path terminates at, rather than includes, a trust anchor.
        Set<X500Principal> anchorSubjects = new LinkedHashSet<>();
        trustAnchors.forEach(
                anchor -> anchorSubjects.add(anchor.getTrustedCert().getSubjectX500Principal()));
        List<X509Certificate> path = new ArrayList<>();
        for (X509Certificate cert : chain) {
            if (!anchorSubjects.contains(cert.getSubjectX500Principal())) {
                path.add(cert);
            }
        }
        if (path.isEmpty()) {
            throw new TqlException(PolicyEngine.UNAUTHORIZED, "Client certificate is not trusted");
        }
        try {
            CertPath certPath = CertificateFactory.getInstance("X.509").generateCertPath(path);
            PKIXParameters params = new PKIXParameters(trustAnchors);
            // Mesh/edge CAs typically publish no CRL/OCSP; revocation is the terminator's job.
            params.setRevocationEnabled(false);
            CertPathValidator.getInstance("PKIX").validate(certPath, params);
        } catch (java.security.GeneralSecurityException ex) {
            throw new TqlException(PolicyEngine.UNAUTHORIZED, "Client certificate is not trusted");
        }
    }

    private Principal matchIdentity(X509Certificate leaf) {
        String subjectDn = canonicalDn(
                leaf.getSubjectX500Principal().getName(X500Principal.RFC2253));
        Set<String> sans = subjectAltNames(leaf);
        String fingerprint = sha256Hex(leaf);
        for (Entry entry : entries) {
            Matcher matcher = entry.matcher();
            boolean matched = switch (matcher.kind()) {
                case SUBJECT_DN -> matcher.value().equals(subjectDn);
                case SAN -> sans.contains(matcher.value());
                case SHA256 -> matcher.value().equals(fingerprint);
            };
            if (matched) {
                return entry.principal();
            }
        }
        return null;
    }

    private static Matcher matcherOf(MtlsClient client) {
        if (notBlank(client.subjectDn())) {
            return new Matcher(MatchKind.SUBJECT_DN, canonicalDn(client.subjectDn().trim()));
        }
        if (notBlank(client.san())) {
            return new Matcher(MatchKind.SAN, client.san().trim());
        }
        if (notBlank(client.sha256())) {
            return new Matcher(MatchKind.SHA256, normalizeHex(client.sha256()));
        }
        return null;
    }

    private static Principal principalOf(String id, MtlsClient client) {
        String subject = notBlank(client.subject()) ? client.subject() : id;
        return new Principal(subject, id, null, client.tenantId(),
                List.of(), client.roles(), client.permissions(),
                Map.of("mtls_client", id));
    }

    private static Set<String> subjectAltNames(X509Certificate leaf) {
        Set<String> names = new LinkedHashSet<>();
        try {
            Collection<List<?>> sans = leaf.getSubjectAlternativeNames();
            if (sans != null) {
                for (List<?> san : sans) {
                    if (san.size() >= 2 && san.get(1) instanceof String value) {
                        names.add(value);
                    }
                }
            }
        } catch (java.security.cert.CertificateParsingException ex) {
            // A malformed SAN extension contributes no names; identity falls back to DN/fingerprint.
        }
        return names;
    }

    private static Set<TrustAnchor> parseTrustAnchors(String trustBundle) {
        if (!notBlank(trustBundle)) {
            return Set.of();
        }
        try {
            Collection<? extends java.security.cert.Certificate> certs = CertificateFactory
                    .getInstance("X.509")
                    .generateCertificates(new ByteArrayInputStream(
                            trustBundle.getBytes(StandardCharsets.UTF_8)));
            Set<TrustAnchor> anchors = new LinkedHashSet<>();
            for (java.security.cert.Certificate cert : certs) {
                if (cert instanceof X509Certificate x509) {
                    anchors.add(new TrustAnchor(x509, null));
                }
            }
            if (anchors.isEmpty()) {
                throw new IllegalArgumentException("mTLS trustBundle contains no certificates");
            }
            return Set.copyOf(anchors);
        } catch (java.security.cert.CertificateException ex) {
            throw new IllegalArgumentException("mTLS trustBundle is not valid PEM", ex);
        }
    }

    private static String sha256Hex(X509Certificate cert) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException
                | java.security.cert.CertificateEncodingException ex) {
            throw new IllegalStateException("SHA-256 of certificate unavailable", ex);
        }
    }

    private static String normalizeHex(String value) {
        return value.replace(":", "").replaceAll("\\s", "").toLowerCase(Locale.ROOT);
    }

    /**
     * Canonicalizes a distinguished name to an order- and case-insensitive set of RDNs, so a
     * configured DN matches the certificate's subject regardless of how the issuing CA orders or
     * cases the relative distinguished names (a frequent source of subtle mismatches).
     */
    private static String canonicalDn(String dn) {
        try {
            List<String> rdns = new ArrayList<>();
            for (Rdn rdn : new LdapName(dn).getRdns()) {
                rdns.add(rdn.getType().toLowerCase(Locale.ROOT) + "="
                        + String.valueOf(rdn.getValue()).trim().toLowerCase(Locale.ROOT));
            }
            Collections.sort(rdns);
            return String.join(",", rdns);
        } catch (InvalidNameException ex) {
            return dn.trim().toLowerCase(Locale.ROOT);
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
