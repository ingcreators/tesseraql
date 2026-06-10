package io.tesseraql.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.saml.AuthnRequest;
import io.tesseraql.saml.LogoutRequest;
import io.tesseraql.saml.SamlAssertion;
import io.tesseraql.saml.SamlAttributeMapping;
import io.tesseraql.saml.SamlException;
import io.tesseraql.saml.SamlRedirect;
import io.tesseraql.saml.SamlResponseValidator;
import io.tesseraql.saml.SpMetadata;
import io.tesseraql.security.Principal;
import io.tesseraql.security.session.SessionStore;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;

/**
 * SAML 2.0 SP web endpoints under {@code /_tesseraql/saml} (design ch. 10.14): the Assertion Consumer
 * Service ({@code POST /acs}, HTTP-POST binding) that validates the response and issues a session,
 * SP-initiated SSO ({@code GET /login}, HTTP-Redirect AuthnRequest), single logout ({@code GET
 * /logout}), and SP metadata ({@code GET /metadata}). A validation failure returns 401 without
 * leaking assertion contents.
 */
final class SamlAcsRouteBuilder extends RouteBuilder {

    private final ObjectMapper mapper = new ObjectMapper();
    private final SamlResponseValidator validator;
    private final SamlAttributeMapping mapping;
    private final SessionStore sessions;
    private final SamlUserLinker linker;
    private final SpMetadata metadata;
    private final SamlEndpoints endpoints;

    SamlAcsRouteBuilder(SamlResponseValidator validator, SamlAttributeMapping mapping,
            SessionStore sessions) {
        this(validator, mapping, sessions, null, null, null);
    }

    SamlAcsRouteBuilder(SamlResponseValidator validator, SamlAttributeMapping mapping,
            SessionStore sessions, SamlUserLinker linker, SpMetadata metadata,
            SamlEndpoints endpoints) {
        this.validator = validator;
        this.mapping = mapping;
        this.sessions = sessions;
        this.linker = linker;
        this.metadata = metadata;
        this.endpoints = endpoints;
    }

    @Override
    public void configure() {
        onException(SamlException.class).handled(true).process(this::unauthorized);
        onException(Exception.class).handled(true).process(this::badRequest);

        rest().post("/_tesseraql/saml/acs").to("direct:tql.saml.acs");
        from("direct:tql.saml.acs").routeId("system.saml.acs").process(this::consume);

        rest().get("/_tesseraql/saml/logout").to("direct:tql.saml.logout");
        from("direct:tql.saml.logout").routeId("system.saml.logout").process(this::logout);

        if (metadata != null) {
            rest().get("/_tesseraql/saml/metadata").to("direct:tql.saml.metadata");
            from("direct:tql.saml.metadata").routeId("system.saml.metadata").process(this::serveMetadata);
        }
        if (endpoints != null && endpoints.idpSsoUrl() != null && endpoints.acsUrl() != null) {
            rest().get("/_tesseraql/saml/login").to("direct:tql.saml.login");
            from("direct:tql.saml.login").routeId("system.saml.login").process(this::login);
        }
    }

    /** SP-initiated SSO: redirect to the IdP with a DEFLATE-encoded AuthnRequest (HTTP-Redirect). */
    private void login(Exchange exchange) {
        String xml = new AuthnRequest(endpoints.spEntityId(), endpoints.acsUrl(), endpoints.idpSsoUrl())
                .toXml("_" + UUID.randomUUID(), Instant.now());
        String relayState = exchange.getMessage().getHeader("RelayState", String.class);
        redirect(exchange, endpoints.idpSsoUrl(), SamlRedirect.deflateAndEncode(xml), relayState);
    }

