package io.tesseraql.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.saml.SamlAssertion;
import io.tesseraql.saml.SamlAttributeMapping;
import io.tesseraql.saml.SamlException;
import io.tesseraql.saml.SamlResponseValidator;
import io.tesseraql.saml.SpMetadata;
import io.tesseraql.security.Principal;
import io.tesseraql.security.session.SessionStore;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;

/**
 * SAML 2.0 Assertion Consumer Service at {@code POST /_tesseraql/saml/acs} (design ch. 10.14,
 * HTTP-POST binding). It validates the posted {@code SAMLResponse}, maps the trusted assertion onto a
 * {@link Principal}, and issues a browser session exactly as the password login does. A validation
 * failure returns 401 without leaking assertion contents.
 */
final class SamlAcsRouteBuilder extends RouteBuilder {

    private final ObjectMapper mapper = new ObjectMapper();
    private final SamlResponseValidator validator;
    private final SamlAttributeMapping mapping;
    private final SessionStore sessions;
    private final SamlUserLinker linker;
    private final SpMetadata metadata;

    SamlAcsRouteBuilder(SamlResponseValidator validator, SamlAttributeMapping mapping,
            SessionStore sessions) {
        this(validator, mapping, sessions, null, null);
    }

    SamlAcsRouteBuilder(SamlResponseValidator validator, SamlAttributeMapping mapping,
            SessionStore sessions, SamlUserLinker linker, SpMetadata metadata) {
        this.validator = validator;
        this.mapping = mapping;
        this.sessions = sessions;
        this.linker = linker;
        this.metadata = metadata;
    }

    @Override
    public void configure() {
        onException(SamlException.class).handled(true).process(this::unauthorized);
        onException(Exception.class).handled(true).process(this::badRequest);

        rest().post("/_tesseraql/saml/acs").to("direct:tql.saml.acs");
        from("direct:tql.saml.acs").routeId("system.saml.acs").process(this::consume);

        if (metadata != null) {
            rest().get("/_tesseraql/saml/metadata").to("direct:tql.saml.metadata");
            from("direct:tql.saml.metadata").routeId("system.saml.metadata").process(this::serveMetadata);
        }
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
        Principal principal = linker == null
                ? toPrincipal(assertion)
                : linker.resolve(loginId(assertion), attribute(assertion, mapping.displayName()),
                        attribute(assertion, mapping.email()), attribute(assertion, mapping.tenant()));

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
