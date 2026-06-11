package io.tesseraql.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.compiler.binding.ErrorResponseRenderer;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.identity.PasswordAuthenticator;
import io.tesseraql.identity.RealmConfig;
import io.tesseraql.security.Principal;
import io.tesseraql.security.policy.PolicyEngine;
import io.tesseraql.security.session.SessionStore;
import java.util.Map;
import java.util.Optional;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;

/**
 * Password login endpoint {@code POST /_tesseraql/login} (design ch. 10.8, 11.2). On success it
 * creates a browser session and returns the session cookie; on failure it returns 401.
 */
final class LoginRouteBuilder extends RouteBuilder {

    private final ObjectMapper mapper = new ObjectMapper();
    private final PasswordAuthenticator authenticator;
    private final RealmConfig realm;
    private final SessionStore sessions;

    LoginRouteBuilder(PasswordAuthenticator authenticator, RealmConfig realm, SessionStore sessions) {
        this.authenticator = authenticator;
        this.realm = realm;
        this.sessions = sessions;
    }

    @Override
    public void configure() {
        onException(TqlException.class).handled(true).process(new ErrorResponseRenderer());
        onException(Exception.class).handled(true).process(new ErrorResponseRenderer());

        rest().post("/_tesseraql/login").to("direct:tql.login");
        from("direct:tql.login").routeId("system.login").process(this::login);
    }

    private void login(Exchange exchange) throws Exception {
        Map<String, Object> body = parseBody(exchange);
        String loginId = String.valueOf(body.get("loginId"));
        String password = body.get("password") == null ? null : String.valueOf(body.get("password"));
        String tenantId = body.get("tenantId") == null ? null : String.valueOf(body.get("tenantId"));

        Optional<Principal> principal = authenticator.authenticate(realm, loginId, password, tenantId);
        if (principal.isEmpty()) {
            throw new TqlException(PolicyEngine.UNAUTHORIZED, "Invalid credentials");
        }

        String sessionId = sessions.create(principal.get());
        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 200);
        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json; charset=utf-8");
        exchange.getMessage().setHeader("Set-Cookie",
                sessions.cookieName() + "=" + sessionId + "; Path=/; HttpOnly; SameSite=Lax");
        exchange.getMessage().setBody(mapper.writeValueAsString(
                Map.of("ok", true, "loginId", principal.get().loginId())));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseBody(Exchange exchange) throws Exception {
        String raw = exchange.getMessage().getBody(String.class);
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        return mapper.readValue(raw, Map.class);
    }
}