    /** Single logout: invalidate the local session, clear the cookie, and redirect to the IdP SLO. */
    private void logout(Exchange exchange) {
        String sessionId = cookieValue(exchange.getMessage().getHeader("Cookie", String.class),
                sessions.cookieName());
        SessionStore.Session session = sessions.session(sessionId);
        sessions.invalidate(sessionId);
        exchange.getMessage().setHeader("Set-Cookie",
                sessions.cookieName() + "=; Path=/; HttpOnly; Max-Age=0");

        String nameId = session == null ? null
                : (String) session.principal().claims().get("samlNameId");
        if (endpoints != null && endpoints.idpSloUrl() != null && nameId != null) {
            String sessionIndex = (String) session.principal().claims().get("sessionIndex");
            String xml = new LogoutRequest(endpoints.spEntityId(), endpoints.idpSloUrl(), nameId,
                    sessionIndex).toXml("_" + UUID.randomUUID(), Instant.now());
            redirect(exchange, endpoints.idpSloUrl(), SamlRedirect.deflateAndEncode(xml), null);
            return;
        }
        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 200);
        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json; charset=utf-8");
        try {
            exchange.getMessage().setBody(mapper.writeValueAsString(Map.of("ok", true)));
        } catch (Exception ex) {
            exchange.getMessage().setBody("{\"ok\":true}");
        }
    }

    private static void redirect(Exchange exchange, String idpUrl, String samlRequest, String relayState) {
        String separator = idpUrl.contains("?") ? "&" : "?";
        StringBuilder location = new StringBuilder(idpUrl).append(separator)
                .append("SAMLRequest=").append(URLEncoder.encode(samlRequest, StandardCharsets.UTF_8));
        if (relayState != null) {
            location.append("&RelayState=").append(URLEncoder.encode(relayState, StandardCharsets.UTF_8));
        }
        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 302);
        exchange.getMessage().setHeader("Location", location.toString());
        exchange.getMessage().setBody(null);
    }

    private static String cookieValue(String cookieHeader, String name) {
        if (cookieHeader == null) {
            return null;
        }
        for (String cookie : cookieHeader.split(";")) {
            String trimmed = cookie.trim();
            if (trimmed.startsWith(name + "=")) {
                return trimmed.substring(name.length() + 1);
            }
        }
        return null;
    }

    private void serveMetadata(Exchange exchange) {
        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 200);
        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE,
                "application/samlmetadata+xml; charset=utf-8");
        exchange.getMessage().setBody(metadata.toXml());
    }

    private void consume(Exchange exchange) throws Exception {
        // platform-http may expose a form field as a header; otherwise parse the urlencoded body.
        String encoded = exchange.getMessage().getHeader("SAMLResponse", String.class);
        if (encoded == null) {
            encoded = formParam(exchange.getMessage().getBody(String.class), "SAMLResponse");
        }
        if (encoded == null) {
            throw new SamlException("Missing SAMLResponse");
        }
        String xml = new String(Base64.getMimeDecoder().decode(encoded), StandardCharsets.UTF_8);
        SamlAssertion assertion = validator.validate(xml, Instant.now());
        Principal resolved = linker == null
                ? toPrincipal(assertion)
                : linker.resolve(loginId(assertion), attribute(assertion, mapping.displayName()),
                        attribute(assertion, mapping.email()), attribute(assertion, mapping.tenant()));
        Principal principal = withFederationClaims(resolved, assertion);

        String sessionId = sessions.create(principal);
        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 200);
        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json; charset=utf-8");
        exchange.getMessage().setHeader("Set-Cookie",
                sessions.cookieName() + "=" + sessionId + "; Path=/; HttpOnly; SameSite=Lax");
        exchange.getMessage().setBody(mapper.writeValueAsString(
                Map.of("ok", true, "loginId", principal.loginId(), "subject", principal.subject())));
    }

    /** Maps a validated assertion onto a principal from its attributes (IdP-asserted roles). */
    private Principal toPrincipal(SamlAssertion assertion) {
        Map<String, Object> claims = new LinkedHashMap<>(assertion.attributes());
        if (assertion.sessionIndex() != null) {
            claims.put("sessionIndex", assertion.sessionIndex());
        }
        return new Principal(assertion.nameId(), loginId(assertion),
                attribute(assertion, mapping.displayName()), attribute(assertion, mapping.tenant()),
                values(assertion, mapping.groups()), values(assertion, mapping.roles()),
                List.of(), claims);
    }

    /** Stashes the federated NameID and SessionIndex in the principal's claims for later single logout. */
    private static Principal withFederationClaims(Principal principal, SamlAssertion assertion) {
        Map<String, Object> claims = new LinkedHashMap<>(principal.claims());
        claims.put("samlNameId", assertion.nameId());
        if (assertion.sessionIndex() != null) {
            claims.put("sessionIndex", assertion.sessionIndex());
        }
        return new Principal(principal.subject(), principal.loginId(), principal.displayName(),
                principal.tenantId(), principal.groups(), principal.roles(), principal.permissions(),
                claims);
    }

    /** The login id: the configured attribute when present, otherwise the subject NameID. */
    private String loginId(SamlAssertion assertion) {
        return mapping.loginId() == null
                ? assertion.nameId()
                : assertion.attribute(mapping.loginId()).orElse(assertion.nameId());
    }

    private static String attribute(SamlAssertion assertion, String name) {
        return name == null ? null : assertion.attribute(name).orElse(null);
    }

    private static List<String> values(SamlAssertion assertion, String attribute) {
        if (attribute == null) {
            return List.of();
        }
        List<String> found = assertion.attributes().get(attribute);
        return found == null ? List.of() : List.copyOf(found);
    }

    private static String formParam(String form, String name) {
        if (form == null || form.isBlank()) {
            return null;
        }
        for (String pair : form.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
            if (name.equals(key)) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private void unauthorized(Exchange exchange) throws Exception {
        respondError(exchange, 401, "SAML authentication failed");
    }

    private void badRequest(Exchange exchange) throws Exception {
        respondError(exchange, 400, "Invalid SAML request");
    }

    private void respondError(Exchange exchange, int status, String message) throws Exception {
        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, status);
        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json; charset=utf-8");
        exchange.getMessage().setBody(mapper.writeValueAsString(Map.of("error", message)));
    }
}
