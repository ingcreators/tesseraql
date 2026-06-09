package io.tesseraql.camel.iam;

import org.apache.camel.Consumer;
import org.apache.camel.Processor;
import org.apache.camel.Producer;
import org.apache.camel.support.DefaultEndpoint;

/**
 * Endpoint for the {@code tesseraql-iam} component (design ch. 9.3).
 */
public class TesseraqlIamEndpoint extends DefaultEndpoint {

    private final String operation;
    private String name;
    private String realm;
    private String resultKey = "sql";

    public TesseraqlIamEndpoint(String uri, TesseraqlIamComponent component, String operation) {
        super(uri, component);
        this.operation = operation;
    }

    @Override
    public Producer createProducer() {
        return new TesseraqlIamProducer(this);
    }

    @Override
    public Consumer createConsumer(Processor processor) {
        throw new UnsupportedOperationException("tesseraql-iam does not support consumers");
    }

    public String getOperation() {
        return operation;
    }

    public String getName() {
        return name;
    }

    /** The contract name, e.g. {@code identity.list-users}. */
    public void setName(String name) {
        this.name = name;
    }

    public String getRealm() {
        return realm;
    }

    /** Optional realm id; defaults to the configured default realm. */
    public void setRealm(String realm) {
        this.realm = realm;
    }

    public String getResultKey() {
        return resultKey;
    }

    public void setResultKey(String resultKey) {
        this.resultKey = resultKey;
    }
}
