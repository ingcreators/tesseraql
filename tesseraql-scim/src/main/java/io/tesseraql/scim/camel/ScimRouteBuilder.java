package io.tesseraql.scim.camel;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.camel.HttpMounts;
import io.tesseraql.scim.ScimError;
import io.tesseraql.scim.ScimException;
import io.tesseraql.scim.ScimGroup;
import io.tesseraql.scim.ScimGroupService;
import io.tesseraql.scim.ScimUser;
import io.tesseraql.scim.ScimUserService;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;

/**
 * Serves SCIM 2.0 inbound provisioning under {@code /scim/v2} (design ch. 10.15): users at
 * {@code /Users} and, when a group service is configured, groups at {@code /Groups}. Endpoints
 * require a bearer principal with the {@code scim.manage} policy; responses use the SCIM media type
 * and SCIM error envelope.
 */
public final class ScimRouteBuilder extends RouteBuilder {

    private static final String AUTH = "tesseraql-auth:authenticate?auth=bearer";
    private static final String AUTHORIZE = "tesseraql-auth:authorize?policy=scim.manage";
    private static final String SCIM_JSON = "application/scim+json; charset=utf-8";

    private final ObjectMapper mapper = new ObjectMapper();
    private final ScimUserService users;
    private final ScimGroupService groups;
    private final io.tesseraql.scim.ScimAttributeCapture capture;

    public ScimRouteBuilder(ScimUserService users) {
        this(users, null, null);
    }

    public ScimRouteBuilder(ScimUserService users, ScimGroupService groups) {
        this(users, groups, null);
    }

    /** With the identity-store attribute capture (docs/application-roles.md), or null without. */
    public ScimRouteBuilder(ScimUserService users, ScimGroupService groups,
            io.tesseraql.scim.ScimAttributeCapture capture) {
        this.users = users;
        this.groups = groups;
        this.capture = capture;
    }

    @Override
    public void configure() {
        onException(ScimException.class).handled(true).process(this::scimError);
        onException(Exception.class).handled(true).process(this::genericError);

        HttpMounts.mount(getContext(), "POST", "/scim/v2/Users", "direct:scim.createUser");
        HttpMounts.mount(getContext(), "GET", "/scim/v2/Users/{id}", "direct:scim.getUser");
        HttpMounts.mount(getContext(), "GET", "/scim/v2/Users", "direct:scim.listUsers");
        HttpMounts.mount(getContext(), "PUT", "/scim/v2/Users/{id}", "direct:scim.replaceUser");
        HttpMounts.mount(getContext(), "PATCH", "/scim/v2/Users/{id}", "direct:scim.patchUser");
        HttpMounts.mount(getContext(), "DELETE", "/scim/v2/Users/{id}", "direct:scim.deleteUser");

        from("direct:scim.createUser").routeId("scim.createUser")
                .to(AUTH).to(AUTHORIZE).process(this::createUser);
        from("direct:scim.getUser").routeId("scim.getUser")
                .to(AUTH).to(AUTHORIZE).process(this::getUser);
        from("direct:scim.listUsers").routeId("scim.listUsers")
                .to(AUTH).to(AUTHORIZE).process(this::listUsers);
        from("direct:scim.replaceUser").routeId("scim.replaceUser")
                .to(AUTH).to(AUTHORIZE).process(this::replaceUser);
        from("direct:scim.patchUser").routeId("scim.patchUser")
                .to(AUTH).to(AUTHORIZE).process(this::patchUser);
        from("direct:scim.deleteUser").routeId("scim.deleteUser")
                .to(AUTH).to(AUTHORIZE).process(this::deleteUser);

        if (groups != null) {
            configureGroups();
        }
    }

