package io.tesseraql.camel.service;

import org.apache.camel.Consumer;
import org.apache.camel.Processor;
import org.apache.camel.Producer;
import org.apache.camel.support.DefaultEndpoint;

/** Endpoint for the {@code tesseraql-service} component (design ch. 47). */
public class TesseraqlServiceEndpoint extends DefaultEndpoint {

    private final String operation;
    private String name;
    private String resultKey = "sql";

    public TesseraqlServiceEndpoint(String uri, TesseraqlServiceComponent component,
            String operation) {
        super(uri, component);
        this.operation = operation;
    }

    @Override
    public Producer createProducer() {
        return new TesseraqlServiceProducer(this);
    }

    @Override
    public Consumer createConsumer(Processor processor) {
        throw new UnsupportedOperationException("tesseraql-service does not support consumers");
    }

    public String getOperation() {
        return operation;
    }

    public String getName() {
        return name;
    }

    /** The provider name, e.g. {@code ops.overview}. */
    public void setName(String name) {
        this.name = name;
    }

    public String getResultKey() {
        return resultKey;
    }

    public void setResultKey(String resultKey) {
        this.resultKey = resultKey;
    }
}
