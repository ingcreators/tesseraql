package io.tesseraql.operations.webhook;

import io.tesseraql.core.dialect.SqlErrors;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.util.SqlScripts;
import io.tesseraql.core.webhook.WebhookReplayStore;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import javax.sql.DataSource;

/**
 * JDBC-backed inbound-webhook replay protection (roadmap Phase 26): a delivery id is inserted the
 * first time it is seen and rejected (a unique-key violation) on any replay until it expires, on
 * any node sharing the database — mirroring the SAML assertion replay cache. Expired ids are
 * pruned before each insert, so the table only retains ids for the configured tolerance window.
 */
public final class JdbcWebhookReplayStore implements WebhookReplayStore {

    /** TQL-BATCH-5311: the webhook replay store could not record a delivery. */
    private static final TqlErrorCode STORE_ERROR = new TqlErrorCode(TqlDomain.BATCH, 5311);

    private final DataSource dataSource;

    public JdbcWebhookReplayStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Creates the {@code tql_webhook_seen} table (per dialect) if it does not exist. */
    public void ensureSchema() {
        try {
            SqlScripts.applyForVendor(dataSource, JdbcWebhookReplayStore.class,
                    "/tesseraql/db/migration/webhook/V1__webhook_replay.sql");
        } catch (SQLException ex) {
            throw new TqlException(STORE_ERROR,
                    "Failed to create webhook replay schema: " + ex.getMessage(), ex);
        }
    }

    @Override
    public boolean markSeen(String deliveryId, Instant expiresAt) {
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement prune = connection.prepareStatement(
                    "delete from tql_webhook_seen where expires_at < ?")) {
                prune.setTimestamp(1, Timestamp.from(Instant.now()));
                prune.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "insert into tql_webhook_seen (delivery_id, expires_at) values (?, ?)")) {
                insert.setString(1, deliveryId);
                insert.setTimestamp(2, Timestamp.from(expiresAt));
                insert.executeUpdate();
                return true;
            }
        } catch (SQLException ex) {
            if (SqlErrors.isUniqueViolation(ex)) {
                return false;
            }
            throw new TqlException(STORE_ERROR,
                    "Failed to record webhook delivery: " + ex.getMessage(), ex);
        }
    }
}
