package io.tesseraql.camel.auth;

import org.apache.camel.Consumer;
import org.apache.camel.Processor;
import org.apache.camel.Producer;
import org.apache.camel.support.DefaultEndpoint;

/**
 * Endpoint for the {@code tesseraql-auth} component (design ch. 9.2).
 */
public class TesseraqlAuthEndpoint extends DefaultEndpoint {

    private final String operation;
    private String auth = "bearer";
    private String policy;

    public TesseraqlAuthEndpoint(String uri, TesseraqlAuthComponent component, String operation) {
        super(uri, component);
        this.operation = operation;
    }

    @Override
    public Producer createProducer() {
        return new TesseraqlAuthProducer(this);
    }

    @Override
    public Consumer createConsumer(Processor processor) {
        throw new UnsupportedOperationException("tesseraql-auth does not support consumers");
    }

    public String getOperation() {
        return operation;
    }

    public String getAuth() {
        return auth;
    }

    /** Authentication type for {@code authenticate}. Only {@code bearer} is supported initially. */
    public void setAuth(String auth) {
        this.auth = auth;
    }

    public String getPolicy() {
        return policy;
    }

    /** Policy id for {@code authorize}. */
    public void setPolicy(String policy) {
        this.policy = policy;
    }
}
