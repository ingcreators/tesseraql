package io.tesseraql.oidc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.function.Function;

/**
 * Resolves and caches the OpenID Provider {@link OidcMetadata} from its discovery document
 * (roadmap Phase 25). Discovery is lazy and memoized: a deliberate divergence from the SAML module,
 * which reads a local key file at startup — fetching a remote OP eagerly would couple app boot to
 * the provider's availability. The document fetch is a {@link Function} seam so tests run without a
 * network; production passes {@link OidcHttp#get}.
 */
public final class OidcDiscovery {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final URI discoveryUri;
    private final Function<URI, byte[]> fetcher;
    private volatile OidcMetadata cached;

    public OidcDiscovery(URI discoveryUri, Function<URI, byte[]> fetcher) {
        this.discoveryUri = discoveryUri;
        this.fetcher = fetcher;
    }

    /** Discovery over the JDK HTTP client. */
    public static OidcDiscovery overHttp(String discoveryUri, OidcHttp http) {
        return new OidcDiscovery(URI.create(discoveryUri), http::get);
    }

    /** The provider metadata, fetched once and reused. */
    public OidcMetadata metadata() {
        OidcMetadata local = cached;
        if (local == null) {
            synchronized (this) {
                if (cached == null) {
                    cached = fetch();
                }
                local = cached;
            }
        }
        return local;
    }

    private OidcMetadata fetch() {
        try {
            JsonNode node = MAPPER.readTree(fetcher.apply(discoveryUri));
            return new OidcMetadata(
                    requireText(node, "issuer"),
                    requireUri(node, "authorization_endpoint"),
                    requireUri(node, "token_endpoint"),
                    requireUri(node, "jwks_uri"),
                    optionalUri(node, "end_session_endpoint"));
        } catch (OidcException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new OidcException("OIDC discovery document is malformed: " + ex.getMessage());
        }
    }

    private static String requireText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            throw new OidcException("OIDC discovery document missing '" + field + "'");
        }
        return value.asText();
    }

    private static URI requireUri(JsonNode node, String field) {
        return URI.create(requireText(node, field));
    }

    private static URI optionalUri(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || value.asText().isBlank()
                ? null
                : URI.create(value.asText());
    }
}
