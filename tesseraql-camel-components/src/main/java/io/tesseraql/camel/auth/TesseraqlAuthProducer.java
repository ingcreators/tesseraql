package io.tesseraql.camel.auth;

import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.security.Principal;
import io.tesseraql.security.jwt.JwtAuthenticator;
import io.tesseraql.security.policy.PolicyEngine;
import org.apache.camel.Exchange;
import org.apache.camel.support.DefaultProducer;

/**
 * Performs the {@code authenticate} and {@code authorize} operations for the {@code tesseraql-auth}
 * component (design ch. 9.2, 7.2). The {@link PolicyEngine} and {@link JwtAuthenticator} are looked
 * up from the Camel registry, where the runtime binds them from the security configuration.
 */
public class TesseraqlAuthProducer extends DefaultProducer {

    private static final TqlErrorCode UNSUPPORTED = new TqlErrorCode(TqlDomain.SEC, 4000);

    private final TesseraqlAuthEndpoint endpoint;

    public TesseraqlAuthProducer(TesseraqlAuthEndpoint endpoint) {
        super(endpoint);
        this.endpoint = endpoint;
    }

    @Override
    public void process(Exchange exchange) {
        switch (endpoint.getOperation()) {
            case "authenticate" -> authenticate(exchange);
            case "authorize" -> authorize(exchange);
            default -> throw new TqlException(UNSUPPORTED,
                    "Unsupported tesseraql-auth operation: " + endpoint.getOperation());
        }
    }

    private void authenticate(Exchange exchange) {
        if (!"bearer".equals(endpoint.getAuth())) {
            throw new TqlException(UNSUPPORTED, "Unsupported auth type: " + endpoint.getAuth());
        }
        JwtAuthenticator authenticator = bean(JwtAuthenticator.class,
                TesseraqlProperties.JWT_AUTHENTICATOR_BEAN);
        String header = exchange.getMessage().getHeader("Authorization", String.class);
        Principal principal = authenticator.authenticate(header);
        exchange.setProperty(TesseraqlProperties.PRINCIPAL, principal);
    }

    private void authorize(Exchange exchange) {
        PolicyEngine engine = bean(PolicyEngine.class, TesseraqlProperties.POLICY_ENGINE_BEAN);
        Principal principal = exchange.getProperty(TesseraqlProperties.PRINCIPAL, Principal.class);
        engine.authorize(endpoint.getPolicy(), principal);
    }

    private <T> T bean(Class<T> type, String name) {
        T bean = endpoint.getCamelContext().getRegistry().lookupByNameAndType(name, type);
        if (bean == null) {
            throw new TqlException(UNSUPPORTED,
                    "Security bean '" + name + "' is not bound; security is not configured");
        }
        return bean;
    }
}
