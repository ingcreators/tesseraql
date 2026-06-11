package io.tesseraql.camel.auth;

import java.util.Map;
import org.apache.camel.Endpoint;
import org.apache.camel.support.DefaultComponent;

/**
 * Camel component scheme {@code tesseraql-auth} (design ch. 9.2).
 *
 * <p>URI forms: {@code tesseraql-auth:authenticate?auth=bearer} and
 * {@code tesseraql-auth:authorize?policy=users.read}. CSRF support is added in a later phase.
 */
public class TesseraqlAuthComponent extends DefaultComponent {

    @Override
    protected Endpoint createEndpoint(String uri, String remaining, Map<String, Object> parameters)
            throws Exception {
        TesseraqlAuthEndpoint endpoint = new TesseraqlAuthEndpoint(uri, this, remaining);
        setProperties(endpoint, parameters);
        return endpoint;
    }
}
