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
    /** The computed page request a paginated query executes under (roadmap Phase 41). */
    public static final String PAGE = "TesseraqlPage";
    public static final String SQL_PARAMS = "TesseraqlSqlParams";
    public static final String PRINCIPAL = "TesseraqlPrincipal";
    /**
     * The authenticated browser session's CSRF token, stashed on {@code browser} authentication so
     * HTML responses can publish it as {@code <meta name="csrf-token">} for the Hypermedia
     * Components {@code installCsrfHeader} convention (design ch. 11.3).
     */
    public static final String CSRF_TOKEN = "TesseraqlCsrfToken";
    public static final String TENANT = "TesseraqlTenant";
    /** The resolved request locale as a BCP-47 language tag (roadmap Phase 22). */
    public static final String LOCALE = "TesseraqlLocale";
    public static final String ROUTE_SPAN = "TesseraqlRouteSpan";
    public static final String TRACE_CONTEXT = "TesseraqlTraceContext";
    /**
     * The trace and span ids as exchange properties, which is how they reach the log lines: the
     * MDC service copies these two into the thread's MDC around every processor call, so the
     * name here is also the key a log line carries. They are deliberately unprefixed — renaming
     * them would rename a field in every structured log line and every log query built on it.
     */
    public static final String TRACE_ID = "traceId";
    public static final String SPAN_ID = "spanId";
    /** The {@code FileCodec} a {@code query-export} route encodes its result with (ch. 28.10). */
    public static final String EXPORT_CODEC = "TesseraqlExportCodec";
    /** The {@code FileWriteSpec} (columns, formats, resolved locale/zone) for {@code query-export}. */
    public static final String EXPORT_SPEC = "TesseraqlExportSpec";
    /** The {@code ExportRowCap} a buffering codec's export runs under (export-pipeline, dec. 7). */
    public static final String EXPORT_ROW_CAP = "TesseraqlExportRowCap";
    /** The {@code ExportQuery} list an export composes around its rows (export-pipeline, dec. 2). */
    public static final String EXPORT_QUERIES = "TesseraqlExportQueries";
    /** An export's already-resolved {@code http:} source results (export-pipeline, dec. 2). */
    public static final String EXPORT_VALUES = "TesseraqlExportValues";

    /** The enrichment an export folds into its rows, a window at a time (docs/lookups.md). */
    public static final String EXPORT_ENRICHER = "TesseraqlExportEnricher";

    /** How many rows that enrichment takes per reference query — its {@code batchSize}. */
    public static final String EXPORT_ENRICH_WINDOW = "TesseraqlExportEnrichWindow";
    public static final String TENANT_DATASOURCE_RESOLVER_BEAN = "tesseraqlTenantDataSources";
    /** The data-scope resolver expanding {@code /*%scope%/} directives (roadmap Phase 29). */
    public static final String SCOPE_RESOLVER_BEAN = "tesseraqlScopeResolver";
    /** The file-scope resolver for {@code ${scope.*}} placeholders on duckdb datasources. */
    public static final String FILE_PATH_RESOLVER_BEAN = "tesseraqlFilePathResolver";
    /** The managed org-unit hierarchy store, bound in {@code managed} mode (roadmap Phase 29). */
    public static final String ORG_UNIT_STORE_BEAN = "tesseraqlOrgUnitStore";
    /** The approval-workflow state store, bound when an app declares workflows (roadmap Phase 28). */
    public static final String WORKFLOW_STORE_BEAN = "tesseraqlWorkflowStore";
    /** The approval-workflow task inbox store, bound when a workflow assigns tasks (Phase 28). */
    public static final String WORKFLOW_TASK_STORE_BEAN = "tesseraqlWorkflowTaskStore";
    /** The approval-workflow deadline sweeper, bound when a workflow declares deadlines (Phase 28). */
    public static final String WORKFLOW_SWEEPER_BEAN = "tesseraqlWorkflowSweeper";

    /** Registry bean names bound by the runtime for security components. */
    public static final String POLICY_ENGINE_BEAN = "tesseraqlPolicyEngine";
    public static final String JWT_AUTHENTICATOR_BEAN = "tesseraqlJwtAuthenticator";
    public static final String API_KEY_AUTHENTICATOR_BEAN = "tesseraqlApiKeyAuthenticator";
    public static final String MTLS_AUTHENTICATOR_BEAN = "tesseraqlMtlsAuthenticator";
    public static final String SESSION_STORE_BEAN = "tesseraqlSessionStore";

    /** The keyed credential throttle (docs/credential-throttle.md). */
    public static final String CREDENTIAL_THROTTLE_BEAN = "tesseraqlCredentialThrottle";

    /**
     * The app's resolved {@code tesseraql.security.responseHeaders} block, as a plain map.
     *
     * <p>Bound in the registry rather than threaded through constructors because the surfaces
     * that need it are the ones the compiler never sees — static assets and the SSE streams,
     * which write their own responses outside a compiled route.
     */
    public static final String RESPONSE_HEADERS_BEAN = "tesseraqlResponseHeaders";
    /**
     * The prefix the application is served under ({@code tesseraql.http.basePath}), normalized to
     * {@code ""} or {@code /a/b}. Read through {@link BasePath}.
     */
    public static final String BASE_PATH_BEAN = "tesseraqlBasePath";
    /**
     * The {@code Path} the session cookie is issued with, supplied by whatever started this
     * runtime rather than derived from the base path. Read through {@link CookiePath}.
     */
    public static final String COOKIE_PATH_BEAN = "tesseraqlCookiePath";
    public static final String TEMP_STORE_BEAN = "tesseraqlTempStore";
    public static final String IDEMPOTENCY_STORE_BEAN = "tesseraqlIdempotencyStore";
    public static final String OUTBOX_STORE_BEAN = "tesseraqlOutboxStore";
    /** The managed document-number sequence allocator (roadmap Phase 18). */
    public static final String DOCUMENT_SEQUENCES_BEAN = "tesseraqlDocumentSequences";
    /** The asynchronous file import/export service (design ch. 28). */
    public static final String FILE_TRANSFER_BEAN = "tesseraqlFileTransfers";
    /** The durable object store backing attachments (file default, roadmap Phase 30). */
    public static final String BLOB_STORE_BEAN = "tesseraqlBlobStore";
    /** The managed attachment-metadata store, bound in {@code managed} mode (roadmap Phase 30). */
    public static final String ATTACHMENT_STORE_BEAN = "tesseraqlAttachmentStore";
    /** The attachment upload/download service (roadmap Phase 30). */
    public static final String ATTACHMENT_SERVICE_BEAN = "tesseraqlAttachmentService";
    /** The inbound-webhook replay store (roadmap Phase 26). */
    public static final String WEBHOOK_REPLAY_STORE_BEAN = "tesseraqlWebhookReplayStore";
    /** The messaging channel event-log store backing the pg-notify transport (roadmap Phase 27). */
    public static final String EVENT_CHANNEL_STORE_BEAN = "tesseraqlEventChannelStore";
    /** Exchange header: the message key a queue-consume delivery carries (roadmap Phase 27). */
    public static final String QUEUE_MESSAGE_KEY = "TesseraqlQueueKey";
    /** Exchange property: the resolved idempotency key of a queue-consume delivery (Phase 27). */
    public static final String QUEUE_IDEM_KEY = "TesseraqlQueueIdemKey";
    /** Exchange property: set when a queue-consume delivery is a deduplicated replay (Phase 27). */
    public static final String QUEUE_DUPLICATE = "TesseraqlQueueDuplicate";
    /** An OutboxEventSink contributed by a runtime extension (e.g. SCIM outbound provisioning). */
    public static final String OUTBOX_EVENT_SINK_BEAN = "tesseraqlOutboxEventSink";
    /** The ServiceProviders registry backing the tesseraql-service component (design ch. 47). */
    public static final String SERVICE_PROVIDERS_BEAN = "tesseraqlServiceProviders";
    public static final String IDENTITY_SERVICE_BEAN = "tesseraqlIdentityService";
    public static final String IDENTITY_REALM_BEAN = "tesseraqlIdentityRealm";
    public static final String TRACER_BEAN = "tesseraqlTracer";
    public static final String METER_BEAN = "tesseraqlMeter";

    /** Registry bean name for the business-route audit sink (roadmap Phase 45), if enabled. */
    public static final String ROUTE_AUDIT_SINK_BEAN = "tesseraqlRouteAuditSink";
    /** Registry bean name for the per-user preference store (roadmap Phase 48), if enabled. */
    public static final String PREFERENCE_STORE_BEAN = "tesseraqlPreferenceStore";
    /** Marker bean: present when the bundled account surface is mounted (roadmap Phase 48). */
    public static final String ACCOUNT_SURFACE_BEAN = "tesseraqlAccountSurface";
    /** Registry bean name for the operator's default page theme (roadmap Phase 48), if set. */
    public static final String UI_THEME_BEAN = "tesseraqlUiTheme";
    /** Registry bean name for the app's neutral color ramp (docs/hypermedia-ui.md), if non-default. */
    public static final String UI_NEUTRAL_BEAN = "tesseraqlUiNeutral";
    /** Registry bean name for the app's control density (docs/hypermedia-ui.md), if non-default. */
    public static final String UI_DENSITY_BEAN = "tesseraqlUiDensity";
    /** Registry bean name for the in-app inbox store (roadmap Phase 49), if any inbox channel. */
    public static final String INBOX_STORE_BEAN = "tesseraqlInboxStore";

    /** The shared lease ledger behind cluster-scoped rate limits (docs/deployment.md). */
    public static final String RATE_BUDGET_BEAN = "tesseraqlRateBudget";
    /** The one gateway every outbound HTTP call leaves through (docs/lookups.md, decision 15). */
    public static final String OUTBOUND_GATEWAY_BEAN = "tesseraqlOutboundGateway";

    /** Registry bean name for the app's loaded code catalogs (docs/lookups.md, decision 8). */
    public static final String CATALOG_STORE_BEAN = "tesseraqlCatalogStore";

    /** The reserved context key the code catalogs are published under. */
    public static final String CODES = "codes";
    /** The live-view topic bus commands emit to after commit (docs/realtime.md). */
    public static final String TOPIC_BUS_BEAN = "tesseraqlTopicBus";
    /** Registry bean name for the TOTP enrollment store (roadmap Phase 50), with identity. */
    public static final String TOTP_STORE_BEAN = "tesseraqlTotpStore";
    /** Registry bean name for the absence-delegation store (roadmap Phase 52), with tasks. */
    public static final String DELEGATION_STORE_BEAN = "tesseraqlDelegationStore";
    /** Registry bean name for the pins/recents store (roadmap Phase 51), with the account. */
    public static final String SHORTCUT_STORE_BEAN = "tesseraqlShortcutStore";
    public static final String LANES_BEAN = "tesseraqlExecutionLanes";
    public static final String SLOW_SQL_LOG_BEAN = "tesseraqlSlowSqlLog";

    /** Registry bean name for the {@code ExecutorService} backing a named execution lane. */
    public static String laneExecutorRef(String laneName) {
        return "tesseraqlLane." + laneName;
    }

    private TesseraqlProperties() {
    }
}
