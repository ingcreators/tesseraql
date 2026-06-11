package io.tesseraql.camel.service;

import java.util.Map;
import org.apache.camel.Endpoint;
import org.apache.camel.support.DefaultComponent;

/**
 * Camel component scheme {@code tesseraql-service} (design ch. 47).
 *
 * <p>URI form: {@code tesseraql-service:call?name=ops.overview&resultKey=ops}. It invokes a named
 * {@link io.tesseraql.core.service.ServiceProvider} from the runtime registry, exposing non-SQL
 * runtime state (lanes, traces, file trees, ...) to yaml/template routes.
 */
public class TesseraqlServiceComponent extends DefaultComponent {

    @Override
    protected Endpoint createEndpoint(String uri, String remaining, Map<String, Object> parameters)
            throws Exception {
        TesseraqlServiceEndpoint endpoint = new TesseraqlServiceEndpoint(uri, this, remaining);
        setProperties(endpoint, parameters);
        return endpoint;
    }
}