    private void configureGroups() {
        HttpMounts.mount(getContext(), "POST", "/scim/v2/Groups", "direct:scim.createGroup");
        HttpMounts.mount(getContext(), "GET", "/scim/v2/Groups/{id}", "direct:scim.getGroup");
        HttpMounts.mount(getContext(), "GET", "/scim/v2/Groups", "direct:scim.listGroups");
        HttpMounts.mount(getContext(), "PUT", "/scim/v2/Groups/{id}", "direct:scim.replaceGroup");
        HttpMounts.mount(getContext(), "PATCH", "/scim/v2/Groups/{id}", "direct:scim.patchGroup");
        HttpMounts.mount(getContext(), "DELETE", "/scim/v2/Groups/{id}", "direct:scim.deleteGroup");

        from("direct:scim.createGroup").routeId("scim.createGroup")
                .to(AUTH).to(AUTHORIZE).process(this::createGroup);
        from("direct:scim.getGroup").routeId("scim.getGroup")
                .to(AUTH).to(AUTHORIZE).process(this::getGroup);
        from("direct:scim.listGroups").routeId("scim.listGroups")
                .to(AUTH).to(AUTHORIZE).process(this::listGroups);
        from("direct:scim.replaceGroup").routeId("scim.replaceGroup")
                .to(AUTH).to(AUTHORIZE).process(this::replaceGroup);
        from("direct:scim.patchGroup").routeId("scim.patchGroup")
                .to(AUTH).to(AUTHORIZE).process(this::patchGroup);
        from("direct:scim.deleteGroup").routeId("scim.deleteGroup")
                .to(AUTH).to(AUTHORIZE).process(this::deleteGroup);
    }

    private void createUser(Exchange exchange) throws Exception {
        com.fasterxml.jackson.databind.JsonNode payload = mapper.readTree(
                exchange.getMessage().getBody(String.class));
        ScimUser request = mapper.treeToValue(payload, ScimUser.class);
        ScimUser created = users.create(request);
        if (capture != null) {
            capture.syncResource(created.id(), request, payload);
        }
        // RFC 7644 §3.3: a SCIM 201 carries the created resource's Location.
        exchange.getMessage().setHeader("Location",
                exchange.getMessage().getHeader(Exchange.HTTP_URI, String.class)
                        + "/" + created.id());
        respond(exchange, 201, created);
    }

    private void getUser(Exchange exchange) throws Exception {
        String id = exchange.getMessage().getHeader("id", String.class);
        ScimUser user = users.findById(id)
                .orElseThrow(() -> new ScimException(404, null, "User not found: " + id));
        respond(exchange, 200, user);
    }

    private void listUsers(Exchange exchange) throws Exception {
        int startIndex = header(exchange, "startIndex", 1);
        int count = header(exchange, "count", 100);
        String filter = exchange.getMessage().getHeader("filter", String.class);
        respond(exchange, 200, users.list(startIndex, count, filter));
    }

    private void replaceUser(Exchange exchange) throws Exception {
        String id = exchange.getMessage().getHeader("id", String.class);
        com.fasterxml.jackson.databind.JsonNode payload = mapper.readTree(
                exchange.getMessage().getBody(String.class));
        ScimUser request = mapper.treeToValue(payload, ScimUser.class);
        ScimUser replaced = users.replace(id, request);
        if (capture != null) {
            capture.syncResource(id, request, payload);
        }
        respond(exchange, 200, replaced);
    }

    private void patchUser(Exchange exchange) throws Exception {
        String id = exchange.getMessage().getHeader("id", String.class);
        io.tesseraql.scim.ScimPatchRequest patch = mapper.readValue(
                exchange.getMessage().getBody(String.class),
                io.tesseraql.scim.ScimPatchRequest.class);
        ScimUser patched = users.patch(id, patch);
        if (capture != null) {
            capture.syncPatch(id, patch);
        }
        respond(exchange, 200, patched);
    }

