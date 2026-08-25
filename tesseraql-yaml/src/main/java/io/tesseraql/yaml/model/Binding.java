package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/**
 * One acquisition or one statement: the union every place that reads rows or writes them
 * declares (docs/unified-sources.md). Exactly one <em>arm</em> names the mechanism and nests
 * that mechanism's own keys — {@code sql} (a colocated 2-way SQL file), {@code contract} (a
 * named identity contract), {@code service} (a runtime service provider), {@code http} (a call
 * through the outbound gateway), or the write-side {@code sequence}:
 *
 * <pre>{@code
 * main:
 *   sql: { file: order.sql, mode: query, params: { id: path.id } }
 * rates:
 *   http: { url: ..., credential: fx-api, select: rates }
 * }</pre>
 *
 * <p>The arms grew one at a time and {@code http} did not join them: HTTP sources arrived as
 * a second top-level map, so at route level the <em>map</em> encoded the mechanism while
 * inside {@code enrich:} an exclusive key did. One union is what lets an HTTP source be a
 * source like any other — named, enrichable, composable — instead of a parallel vocabulary.
 *
 * <p>The record stays flat while the authoring form nests, bridged by {@link #of} — the same
 * split {@link HttpSourceSpec} already makes, so a key added to a mechanism reaches the
 * surface without a second declaration. Nesting is what makes a key's arm structural: there
 * is nowhere to write {@code select:} on a SQL binding, so no lint has to say it is wrong.
 *
 * @param file     the {@code .sql} file relative to the owning YAML file's directory
 * @param mode     execution mode: {@code query}, {@code query-one}, {@code update}, etc. (ch. 28.6)
 * @param params   mapping of bind name to a source expression such as {@code query.q} or
 *                 {@code principal.claim.tenant_id} (design ch. 6.3)
 * @param service  a named runtime service provider invoked instead of SQL, exposing non-SQL
 *                 runtime state (lanes, traces, file trees, ...) to the route (design ch. 47)
 * @param http     an outbound call whose response becomes this binding's rows, through the
 *                 one gateway every outbound call rides (deny-by-default hosts, named
 *                 credentials, timeouts, circuit breaker)
 * @param sequence a managed document-number sequence allocated instead of executing SQL; the
 *                 gapless value rides the command transaction's row lock (roadmap Phase 18)
 * @param keys     generated-key columns captured from an insert, published as
 *                 {@code <step>.keys.<column>} for later steps and the response
 * @param expect   declared row-count expectation turning silent lost updates into conflicts
 * @param timeoutSeconds per-binding SQL statement timeout override (roadmap Phase 45); the
 *                 global default is {@code tesseraql.sql.timeoutSeconds}, and {@code 0}
 *                 disables the guard for a deliberately long-running statement
 * @param datasource the named connector under {@code tesseraql.datasources} this read query runs
 *                 on (roadmap Phase 53), overriding the route's connector; legal only on read
 *                 bindings — a step inside a transactional pipeline cannot pick its own connector
 *                 ({@code TQL-YAML-1037}), because the pipeline is one transaction on one
 *                 connection
 * @param enrich   keyed references folded into this binding's rows before anything reads them
 *                 (docs/lookups.md), keyed by enrichment name and applied in authored order —
 *                 declared here rather than named by a back-reference, so any arm's rows can
 *                 be enriched (docs/unified-sources.md decision 5)
 * @param spool    a context path resolving to an earlier step's spool reference, read as this
 *                 binding's rows (docs/unified-sources.md decisions 19 and 19a). Spooling is
 *                 not a SQL feature — it is what a large result does on its way to a consumer
 *                 that reads it once — so whatever filled the spool, a reader has one thing to
 *                 understand
 * @param when     optional guard expression on a command step (docs/decision-tables.md "Acting
 *                 on the result"): a falsy guard skips the step, which records
 *                 {@code steps.<name>.skipped} instead of a result — the declared branch point
 *                 for decision outputs ("level 1 approves directly, others open a workflow")
 * @param out      the OUT parameters of a {@code mode: call} statement
 *                 (docs/sql-execution-shapes.md structural decision 7): each name to its
 *                 declared JDBC type keyword; the statement binds them as {@code out.<name>}
 *                 bind sites, and the values publish as {@code steps.<name>.out.<name>}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Binding(String file, String contract, String mode, Map<String, String> params,
        String service, HttpSourceSpec http, Materialize materialize, String sequence,
        java.util.List<String> keys, Expect expect, Integer timeoutSeconds, String datasource,
        String spool, String when, Map<String, EnrichSpec> enrich, Map<String, String> out,
        // The shared-fragment arm (docs/transactional-writes.md): this step is a named sequence,
        // expanded into ordinary steps at manifest load, so nothing downstream knows it was one.
        FragmentUse use) {

    public Binding {
        params = params == null ? Map.of() : Map.copyOf(params);
        keys = keys == null ? java.util.List.of() : java.util.List.copyOf(keys);
        enrich = enrich == null
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(enrich));
        out = out == null
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(out));
    }

    /** The shape before a step could be a shared fragment. */
    public Binding(String file, String contract, String mode, Map<String, String> params,
            String service, HttpSourceSpec http, Materialize materialize, String sequence,
            java.util.List<String> keys, Expect expect, Integer timeoutSeconds, String datasource,
            String spool, String when, Map<String, EnrichSpec> enrich, Map<String, String> out) {
        this(file, contract, mode, params, service, http, materialize, sequence, keys, expect,
                timeoutSeconds, datasource, spool, when, enrich, out, null);
    }

    /** The shape before a call step could declare OUT parameters. */
    public Binding(String file, String contract, String mode, Map<String, String> params,
            String service, HttpSourceSpec http, Materialize materialize, String sequence,
            java.util.List<String> keys, Expect expect, Integer timeoutSeconds, String datasource,
            String spool, String when, Map<String, EnrichSpec> enrich) {
        this(file, contract, mode, params, service, http, materialize, sequence, keys, expect,
                timeoutSeconds, datasource, spool, when, enrich, null, null);
    }

    /** The shape before an enrichment could nest under the source it transforms. */
    public Binding(String file, String contract, String mode, Map<String, String> params,
            String service, HttpSourceSpec http, Materialize materialize, String sequence,
            java.util.List<String> keys, Expect expect, Integer timeoutSeconds, String datasource,
            String when) {
        this(file, contract, mode, params, service, http, materialize, sequence, keys, expect,
                timeoutSeconds, datasource, null, when, null);
    }

    /**
     * The authoring form: one arm key whose value carries that mechanism's keys.
     *
     * <p>{@code sequence}, the step-level {@code when:} and {@code enrich:} sit beside the arm
     * rather than inside one — a guard is about whether the step runs at all, an enrichment is
     * about the rows whatever fetched them, and a sequence allocation has no body beyond its
     * name. None of the three is a question for the mechanism.
     */
    @com.fasterxml.jackson.annotation.JsonCreator
    static Binding of(
            @com.fasterxml.jackson.annotation.JsonProperty("sql") SqlArm sql,
            @com.fasterxml.jackson.annotation.JsonProperty("contract") NamedCall contract,
            @com.fasterxml.jackson.annotation.JsonProperty("service") NamedCall service,
            @com.fasterxml.jackson.annotation.JsonProperty("http") HttpSourceSpec http,
            @com.fasterxml.jackson.annotation.JsonProperty("sequence") String sequence,
            @com.fasterxml.jackson.annotation.JsonProperty("spool") String spool,
            @com.fasterxml.jackson.annotation.JsonProperty("when") String when,
            @com.fasterxml.jackson.annotation.JsonProperty("enrich") Map<String, EnrichSpec> enrich,
            @com.fasterxml.jackson.annotation.JsonProperty("use") FragmentUse use) {
        NamedCall call = contract != null ? contract : service;
        return new Binding(
                sql == null ? null : sql.file(),
                contract == null ? null : contract.name(),
                mode(sql, call, http),
                sql != null ? sql.params() : (call == null ? null : call.params()),
                service == null ? null : service.name(),
                http,
                sql == null ? null : sql.materialize(),
                sequence,
                sql == null ? null : sql.keys(),
                sql != null ? sql.expect() : (call == null ? null : call.expect()),
                sql == null ? null : sql.timeoutSeconds(),
                sql == null ? null : sql.datasource(),
                spool,
                when,
                enrich,
                sql == null ? null : sql.out(),
                use);
    }

    /** Whether this step is a reference to a shared fragment rather than a mechanism of its own. */
    public boolean usesFragment() {
        return use != null && use.fragment() != null && !use.fragment().isBlank();
    }

    /**
     * The mode the declared arm carries. Every arm has one — the legal values are the
     * mechanism's ({@code query} / {@code query-one} / {@code update} / {@code query-spool} for
     * SQL, {@code query} / {@code query-spool} for a call) — and the binding exposes the single
     * answer, so a reader asks "how does this acquisition deliver its rows" once rather than per
     * mechanism (docs/unified-sources.md decision 19a).
     */
    private static String mode(SqlArm sql, NamedCall call, HttpSourceSpec http) {
        if (sql != null) {
            return sql.mode();
        }
        if (call != null) {
            return call.mode();
        }
        return http == null ? null : http.mode();
    }

    /**
     * The {@code sql} arm: the file to run, and how. {@code file} is this arm's acquisition
     * target, the same role {@code url} plays for {@code http} — which is why both are checked
     * by the mechanism that owns them (a file must exist, a host must be allow-listed).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SqlArm(String file, String mode, Map<String, String> params,
            Materialize materialize, java.util.List<String> keys, Expect expect,
            Integer timeoutSeconds, String datasource, Map<String, String> out) {

        public SqlArm {
            // The arm is read directly wherever a slot holds one (an enrichment's reference, an
            // import's row write, an export's follow-up), so its collections normalize here as
            // the enclosing record's do — an absent params: is an empty map, not a null.
            params = params == null ? Map.of() : Map.copyOf(params);
            keys = keys == null ? java.util.List.of() : java.util.List.copyOf(keys);
            out = out == null
                    ? Map.of()
                    : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(out));
        }

        /** The shape before a call statement could declare OUT parameters. */
        public SqlArm(String file, String mode, Map<String, String> params,
                Materialize materialize, java.util.List<String> keys, Expect expect,
                Integer timeoutSeconds, String datasource) {
            this(file, mode, params, materialize, keys, expect, timeoutSeconds, datasource,
                    null);
        }

        /** A plain SQL file arm. */
        public static SqlArm of(String file) {
            return of(file, null, null);
        }

        /** A SQL file arm in a declared mode, with its binds. */
        public static SqlArm of(String file, String mode, Map<String, String> params) {
            return new SqlArm(file, mode, params, null, null, null, null, null);
        }
    }

    /**
     * The {@code contract} and {@code service} arms: the name to call, and how. A contract is
     * SQL the identity schema owns, so it reads or writes like any other statement and carries
     * {@code mode} and {@code expect}; a service provider answers rows from runtime state and
     * takes only its arguments.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NamedCall(String name, String mode, Map<String, String> params, Expect expect) {

        public NamedCall {
            params = params == null ? Map.of() : Map.copyOf(params);
        }
    }

    /** A plain SQL-file binding — the shape most bindings are. */
    public static Binding sql(String file) {
        return sql(file, null);
    }

    /** A SQL-file binding in a declared mode. */
    public static Binding sql(String file, String mode) {
        return sql(file, mode, null);
    }

    /** A SQL-file binding in a declared mode, with its binds. */
    public static Binding sql(String file, String mode, Map<String, String> params) {
        return new Binding(file, null, mode, params, null, null, null, null, null, null, null,
                null, null);
    }

    /** A binding that allocates a managed document-number sequence. */
    public static Binding sequence(String name) {
        return new Binding(null, null, null, null, null, null, null, name, null, null, null,
                null, null);
    }

    /** A binding that calls a named runtime service provider. */
    public static Binding service(String name, Map<String, String> params) {
        return new Binding(null, null, null, params, name, null, null, null, null, null, null,
                null, null);
    }

    /** The binding an already-parsed SQL arm stands for, for the paths that need one. */
    public static Binding sql(SqlArm arm) {
        return arm == null
                ? null
                : new Binding(arm.file(), null, arm.mode(), arm.params(), null,
                        null, arm.materialize(), null, arm.keys(), arm.expect(),
                        arm.timeoutSeconds(),
                        arm.datasource(), null);
    }

    /** A binding whose rows come from an outbound call. */
    public static Binding http(HttpSourceSpec source) {
        return new Binding(null, null, null, null, null, source, null, null, null, null, null,
                null, null);
    }

    /** Whether this binding runs a colocated 2-way SQL file. */
    public boolean isSql() {
        return file != null && !file.isBlank();
    }

    /** Whether this binding executes a named Identity SQL Contract instead of a SQL file. */
    public boolean isContract() {
        return contract != null && !contract.isBlank();
    }

    /** Whether this binding allocates a managed document-number sequence instead of SQL. */
    public boolean isSequence() {
        return sequence != null && !sequence.isBlank();
    }

    /** Whether this binding invokes a named runtime service provider instead of SQL. */
    public boolean isService() {
        return service != null && !service.isBlank();
    }

    /** Whether this binding reads an earlier step's spool rather than executing anything. */
    public boolean isSpool() {
        return spool != null && !spool.isBlank();
    }

    /**
     * Whether an {@code http:} arm was declared at all. A call missing its url is still an
     * HTTP binding — a misdeclared one, which the gateway lints report as such; treating it as
     * no arm would answer "this step declares no work", which is not the author's mistake.
     */
    public boolean declaresHttp() {
        return http != null;
    }

    /** Whether this binding's rows come from an outbound call rather than a datasource. */
    public boolean isHttp() {
        return http != null && http.call() != null && http.call().url() != null
                && !http.call().url().isBlank();
    }

    /** Returns the effective mode, defaulting to {@code query}. */
    public String effectiveMode() {
        return mode == null || mode.isBlank() ? "query" : mode;
    }

    /**
     * Per-route result materialization guard (design ch. 28.7).
     *
     * @param maxRows    maximum rows that may be materialized in memory
     * @param onOverflow behavior when exceeded: {@code fail} (default) or {@code warn}
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Materialize(Integer maxRows, String onOverflow) {
    }

    /**
     * Declared row-count expectation for a command statement (roadmap Phase 18). A mismatch turns
     * a silent lost update into an explicit error instead of reporting success.
     *
     * @param rowCount   the exact number of rows the statement must affect
     * @param onMismatch {@code conflict} (default, HTTP 409 with a conflict hint) or
     *                   {@code error} (HTTP 500)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Expect(Integer rowCount, String onMismatch) {

        /** Returns the effective mismatch behavior, defaulting to {@code conflict}. */
        public String effectiveOnMismatch() {
            return onMismatch == null || onMismatch.isBlank() ? "conflict" : onMismatch;
        }
    }
}
