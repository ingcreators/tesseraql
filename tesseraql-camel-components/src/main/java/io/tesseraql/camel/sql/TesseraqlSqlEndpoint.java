package io.tesseraql.camel.sql;

import org.apache.camel.Consumer;
import org.apache.camel.Processor;
import org.apache.camel.Producer;
import org.apache.camel.support.DefaultEndpoint;

/**
 * Endpoint for the {@code tesseraql-sql} component (design ch. 9.1).
 */
public class TesseraqlSqlEndpoint extends DefaultEndpoint {

    private final String sqlPath;
    private String datasource = "main";
    private String mode = "query";
    private String resultKey = "sql";

    public TesseraqlSqlEndpoint(String uri, TesseraqlSqlComponent component, String sqlPath) {
        super(uri, component);
        this.sqlPath = sqlPath;
    }

    @Override
    public Producer createProducer() {
        return new TesseraqlSqlProducer(this);
    }

    @Override
    public Consumer createConsumer(Processor processor) {
        throw new UnsupportedOperationException("tesseraql-sql does not support consumers");
    }

    public String getSqlPath() {
        return sqlPath;
    }

    public String getDatasource() {
        return datasource;
    }

    /** Name of the {@code javax.sql.DataSource} bound in the Camel registry. Defaults to {@code main}. */
    public void setDatasource(String datasource) {
        this.datasource = datasource;
    }

    public String getMode() {
        return mode;
    }

    /** SQL execution mode (design ch. 28.6). Only {@code query} is supported in the first milestone. */
    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getResultKey() {
        return resultKey;
    }

    /** Context key under which the result ({@code rows}, {@code rowCount}) is published. */
    public void setResultKey(String resultKey) {
        this.resultKey = resultKey;
    }
}
