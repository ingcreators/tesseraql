package io.tesseraql.camel.iam;

import java.util.Map;
import org.apache.camel.Endpoint;
import org.apache.camel.support.DefaultComponent;

/**
 * Camel component scheme {@code tesseraql-iam} (design ch. 9.3).
 *
 * <p>URI form: {@code tesseraql-iam:contract?name=identity.list-users&resultKey=sql}. It executes a
 * named Identity SQL Contract against the configured realm, so admin UIs work the same on a managed
 * or an existing-database realm.
 */
public class TesseraqlIamComponent extends DefaultComponent {

    @Override
    protected Endpoint createEndpoint(String uri, String remaining, Map<String, Object> parameters)
            throws Exception {
        TesseraqlIamEndpoint endpoint = new TesseraqlIamEndpoint(uri, this, remaining);
        setProperties(endpoint, parameters);
        return endpoint;
    }
}
