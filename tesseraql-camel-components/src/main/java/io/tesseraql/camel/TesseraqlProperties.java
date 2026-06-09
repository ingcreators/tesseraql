package io.tesseraql.camel;

/**
 * Camel {@code Exchange} property names shared between TesseraQL processors and components.
 *
 * <p>The {@link #CONTEXT} property holds the execution context map ({@code query}, {@code params},
 * {@code principal}, and SQL results keyed by {@code resultKey}) used to resolve response and SQL
 * parameter expressions. {@link #SQL_PARAMS} holds the resolved bind values for the SQL component.
 */
public final class TesseraqlProperties {

    public static final String CONTEXT = "TesseraqlContext";
    public static final String SQL_PARAMS = "TesseraqlSqlParams";

    private TesseraqlProperties() {
    }
}
