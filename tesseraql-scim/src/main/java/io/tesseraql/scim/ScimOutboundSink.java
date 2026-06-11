package io.tesseraql.scim;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.core.outbox.OutboxEvent;
import io.tesseraql.core.outbox.OutboxEventSink;

/**
 * Delivers outbox events to a downstream SCIM provider (design ch. 10.15, 39.2): a
 * {@code USER_PROVISIONED} event provisions (create-or-replace) the user carried in the payload, and
 * {@code USER_DEPROVISIONED} deletes it. Other event types are ignored, so this sink can be composed
 * with others on the same outbox. Throwing propagates a delivery failure for at-least-once retry.
 */
public final class ScimOutboundSink implements OutboxEventSink {

    public static final String PROVISION = "USER_PROVISIONED";
    public static final String DEPROVISION = "USER_DEPROVISIONED";

    private final ScimProvisioner provisioner;
    private final ObjectMapper mapper = new ObjectMapper();

    public ScimOutboundSink(ScimProvisioner provisioner) {
        this.provisioner = provisioner;
    }

    @Override
    public void send(OutboxEvent event) throws Exception {
        switch (event.eventType()) {
            case PROVISION ->
                provisioner.provision(event.aggregateId(),
                        mapper.readValue(event.payloadJson(), ScimUser.class));
            case DEPROVISION -> provisioner.deprovision(event.aggregateId());
            default -> {
                // Not a SCIM provisioning event; leave it for other sinks.
            }
        }
    }
}
