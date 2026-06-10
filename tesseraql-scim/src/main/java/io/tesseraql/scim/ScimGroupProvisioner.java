package io.tesseraql.scim;

import java.util.List;

/**
 * Orchestrates outbound SCIM provisioning of a local group to a downstream provider (design ch. 10.15):
 * create-or-replace keyed by the {@link ScimResourceMapping}, deprovision (delete) on removal, and
 * bidirectional member synchronization. Mapping keys are namespaced with a {@code group:} prefix so
 * groups and users can share one mapping table without colliding on equal ids.
 */
public final class ScimGroupProvisioner {

    private static final String KEY_PREFIX = "group:";

    private final ScimOutboundClient client;
    private final ScimResourceMapping mapping;

    public ScimGroupProvisioner(ScimOutboundClient client, ScimResourceMapping mapping) {
        this.client = client;
        this.mapping = mapping;
    }

    /**
     * Provisions {@code group} for the given local id: replaces the existing remote group (carrying its
     * full member list, so members are reconciled in both directions) when it has been provisioned
     * before, otherwise creates it and records the remote id.
     */
    public ScimGroup provision(String localId, ScimGroup group) {
        return mapping.remoteId(key(localId))
                .map(remoteId -> client.replaceGroup(remoteId, group))
                .orElseGet(() -> {
                    ScimGroup created = client.createGroup(group);
                    mapping.put(key(localId), created.id());
                    return created;
                });
    }

    /**
     * Synchronizes the remote group's membership in both directions: adds {@code toAdd} and removes
     * {@code toRemove}. The group must already be provisioned.
     */
    public void syncMembers(String localId, List<String> toAdd, List<String> toRemove) {
        String remoteId = mapping.remoteId(key(localId)).orElseThrow(() -> new ScimException(
                409, null, "Group not provisioned, cannot sync members: " + localId));
        client.patchGroupMembers(remoteId, toAdd, toRemove);
    }

    /** Deprovisions the local group from the provider (delete) and drops its mapping; a no-op if unmapped. */
    public void deprovision(String localId) {
        mapping.remoteId(key(localId)).ifPresent(remoteId -> {
            client.deleteGroup(remoteId);
            mapping.remove(key(localId));
        });
    }

    private static String key(String localId) {
        return KEY_PREFIX + localId;
    }
}
