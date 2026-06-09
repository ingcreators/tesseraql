package io.tesseraql.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.scim.ScimError;
import io.tesseraql.scim.ScimException;
import io.tesseraql.scim.ScimUser;
import io.tesseraql.scim.ScimUserService;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;

/**
 * Serves SCIM 2.0 inbound provisioning for users under {@code /scim/v2/Users} (design ch. 10.15).
 * Endpoints require a bearer principal with the {@code scim.manage} policy; responses use the SCIM
 * media type and SCIM error envelope.
 */
final class ScimRouteBuilder extends RouteBuilder {

    private static final String AUTH = "tesseraql-auth:authenticate?auth=bearer";
    private static final String AUTHORIZE = "tesseraql-auth:authorize?policy=scim.manage";
    private static final String SCIM_JSON = "application/scim+json; charset=utf-8";

    private final ObjectMapper mapper = new ObjectMapper();
    private final ScimUserService users;

    ScimRouteBuilder(ScimUserService users) {
        this.users = users;
    }

    @Override
    public void configure() {
        onException(ScimException.class).handled(true).process(this::scimError);
        onException(Exception.class).handled(true).process(this::genericError);

        rest().post("/scim/v2/Users").to("direct:scim.createUser");
        rest().get("/scim/v2/Users/{id}").to("direct:scim.getUser");
        rest().get("/scim/v2/Users").to("direct:scim.listUsers");
        rest().put("/scim/v2/Users/{id}").to("direct:scim.replaceUser");
        rest().delete("/scim/v2/Users/{id}").to("direct:scim.deleteUser");

        from("direct:scim.createUser").routeId("scim.createUser")
                .to(AUTH).to(AUTHORIZE).process(this::createUser);
        from("direct:scim.getUser").routeId("scim.getUser")
                .to(AUTH).to(AUTHORIZE).process(this::getUser);
        from("direct:scim.listUsers").routeId("scim.listUsers")
                .to(AUTH).to(AUTHORIZE).process(this::listUsers);
        from("direct:scim.replaceUser").routeId("scim.replaceUser")
                .to(AUTH).to(AUTHORIZE).process(this::replaceUser);
        from("direct:scim.deleteUser").routeId("scim.deleteUser")
                .to(AUTH).to(AUTHORIZE).process(this::deleteUser);
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

    private void deleteUser(Exchange exchange) {
        users.delete(exchange.getMessage().getHeader("id", String.class));
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
