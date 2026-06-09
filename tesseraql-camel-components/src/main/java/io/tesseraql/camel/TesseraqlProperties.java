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
    public static final String PRINCIPAL = "TesseraqlPrincipal";
    public static final String TENANT = "TesseraqlTenant";
    public static final String ROUTE_SPAN = "TesseraqlRouteSpan";
    public static final String TENANT_DATASOURCE_RESOLVER_BEAN = "tesseraqlTenantDataSources";

    /** Registry bean names bound by the runtime for security components. */
    public static final String POLICY_ENGINE_BEAN = "tesseraqlPolicyEngine";
    public static final String JWT_AUTHENTICATOR_BEAN = "tesseraqlJwtAuthenticator";
    public static final String SESSION_STORE_BEAN = "tesseraqlSessionStore";
    public static final String TEMP_STORE_BEAN = "tesseraqlTempStore";
    public static final String IDEMPOTENCY_STORE_BEAN = "tesseraqlIdempotencyStore";
    public static final String OUTBOX_STORE_BEAN = "tesseraqlOutboxStore";
    public static final String IDENTITY_SERVICE_BEAN = "tesseraqlIdentityService";
    public static final String IDENTITY_REALM_BEAN = "tesseraqlIdentityRealm";
    public static final String TRACER_BEAN = "tesseraqlTracer";
    public static final String METER_BEAN = "tesseraqlMeter";
    public static final String LANES_BEAN = "tesseraqlExecutionLanes";

    /** Registry bean name for the {@code ExecutorService} backing a named execution lane. */
    public static String laneExecutorRef(String laneName) {
        return "tesseraqlLane." + laneName;
    }

    private TesseraqlProperties() {
    }
}
