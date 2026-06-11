package io.tesseraql.scim;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A client that provisions users and groups to a downstream SCIM provider over HTTP (design ch. 10.15
 * outbound). Each call carries the target's bearer token and the SCIM media type; non-success
 * responses become {@link ScimException}s carrying the remote status.
 */
public final class ScimOutboundClient {

    private static final String SCIM_JSON = "application/scim+json";

    private final ScimTarget target;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public ScimOutboundClient(ScimTarget target) {
        this(target, HttpClient.newHttpClient());
    }

    public ScimOutboundClient(ScimTarget target, HttpClient http) {
        this.target = target;
        this.http = http;
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
        try {
            HttpResponse<String> response = exchange(request("PATCH",
                    target.groupsUrl() + "/" + remoteId, mapper.writeValueAsString(patch)).build());
            if (response.statusCode() != 200 && response.statusCode() != 204) {
                throw new ScimException(response.statusCode(), null,
                        "SCIM member sync rejected by provider: " + response.statusCode());
            }
        } catch (IOException ex) {
            throw new ScimException(502, null, "SCIM provider unreachable: " + ex.getMessage());
        }
    }

    private void deleteAt(String url) {
        HttpResponse<String> response = exchange(request("DELETE", url, null).build());
        if (response.statusCode() != 204 && response.statusCode() != 200
                && response.statusCode() != 404) {
            throw new ScimException(response.statusCode(), null,
                    "SCIM delete rejected by provider: " + response.statusCode());
        }
    }

    private ScimUser send(String method, String url, ScimUser user, int... okStatuses) {
        return send(method, url, user, ScimUser.class, okStatuses);
    }

    private <T> T send(String method, String url, Object body, Class<T> type, int... okStatuses) {
        try {
            HttpResponse<String> response = exchange(
                    request(method, url, mapper.writeValueAsString(body)).build());
            for (int ok : okStatuses) {
                if (response.statusCode() == ok) {
                    return mapper.readValue(response.body(), type);
                }
            }
            throw new ScimException(response.statusCode(), null,
                    "SCIM " + method + " rejected by provider: " + response.statusCode());
        } catch (IOException ex) {
            throw new ScimException(502, null, "SCIM provider unreachable: " + ex.getMessage());
        }
    }

    private HttpResponse<String> exchange(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException ex) {
            throw new ScimException(502, null, "SCIM provider unreachable: " + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ScimException(502, null, "SCIM provider call interrupted");
        }
    }

    private HttpRequest.Builder request(String method, String url, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + target.bearerToken())
                .header("Accept", SCIM_JSON);
        if (body == null) {
            return builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return builder.header("Content-Type", SCIM_JSON)
                .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    }
}
