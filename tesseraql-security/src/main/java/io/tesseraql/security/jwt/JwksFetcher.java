package io.tesseraql.security.jwt;

import java.net.URI;
import java.security.interfaces.RSAPublicKey;
import java.util.Map;

/**
 * Fetches a JWKS document and returns its RSA keys mapped by {@code kid} (design ch. 11.1). This is
 * the network seam behind {@link JwksKeySource}: production uses {@link HttpJwksFetcher}, while
 * tests inject a fake so JWKS caching and rotation are verified without a network.
 */
@FunctionalInterface
public interface JwksFetcher {

    /**
     * @return the JWKS keys by {@code kid} (a kid-less key under the empty string)
     * @throws io.tesseraql.core.error.TqlException on a transport or parse failure
     */
    Map<String, RSAPublicKey> fetch(URI jwksUri);
}
