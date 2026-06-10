package io.tesseraql.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
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
final class ScimRouteBuilder extends RouteBuilder {

    private static final String AUTH = "tesseraql-auth:authenticate?auth=bearer";
    private static final String AUTHORIZE = "tesseraql-auth:authorize?policy=scim.manage";
    private static final String SCIM_JSON = "application/scim+json; charset=utf-8";

    private final ObjectMapper mapper = new ObjectMapper();
    private final ScimUserService users;
    private final ScimGroupService groups;

    ScimRouteBuilder(ScimUserService users) {
        this(users, null);
    }

    ScimRouteBuilder(ScimUserService users, ScimGroupService groups) {
        this.users = users;
        this.groups = groups;
    }

    @Override
    public void configure() {
        onException(ScimException.class).handled(true).process(this::scimError);
        onException(Exception.class).handled(true).process(this::genericError);

        rest().post("/scim/v2/Users").to("direct:scim.createUser");
        rest().get("/scim/v2/Users/{id}").to("direct:scim.getUser");
        rest().get("/scim/v2/Users").to("direct:scim.listUsers");
        rest().put("/scim/v2/Users/{id}").to("direct:scim.replaceUser");
        rest().patch("/scim/v2/Users/{id}").to("direct:scim.patchUser");
        rest().delete("/scim/v2/Users/{id}").to("direct:scim.deleteUser");

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
        rest().post("/scim/v2/Groups").to("direct:scim.createGroup");
        rest().get("/scim/v2/Groups/{id}").to("direct:scim.getGroup");
        rest().get("/scim/v2/Groups").to("direct:scim.listGroups");
        rest().put("/scim/v2/Groups/{id}").to("direct:scim.replaceGroup");
        rest().patch("/scim/v2/Groups/{id}").to("direct:scim.patchGroup");
        rest().delete("/scim/v2/Groups/{id}").to("direct:scim.deleteGroup");

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
        ScimUser request = mapper.readValue(exchange.getMessage().getBody(String.class), ScimUser.class);
        respond(exchange, 201, users.create(request));
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
        ScimUser request = mapper.readValue(exchange.getMessage().getBody(String.class), ScimUser.class);
        respond(exchange, 200, users.replace(id, request));
    }

    private void patchUser(Exchange exchange) throws Exception {
        String id = exchange.getMessage().getHeader("id", String.class);
        io.tesseraql.scim.ScimPatchRequest patch = mapper.readValue(
                exchange.getMessage().getBody(String.class), io.tesseraql.scim.ScimPatchRequest.class);
        respond(exchange, 200, users.patch(id, patch));
    }

    private void deleteUser(Exchange exchange) {
        users.delete(exchange.getMessage().getHeader("id", String.class));
        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 204);
        exchange.getMessage().setBody(null);
    }

    private void createGroup(Exchange exchange) throws Exception {
        ScimGroup request =
                mapper.readValue(exchange.getMessage().getBody(String.class), ScimGroup.class);
        respond(exchange, 201, groups.create(request));
    }

    private void getGroup(Exchange exchange) throws Exception {
        String id = exchange.getMessage().getHeader("id", String.class);
        ScimGroup group = groups.findById(id)
                .orElseThrow(() -> new ScimException(404, null, "Group not found: " + id));
        respond(exchange, 200, group);
    }

    private void listGroups(Exchange exchange) throws Exception {
        int startIndex = header(exchange, "startIndex", 1);
        int count = header(exchange, "count", 100);
        respond(exchange, 200, groups.list(startIndex, count));
    }

    private void replaceGroup(Exchange exchange) throws Exception {
        String id = exchange.getMessage().getHeader("id", String.class);
        ScimGroup request =
                mapper.readValue(exchange.getMessage().getBody(String.class), ScimGroup.class);
        respond(exchange, 200, groups.replace(id, request));
    }

    private void patchGroup(Exchange exchange) throws Exception {
        String id = exchange.getMessage().getHeader("id", String.class);
        io.tesseraql.scim.ScimPatchRequest patch = mapper.readValue(
                exchange.getMessage().getBody(String.class), io.tesseraql.scim.ScimPatchRequest.class);
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
                ? io.tesseraql.compiler.binding.ErrorResponseRenderer.httpStatus(tql.code()) : 500;
        respond(exchange, status, ScimError.of(status,
                cause == null ? "Internal Server Error" : "Request rejected"));
    }

    private static int header(Exchange exchange, String name, int defaultValue) {
        Integer value = exchange.getMessage().getHeader(name, Integer.class);
        return value != null ? value : defaultValue;
    }
}
