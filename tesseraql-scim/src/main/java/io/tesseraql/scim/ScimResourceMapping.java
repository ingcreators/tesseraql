package io.tesseraql.scim;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps a local user id to the resource id assigned by a downstream SCIM provider (design ch. 10.15),
 * so updates and deletes target the correct remote resource. This is the SPI; a JDBC-backed
 * implementation can persist the mapping table.
 */
public interface ScimResourceMapping {

    /** The remote SCIM resource id for {@code localId}, if this user has been provisioned. */
    Optional<String> remoteId(String localId);

    /** Records the remote resource id assigned to {@code localId}. */
    void put(String localId, String remoteId);

    /** Removes the mapping for {@code localId} (after deprovisioning). */
    void remove(String localId);

    /** A simple in-memory mapping, useful for a single instance and tests. */
    final class InMemory implements ScimResourceMapping {
        private final ConcurrentHashMap<String, String> byLocalId = new ConcurrentHashMap<>();

        @Override
        public Optional<String> remoteId(String localId) {
            return Optional.ofNullable(byLocalId.get(localId));
        }

        @Override
        public void put(String localId, String remoteId) {
            byLocalId.put(localId, remoteId);
        }

        @Override
        public void remove(String localId) {
            byLocalId.remove(localId);
        }
    }
}
