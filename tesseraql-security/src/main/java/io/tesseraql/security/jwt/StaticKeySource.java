package io.tesseraql.security.jwt;

import java.security.interfaces.RSAPublicKey;

/**
 * A {@link KeySource} backed by one configured RSA public key. The token {@code kid} is ignored: an
 * app that pins a single key trusts it regardless of the header (design ch. 11.1).
 */
public final class StaticKeySource implements KeySource {

    private final RSAPublicKey key;

    public StaticKeySource(RSAPublicKey key) {
        this.key = key;
    }

    @Override
    public RSAPublicKey resolve(String kid) {
        return key;
    }
}
