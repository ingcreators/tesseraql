package io.tesseraql.scim;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.core.outbox.OutboxEvent;
import io.tesseraql.core.outbox.OutboxEventSink;

/**
 * Delivers outbox events to a downstream SCIM provider for groups (design ch. 10.15, 39.2): a
 * {@code GROUP_PROVISIONED} event provisions (create-or-replace) the group carried in the payload —
 * the replace carries the full member list, so membership is reconciled in both directions — and
 * {@code GROUP_DEPROVISIONED} deletes it. Other event types are ignored, so this sink can be composed
 * with others on the same outbox. Throwing propagates a delivery failure for at-least-once retry.
 */
public final class ScimGroupOutboundSink implements OutboxEventSink {

    public static final String PROVISION = "GROUP_PROVISIONED";
    public static final String DEPROVISION = "GROUP_DEPROVISIONED";

    private final ScimGroupProvisioner provisioner;
    private final ObjectMapper mapper = new ObjectMapper();

    public ScimGroupOutboundSink(ScimGroupProvisioner provisioner) {
        this.provisioner = provisioner;
    }

    @Override
    public void send(OutboxEvent event) throws Exception {
        switch (event.eventType()) {
            case PROVISION ->
                    provisioner.provision(event.aggregateId(),
                            mapper.readValue(event.payloadJson(), ScimGroup.class));
            case DEPROVISION -> provisioner.deprovision(event.aggregateId());
            default -> {
                // Not a SCIM group provisioning event; leave it for other sinks.
            }
        }
    }
}
