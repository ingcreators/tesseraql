package io.tesseraql.scim;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.outbox.TerminalDeliveryException;
import io.tesseraql.yaml.http.HttpOutbound;
import io.tesseraql.yaml.http.OutboundGateway;
import io.tesseraql.yaml.model.HttpCallSpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A client that provisions users and groups to a downstream SCIM provider over HTTP (design ch.
 * 10.15 outbound), through the runtime's {@link OutboundGateway}
 * (docs/duplication-consolidation.md, campaign 1): the provider's host must be in the egress
 * allow-list, and every call runs under the configured timeouts, the per-host circuit breaker,
 * and the {@code tesseraql.http.call} span. It used to build its own JDK client with <em>no
 * timeout at all</em> — a hung provider hung the provisioning thread indefinitely, the exact
 * defect class the gateway exists to close. Each call carries the target's bearer token and the
 * SCIM media type; non-success responses become {@link ScimException}s carrying the remote
 * status. A gateway refusal splits by what retrying can do about it: an open circuit or a
 * transport failure heals, so it becomes a 502 that keeps the outbox retrying, while a denied
 * host is configuration no retry can fix, so it becomes a {@link TerminalDeliveryException}
 * that dead-letters the event at once — the operator fixes the allow-list and redelivers.
 */
public final class ScimOutboundClient {

    private static final String SCIM_JSON = "application/scim+json";

    private final ScimTarget target;
    private final OutboundGateway gateway;
    private final ObjectMapper mapper = io.tesseraql.yaml.JsonMappers.constrained();

    public ScimOutboundClient(ScimTarget target, OutboundGateway gateway) {
        this.target = target;
        this.gateway = gateway;
    }

    /** Creates the user on the provider and returns the created resource (with its remote id). */
    public ScimUser create(ScimUser user) {
        return send("POST", target.usersUrl(), user, 201, 200);
    }

    /** Replaces the user with remote id {@code remoteId} on the provider. */
    public ScimUser replace(String remoteId, ScimUser user) {
        return send("PUT", target.usersUrl() + "/" + remoteId, user, 200);
    }

    /** Deletes the user with remote id {@code remoteId} (204 or 404 are both treated as gone). */
    public void delete(String remoteId) {
        deleteAt(target.usersUrl() + "/" + remoteId);
    }

    /** Creates the group on the provider and returns the created resource (with its remote id). */
    public ScimGroup createGroup(ScimGroup group) {
        return send("POST", target.groupsUrl(), group, ScimGroup.class, 201, 200);
    }

    /** Replaces the group with remote id {@code remoteId}, carrying its full member list. */
    public ScimGroup replaceGroup(String remoteId, ScimGroup group) {
        return send("PUT", target.groupsUrl() + "/" + remoteId, group, ScimGroup.class, 200);
    }

    /** Deletes the group with remote id {@code remoteId} (204 or 404 are both treated as gone). */
    public void deleteGroup(String remoteId) {
        deleteAt(target.groupsUrl() + "/" + remoteId);
    }

    /**
     * Synchronizes the membership of the remote group {@code remoteId} in both directions with a single
     * SCIM PATCH (RFC 7644 §3.5.2): an {@code add} operation for each member id in {@code toAdd} and a
     * {@code remove} operation (by value-filter path) for each member id in {@code toRemove}. A no-op
     * when both lists are empty.
     */
    public void patchGroupMembers(String remoteId, List<String> toAdd, List<String> toRemove) {
        if (toAdd.isEmpty() && toRemove.isEmpty()) {
            return;
        }
        List<Map<String, Object>> operations = new ArrayList<>();
        for (String value : toAdd) {
            operations.add(Map.of("op", "add", "path", "members",
                    "value", List.of(Map.of("value", value))));
        }
        for (String value : toRemove) {
            operations.add(Map.of("op", "remove", "path", "members[value eq \"" + value + "\"]"));
        }
        Map<String, Object> patch = Map.of(
                "schemas", List.of(ScimPatchRequest.SCHEMA), "Operations", operations);
        OutboundGateway.RawResponse response = exchange("PATCH",
                target.groupsUrl() + "/" + remoteId, write(patch));
        if (response.status() != 200 && response.status() != 204) {
            throw new ScimException(response.status(), null,
                    "SCIM member sync rejected by provider: " + response.status());
        }
    }

    private void deleteAt(String url) {
        OutboundGateway.RawResponse response = exchange("DELETE", url, null);
        if (response.status() != 204 && response.status() != 200
                && response.status() != 404) {
            throw new ScimException(response.status(), null,
                    "SCIM delete rejected by provider: " + response.status());
        }
    }

    private ScimUser send(String method, String url, ScimUser user, int... okStatuses) {
        return send(method, url, user, ScimUser.class, okStatuses);
    }

    private <T> T send(String method, String url, Object body, Class<T> type, int... okStatuses) {
        OutboundGateway.RawResponse response = exchange(method, url, write(body));
        for (int ok : okStatuses) {
            if (response.status() == ok) {
                try {
                    return mapper.readValue(response.body(), type);
                } catch (IOException ex) {
                    throw new ScimException(502, null,
                            "SCIM provider answered an unparseable resource: " + ex.getMessage());
                }
            }
        }
        throw new ScimException(response.status(), null,
                "SCIM " + method + " rejected by provider: " + response.status());
    }

    private OutboundGateway.RawResponse exchange(String method, String url, String body) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + target.bearerToken());
        headers.put("Accept", SCIM_JSON);
        if (body != null) {
            headers.put("Content-Type", SCIM_JSON);
        }
        try {
            return gateway.exchange(
                    new HttpCallSpec(method, url, Map.of(), null, null, null, null, null, null),
                    body == null ? null : body.getBytes(StandardCharsets.UTF_8), headers);
        } catch (TqlException refused) {
            if (HttpOutbound.HOST_DENIED.equals(refused.code())) {
                // An egress allow-list refusal is configuration to fix; every retry would be
                // the identical refusal, so dead-letter now instead of burning the budget.
                throw new TerminalDeliveryException(
                        "SCIM provider egress denied: " + refused.getMessage(), refused);
            }
            // An open circuit or a transport failure heals: 502 keeps the outbox retrying;
            // the message carries the fix.
            throw new ScimException(502, null,
                    "SCIM provider unreachable: " + refused.getMessage());
        }
    }

    private String write(Object body) {
        try {
            return mapper.writeValueAsString(body);
        } catch (IOException ex) {
            throw new ScimException(502, null,
                    "SCIM request body is not serializable: " + ex.getMessage());
        }
    }
}
