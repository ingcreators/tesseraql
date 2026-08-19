package io.tesseraql.oauth;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * The stack's signing keys in the framework datasource (docs/token-issuance.md decision 3):
 * every replica reads the same rows, so every replica serves one JWKS and signs with one key.
 * Keys are RSA-2048, stored base64 (PKCS#8 private, X.509 public), addressed by {@code kid}.
 *
 * <p><b>Exactly-once generation without a coordinator.</b> The first key's {@code kid} is the
 * constant {@code initial}, so of two concurrent first starts exactly one INSERT wins the
 * primary key and the loser re-reads the winner's pair — the same single-winner discipline the
 * refresh store uses. Rotation inserts the new key <em>before</em> retiring predecessors, so
 * the stack is never keyless, and retirement only touches keys older than the new one, so two
 * concurrent rotations leave two live keys rather than zero.
 *
 * <p>Rotation is overlapping and {@code kid}-addressed: a retired key stays published until
 * every access token it signed has expired, which is bounded by the access-token lifetime —
 * the bound that makes rotation affordable at all.
 */
public final class SigningKeys {

    /** The first key's reserved kid — the primary-key guard behind exactly-once generation. */
    public static final String INITIAL_KID = "initial";

    private static final SecureRandom RANDOM = new SecureRandom();

    /** A stored key pair; {@code retiredAt} null means it is a candidate signer. */
    public record SigningKey(String kid, String privateKey, String publicKey, Instant createdAt,
            Instant retiredAt) {
    }

    private final DataSource dataSource;
    private final Clock clock;

    public SigningKeys(DataSource dataSource, Clock clock) {
        this.dataSource = dataSource;
        this.clock = clock;
    }

    /**
     * The active signer, generated on first call if none exists. Concurrent first calls race
     * on the {@code initial} primary key; the loser reads the winner's key.
     */
    public SigningKey ensureActive() {
        Optional<SigningKey> active = active();
        if (active.isPresent()) {
            return active.get();
        }
        try {
            insert(generate(INITIAL_KID, clock.instant()));
        } catch (IllegalStateException raced) {
            // The other first start won the primary key; fall through to read its key.
        }
        return active().orElseThrow(() -> new IllegalStateException(
                "no active signing key, and the initial slot is already spent — rotate"));
    }

    /** The newest unretired key; ties (two concurrent rotations) break on kid, so every
     * replica picks the same signer. */
    public Optional<SigningKey> active() {
        return all().stream()
                .filter(key -> key.retiredAt() == null)
                .max(Comparator.comparing(SigningKey::createdAt)
                        .thenComparing(SigningKey::kid));
    }

    /**
     * Every key the JWKS publishes: the unretired ones, and retired ones whose signed access
     * tokens can still be alive — retirement plus the access-token lifetime is the horizon.
     */
    public List<SigningKey> published(Duration accessTokenLifetime) {
        Instant horizon = clock.instant().minus(accessTokenLifetime);
        return all().stream()
                .filter(key -> key.retiredAt() == null || key.retiredAt().isAfter(horizon))
                .toList();
    }

    /** Inserts a fresh key, then retires the strictly older unretired ones. */
    public SigningKey rotate() {
        SigningKey fresh = generate("k-" + HexFormat.of().formatHex(randomBytes(8)),
                clock.instant());
        insert(fresh);
        String sql = "update tql_oauth_signing_key set retired_at = ?"
                + " where retired_at is null and created_at < ? and kid <> ?";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement update = connection.prepareStatement(sql)) {
            update.setTimestamp(1, Timestamp.from(clock.instant()));
            update.setTimestamp(2, Timestamp.from(fresh.createdAt()));
            update.setString(3, fresh.kid());
            update.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Signing-key rotation failed", e);
        }
        return fresh;
    }

    public static RSAPrivateKey privateKey(SigningKey key) {
        try {
            return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(
                    new PKCS8EncodedKeySpec(Base64.getDecoder().decode(key.privateKey())));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Stored private key is unreadable", e);
        }
    }

    public static RSAPublicKey publicKey(SigningKey key) {
        try {
            return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(key.publicKey())));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Stored public key is unreadable", e);
        }
    }

    static SigningKey generate(String kid, Instant createdAt) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            Base64.Encoder base64 = Base64.getEncoder();
            return new SigningKey(kid,
                    base64.encodeToString(pair.getPrivate().getEncoded()),
                    base64.encodeToString(pair.getPublic().getEncoded()),
                    createdAt, null);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("RSA is a required JDK algorithm", e);
        }
    }

    private void insert(SigningKey key) {
        String sql = "insert into tql_oauth_signing_key (kid, private_key, public_key,"
                + " created_at, retired_at) values (?, ?, ?, ?, ?)";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement insert = connection.prepareStatement(sql)) {
            insert.setString(1, key.kid());
            insert.setString(2, key.privateKey());
            insert.setString(3, key.publicKey());
            insert.setTimestamp(4, Timestamp.from(key.createdAt()));
            insert.setTimestamp(5, null);
            insert.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Signing-key insert failed", e);
        }
    }

    private List<SigningKey> all() {
        String sql = "select kid, private_key, public_key, created_at, retired_at"
                + " from tql_oauth_signing_key";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement select = connection.prepareStatement(sql);
                ResultSet rs = select.executeQuery()) {
            List<SigningKey> keys = new ArrayList<>();
            while (rs.next()) {
                Timestamp retired = rs.getTimestamp("retired_at");
                keys.add(new SigningKey(
                        rs.getString("kid"),
                        rs.getString("private_key"),
                        rs.getString("public_key"),
                        rs.getTimestamp("created_at").toInstant(),
                        retired == null ? null : retired.toInstant()));
            }
            return keys;
        } catch (SQLException e) {
            throw new IllegalStateException("Signing-key listing failed", e);
        }
    }

    private static byte[] randomBytes(int count) {
        byte[] bytes = new byte[count];
        RANDOM.nextBytes(bytes);
        return bytes;
    }
}