    private void deleteUser(Exchange exchange) {
        users.delete(exchange.getMessage().getHeader("id", String.class));
        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 204);
        exchange.getMessage().setBody(null);
    }

    private void createGroup(Exchange exchange) throws Exception {
        ScimGroup request = mapper.readValue(exchange.getMessage().getBody(String.class),
                ScimGroup.class);
        ScimGroup created = groups.create(request);
        exchange.getMessage().setHeader("Location",
                exchange.getMessage().getHeader(Exchange.HTTP_URI, String.class)
                        + "/" + created.id());
        respond(exchange, 201, created);
    }

    private void getGroup(Exchange exchange) throws Exception {
        String id = exchange.getMessage().getHeader("id", String.class);
        ScimGroup group = groups.findById(id)
                .orElseThrow(() -> new ScimException(404, null, "Group not found: " + id));
        respond(exchange, 200, group);
    }

    private void listGroups(Exchange exchange) throws Exception {
        String filter = exchange.getMessage().getHeader("filter", String.class);
        if (filter != null && !filter.isBlank()) {
            // The Groups endpoint has no filter support; it used to silently return the whole
            // directory, so an IdP's pre-create `displayName eq "X"` lookup got every group and
            // mutated the wrong one. RFC 7644: an unsupported filter is invalidFilter (400).
            throw new io.tesseraql.scim.ScimException(400, "invalidFilter",
                    "Filtering is not supported on the Groups endpoint");
        }
        int startIndex = header(exchange, "startIndex", 1);
        int count = header(exchange, "count", 100);
        respond(exchange, 200, groups.list(startIndex, count));
    }

    private void replaceGroup(Exchange exchange) throws Exception {
        String id = exchange.getMessage().getHeader("id", String.class);
        ScimGroup request = mapper.readValue(exchange.getMessage().getBody(String.class),
                ScimGroup.class);
        respond(exchange, 200, groups.replace(id, request));
    }

    private void patchGroup(Exchange exchange) throws Exception {
        String id = exchange.getMessage().getHeader("id", String.class);
        io.tesseraql.scim.ScimPatchRequest patch = mapper.readValue(
                exchange.getMessage().getBody(String.class),
                io.tesseraql.scim.ScimPatchRequest.class);
        respond(exchange, 200, groups.patch(id, patch));
    }

    private void deleteGroup(Exchange exchange) {
        groups.delete(exchange.getMessage().getHeader("id", String.class));
        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 204);
        exchange.getMessage().setBody(null);
    }

    private void respond(Exchange exchange, int status, Object body) throws Exception {
        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, status);
        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, SCIM_JSON);
        exchange.getMessage().setBody(mapper.writeValueAsString(body));
    }

    private void scimError(Exchange exchange) throws Exception {
        ScimException ex = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, ScimException.class);
        respond(exchange, ex.status(), ex.toError());
    }

    private void genericError(Exchange exchange) throws Exception {
        Throwable cause = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Throwable.class);
        int status = cause instanceof io.tesseraql.core.error.TqlException tql
                ? io.tesseraql.compiler.binding.ErrorResponseRenderer.httpStatus(tql.code())
                : 500;
        respond(exchange, status, ScimError.of(status,
                cause == null ? "Internal Server Error" : "Request rejected"));
    }

    /** The largest page a SCIM list returns; a client asking for more is clamped, not honored. */
    private static final int MAX_COUNT = 200;

    private static int header(Exchange exchange, String name, int defaultValue) {
        String raw = exchange.getMessage().getHeader(name, String.class);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        int value;
        try {
            // getHeader(Integer.class) returned null on a non-numeric value, so ?count=abc
            // silently became the default and ?count=1000000 was an unbounded page; reject and
            // clamp explicitly.
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            throw new io.tesseraql.scim.ScimException(400, "invalidValue",
                    "Query parameter '" + name + "' must be an integer, not '" + raw + "'");
        }
        if ("startIndex".equals(name)) {
            return Math.max(1, value);
        }
        if ("count".equals(name)) {
            return Math.max(0, Math.min(value, MAX_COUNT));
        }
        return value;
    }
}
