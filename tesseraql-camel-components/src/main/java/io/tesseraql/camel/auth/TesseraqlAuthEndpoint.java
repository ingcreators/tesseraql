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
    private String pathTemplate;

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

    public String getPathTemplate() {
        return pathTemplate;
    }

    /**
     * The route's own URL template, set only when {@code policy} resolves from the path
     * (docs/access-governance.md structural decision 7).
     *
     * <p>It is what lets the atom be read off the request's URL rather than off a header. The
     * router does publish its path parameters as headers, but so does a form body: a field
     * named after the path parameter overwrites it, and the gate would then be resolving from
     * exactly the caller-shaped input this design refuses to build a gate from.
     */
    public void setPathTemplate(String pathTemplate) {
        this.pathTemplate = pathTemplate;
    }
}
