package io.tesseraql.scim;

/**
 * Orchestrates outbound SCIM provisioning of a local user to a downstream provider (design ch. 10.15):
 * create-or-replace keyed by the {@link ScimResourceMapping}, and deprovision (delete) on removal.
 */
public final class ScimProvisioner {

    private final ScimOutboundClient client;
    private final ScimResourceMapping mapping;

    public ScimProvisioner(ScimOutboundClient client, ScimResourceMapping mapping) {
        this.client = client;
        this.mapping = mapping;
    }

    /**
     * Provisions {@code user} for the given local id: replaces the existing remote resource when the
     * user has been provisioned before, otherwise creates it and records the remote id.
     */
    public ScimUser provision(String localId, ScimUser user) {
        return mapping.remoteId(localId)
                .map(remoteId -> client.replace(remoteId, user))
                .orElseGet(() -> {
                    ScimUser created = client.create(user);
                    mapping.put(localId, created.id());
                    return created;
                });
    }

    /** Deprovisions the local user from the provider (delete) and drops its mapping; a no-op if unmapped. */
    public void deprovision(String localId) {
        mapping.remoteId(localId).ifPresent(remoteId -> {
            client.delete(remoteId);
            mapping.remove(localId);
        });
    }
}
