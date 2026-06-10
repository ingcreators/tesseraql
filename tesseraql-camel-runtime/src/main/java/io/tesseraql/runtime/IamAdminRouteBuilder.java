package io.tesseraql.runtime;

import io.tesseraql.compiler.binding.ErrorResponseRenderer;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.identity.IamAdminConsole;
import io.tesseraql.identity.IdentityContracts;
import io.tesseraql.identity.IdentityService;
import io.tesseraql.identity.RealmConfig;
import java.util.List;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;

/**
 * Serves the read-only IAM admin console under {@code /_tesseraql/admin/users} (design ch. 10): a
 * user list and a per-user detail view (roles, groups, permissions). Pages render the identity SQL
 * contracts via {@link IdentityService}, are gated by a bearer principal and the {@code
 * iam.admin.view} policy, and are returned under a strict content security policy. No endpoint
 * mutates identity state.
 */
final class IamAdminRouteBuilder extends RouteBuilder {

    private static final String VIEW = "tesseraql-auth:authenticate?auth=bearer";
    private static final String AUTHZ = "tesseraql-auth:authorize?policy=iam.admin.view";
    private static final String AUTHZ_WRITE = "tesseraql-auth:authorize?policy=iam.admin.write";
    private static final String CSP = "default-src 'self'; style-src 'self' 'unsafe-inline'; "
            + "frame-ancestors 'none'";

    private final IdentityService identity;
    private final RealmConfig realm;

    IamAdminRouteBuilder(IdentityService identity, RealmConfig realm) {
        this.identity = identity;
        this.realm = realm;
    }

    @Override
    public void configure() {
        onException(TqlException.class).handled(true).process(new ErrorResponseRenderer());
        onException(Exception.class).handled(true).process(new ErrorResponseRenderer());

        rest().get("/_tesseraql/admin/users").to("direct:admin.users");
        rest().get("/_tesseraql/admin/users/{id}").to("direct:admin.user");
        rest().post("/_tesseraql/admin/users/{id}/enable").to("direct:admin.user.enable");
        rest().post("/_tesseraql/admin/users/{id}/disable").to("direct:admin.user.disable");

        from("direct:admin.users").routeId("admin.users")
                .to(VIEW).to(AUTHZ).process(usersPage());

        from("direct:admin.user").routeId("admin.user")
                .to(VIEW).to(AUTHZ).process(userPage(null));

        from("direct:admin.user.enable").routeId("admin.user.enable")
                .to(VIEW).to(AUTHZ_WRITE).process(statusChange(IdentityContracts.ENABLE_USER,
                        "User enabled."));

        from("direct:admin.user.disable").routeId("admin.user.disable")
                .to(VIEW).to(AUTHZ_WRITE).process(statusChange(IdentityContracts.DISABLE_USER,
                        "User disabled."));
    }

    private Processor statusChange(String contract, String message) {
        return exchange -> {
            String id = exchange.getMessage().getHeader("id", String.class);
            identity.executeUpdate(realm, contract, Map.of("userId", id));
            userPage(message).process(exchange);
        };
    }

    private Processor usersPage() {
        return exchange -> {
            List<Map<String, Object>> users =
                    identity.execute(realm, IdentityContracts.LIST_USERS, Map.of());
            List<Map<String, Object>> counts =
                    identity.execute(realm, IdentityContracts.COUNT_USERS, Map.of());
            long count = counts.isEmpty() ? users.size() : asLong(counts.get(0).get("count"));
            writeHtml(exchange, 200, IamAdminConsole.renderUsers(users, count));
        };
    }

    private Processor userPage(String status) {
        return exchange -> {
            String id = exchange.getMessage().getHeader("id", String.class);
            Map<String, Object> params = Map.of("userId", id);
            List<Map<String, Object>> found =
                    identity.execute(realm, IdentityContracts.FIND_USER_BY_ID, params);
            if (found.isEmpty()) {
                writeHtml(exchange, 404,
                        "<!DOCTYPE html><html><body><p>User not found.</p></body></html>");
                return;
            }
            String html = IamAdminConsole.renderUser(found.get(0),
                    identity.execute(realm, IdentityContracts.FIND_ROLES_BY_USER_ID, params),
                    identity.execute(realm, IdentityContracts.FIND_GROUPS_BY_USER_ID, params),
                    identity.execute(realm, IdentityContracts.FIND_PERMISSIONS_BY_USER_ID, params),
                    realm.capabilities().userWriteAllowed(), status);
            writeHtml(exchange, 200, html);
        };
    }

    private static long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static void writeHtml(Exchange exchange, int status, String body) {
        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, status);
        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "text/html; charset=utf-8");
        exchange.getMessage().setHeader("Content-Security-Policy", CSP);
        exchange.getMessage().setHeader("X-Content-Type-Options", "nosniff");
        exchange.getMessage().setHeader("X-Frame-Options", "DENY");
        exchange.getMessage().setHeader("Referrer-Policy", "no-referrer");
        exchange.getMessage().setBody(body);
    }
}
