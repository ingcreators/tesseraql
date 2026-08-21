package io.tesseraql.scim.camel;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.camel.HttpMounts;
import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.camel.auth.AuthStep;
import io.tesseraql.compiler.pipeline.Pipeline;
import io.tesseraql.compiler.pipeline.Pipelines;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.Headers;
import io.tesseraql.pipeline.RuntimeContext;
import io.tesseraql.scim.ScimError;
import io.tesseraql.scim.ScimException;
import io.tesseraql.scim.ScimGroup;
import io.tesseraql.scim.ScimGroupService;
import io.tesseraql.scim.ScimUser;
import io.tesseraql.scim.ScimUserService;

/**
 * Serves SCIM 2.0 inbound provisioning under {@code /scim/v2} (design ch. 10.15): users at
 * {@code /Users} and, when a group service is configured, groups at {@code /Groups}. Endpoints
 * require a bearer principal with the {@code scim.manage} policy; responses use the SCIM media type
 * and SCIM error envelope.
 */
public final class ScimRouteBuilder {

    private static final AuthStep AUTH = new AuthStep("authenticate", "bearer", null, null);
    private static final AuthStep AUTHORIZE = new AuthStep("authorize", null, "scim.manage", null);
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

    void install(RuntimeContext context) {
        Pipelines.Compilation pipelines = Pipelines.of(context)
                .compiling(java.util.List.of(
                        Pipeline.Handler.catching(ScimException.class, this::scimError),
                        Pipeline.Handler.catching(Exception.class, this::genericError)));

        HttpMounts.mount(context, "POST", "/scim/v2/Users", "scim.createUser");
        HttpMounts.mount(context, "GET", "/scim/v2/Users/{id}", "scim.getUser");
        HttpMounts.mount(context, "GET", "/scim/v2/Users", "scim.listUsers");
        HttpMounts.mount(context, "PUT", "/scim/v2/Users/{id}", "scim.replaceUser");
        HttpMounts.mount(context, "PATCH", "/scim/v2/Users/{id}", "scim.patchUser");
        HttpMounts.mount(context, "DELETE", "/scim/v2/Users/{id}", "scim.deleteUser");

        pipelines.pipeline("scim.createUser")
                .process(AUTH).process(AUTHORIZE).process(this::createUser);
        pipelines.pipeline("scim.getUser")
                .process(AUTH).process(AUTHORIZE).process(this::getUser);
        pipelines.pipeline("scim.listUsers")
                .process(AUTH).process(AUTHORIZE).process(this::listUsers);
        pipelines.pipeline("scim.replaceUser")
                .process(AUTH).process(AUTHORIZE).process(this::replaceUser);
        pipelines.pipeline("scim.patchUser")
                .process(AUTH).process(AUTHORIZE).process(this::patchUser);
        pipelines.pipeline("scim.deleteUser")
                .process(AUTH).process(AUTHORIZE).process(this::deleteUser);

        if (groups != null) {
            configureGroups(context, pipelines);
        }
    }

    private void configureGroups(RuntimeContext context, Pipelines.Compilation pipelines) {
        HttpMounts.mount(context, "POST", "/scim/v2/Groups", "scim.createGroup");
        HttpMounts.mount(context, "GET", "/scim/v2/Groups/{id}", "scim.getGroup");
        HttpMounts.mount(context, "GET", "/scim/v2/Groups", "scim.listGroups");
        HttpMounts.mount(context, "PUT", "/scim/v2/Groups/{id}", "scim.replaceGroup");
        HttpMounts.mount(context, "PATCH", "/scim/v2/Groups/{id}", "scim.patchGroup");
        HttpMounts.mount(context, "DELETE", "/scim/v2/Groups/{id}", "scim.deleteGroup");

        pipelines.pipeline("scim.createGroup")
                .process(AUTH).process(AUTHORIZE).process(this::createGroup);
        pipelines.pipeline("scim.getGroup")
                .process(AUTH).process(AUTHORIZE).process(this::getGroup);
        pipelines.pipeline("scim.listGroups")
                .process(AUTH).process(AUTHORIZE).process(this::listGroups);
        pipelines.pipeline("scim.replaceGroup")
                .process(AUTH).process(AUTHORIZE).process(this::replaceGroup);
        pipelines.pipeline("scim.patchGroup")
                .process(AUTH).process(AUTHORIZE).process(this::patchGroup);
        pipelines.pipeline("scim.deleteGroup")
                .process(AUTH).process(AUTHORIZE).process(this::deleteGroup);
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
                exchange.getMessage().getHeader(Headers.HTTP_URI, String.class)
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
        exchange.getMessage().setHeader(Headers.HTTP_RESPONSE_CODE, 204);
        exchange.getMessage().setBody(null);
    }

    private void createGroup(Exchange exchange) throws Exception {
        ScimGroup request = mapper.readValue(exchange.getMessage().getBody(String.class),
                ScimGroup.class);
        ScimGroup created = groups.create(request);
        exchange.getMessage().setHeader("Location",
                exchange.getMessage().getHeader(Headers.HTTP_URI, String.class)
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
        exchange.getMessage().setHeader(Headers.HTTP_RESPONSE_CODE, 204);
        exchange.getMessage().setBody(null);
    }

    private void respond(Exchange exchange, int status, Object body) throws Exception {
        exchange.getMessage().setHeader(Headers.HTTP_RESPONSE_CODE, status);
        exchange.getMessage().setHeader(Headers.CONTENT_TYPE, SCIM_JSON);
        exchange.getMessage().setBody(mapper.writeValueAsString(body));
    }

    private void scimError(Exchange exchange) throws Exception {
        ScimException ex = exchange.getProperty(TesseraqlProperties.EXCEPTION_CAUGHT,
                ScimException.class);
        respond(exchange, ex.status(), ex.toError());
    }

    private void genericError(Exchange exchange) throws Exception {
        Throwable cause = exchange.getProperty(TesseraqlProperties.EXCEPTION_CAUGHT,
                Throwable.class);
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
