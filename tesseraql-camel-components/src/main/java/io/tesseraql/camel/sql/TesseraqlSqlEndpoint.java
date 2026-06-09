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
    private int maxRows = -1;
    private String onOverflow = "fail";
    private String format = "csv";
    private String filename = "export.csv";

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

    public int getMaxRows() {
        return maxRows;
    }

    /** Maximum rows to materialize for {@code query} mode; {@code -1} means unbounded. */
    public void setMaxRows(int maxRows) {
        this.maxRows = maxRows;
    }

    public String getOnOverflow() {
        return onOverflow;
    }

    /** Behavior when {@code maxRows} is exceeded: {@code fail} (default) or {@code warn}. */
    public void setOnOverflow(String onOverflow) {
        this.onOverflow = onOverflow;
    }

    public String getFormat() {
        return format;
    }

    /** Export format for {@code query-export} mode. Only {@code csv} is supported initially. */
    public void setFormat(String format) {
        this.format = format;
    }

    public String getFilename() {
        return filename;
    }

    /** Suggested download filename for {@code query-export} mode. */
    public void setFilename(String filename) {
        this.filename = filename;
    }
}
