package io.tesseraql.runtime;

import com.zaxxer.hikari.HikariDataSource;
import io.tesseraql.operations.batch.JobExecution;
import io.tesseraql.operations.batch.JobExecutor;
import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.manifest.JobFile;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One way to execute a batch job, shared by every path that starts one (docs/boot-phases.md
 * slice 3): {@link #runOne} honours the declared datasource and per-tenant routing, and
 * {@link #chained} wraps it in light after-chaining (docs/batch-platform.md track D) for the
 * runner the ops console, the scheduler and the chain itself share. The embedder API
 * ({@code TesseraqlRuntime.runJob}) delegates to the same execution, so a job behaves
 * identically however it was started.
 */
final class JobRunners {

    private static final Logger LOG = LoggerFactory.getLogger(JobRunners.class);

    private JobRunners() {
    }

    /** The job {@code jobId} names, or the refusal every start path shares. */
    static JobFile require(Map<String, JobFile> jobs, String jobId) {
        JobFile jobFile = jobs.get(jobId);
        if (jobFile == null) {
            throw new IllegalArgumentException("Unknown job: " + jobId);
        }
        return jobFile;
    }

    /**
     * Whether a job runs on {@code main}: it names no datasource, or names {@code main}. The two
     * spellings mean the same thing (docs/capacity-defaults.md decision 5).
     */
    static boolean onMain(JobFile jobFile) {
        String declared = jobFile.definition().datasource();
        return declared == null || declared.isBlank() || "main".equals(declared);
    }

    /**
     * The pool a job's declared {@code datasource:} selects. A job on {@code main} runs on main's
     * {@code jobPool} where one is declared ({@code mainJobPool}), on {@code main} otherwise
     * (docs/capacity-defaults.md decision 5).
     */
    static javax.sql.DataSource jobDataSource(JobFile jobFile, javax.sql.DataSource main,
            Map<String, HikariDataSource> dataSources, javax.sql.DataSource mainJobPool) {
        String declared = jobFile.definition().datasource();
        if (onMain(jobFile)) {
            return mainJobPool != null ? mainJobPool : main;
        }
        javax.sql.DataSource pool = dataSources.get(declared);
        if (pool == null) {
            throw new IllegalArgumentException(
                    "Job datasource '" + declared + "' is not declared");
        }
        return pool;
    }

    /**
     * One execution honouring the job's declaration: the declared datasource
     * (docs/duckdb.md ETL) wins over {@code main}, and a {@code perTenant} job runs once per
     * configured tenant, each on its tenant pool and tenant context (design ch. 30.3),
     * returning the last execution. Per-tenant pool routing applies only to main-datasource
     * jobs — a duckdb engine is one per node, tenant isolation there comes from
     * tenant-partitioned file scopes. A job on {@code main} runs on the job pool: main's, or in a
     * per-tenant mode its tenant's (docs/capacity-defaults.md decisions 5 and 5a). A
     * {@code perTenant} app with no tenants configured runs once, like any other job.
     */
    static JobExecution runOne(JobFile jobFile, String owner, Map<String, Object> boundParams,
            String triggerType, String triggeredBy, javax.sql.DataSource dataSource,
            Map<String, HikariDataSource> dataSources, javax.sql.DataSource mainJobPool,
            AppConfig runtimeConfig, TenantDataSources tenantPools, JobExecutor jobExecutor) {
        javax.sql.DataSource jobPool = jobDataSource(jobFile, dataSource, dataSources,
                mainJobPool);
        if (jobFile.definition().perTenant()) {
            List<String> tenants = TenantRegistry.tenantIds(runtimeConfig, dataSource,
                    tenantPools);
            if (!tenants.isEmpty()) {
                JobExecution last = null;
                for (String tenantId : tenants) {
                    last = jobExecutor.run(jobFile,
                            onMain(jobFile)
                                    ? tenantPools.dataSourceFor(tenantId, jobPool,
                                            io.tesseraql.pipeline.tenant.PoolRole.JOBS)
                                    : jobPool,
                            io.tesseraql.core.tenant.TenantContext.of(tenantId),
                            owner, boundParams, triggerType, triggeredBy);
                }
                return last;
            }
        }
        return jobExecutor.run(jobFile, jobPool, owner, boundParams, triggerType, triggeredBy);
    }

    /**
     * The runner every trigger shares: the ops console, the scheduler, and the chain itself —
     * {@code trigger: { after: <jobId> } } fires a job when the named job's execution completes
     * successfully in the same app, carrying the business date so "extract, then send" runs the
     * same fact. Breadth-first with a fired-set, so a declared cycle (a lint error) cannot loop
     * at runtime; anything wider than a chain belongs to the external scheduler by design.
     *
     * <p>{@code jobs} and {@code jobOwners} are read live, on every run: the boot passes them
     * BEFORE the mounted-apps loop finishes filling them, so a defensive copy here would
     * silently make every mounted app's jobs unknown to the scheduler and the console. (The
     * runtime's own constructor copies them — after the loop.)
     */
    static OpsActions.JobRunner chained(Map<String, JobFile> jobs, Map<String, String> jobOwners,
            String appName, javax.sql.DataSource dataSource,
            Map<String, HikariDataSource> dataSources, javax.sql.DataSource mainJobPool,
            AppConfig runtimeConfig, TenantDataSources tenantPools, JobExecutor jobExecutor) {
        OpsActions.JobRunner runOne = (jobId, params, triggerType, triggeredBy) -> {
            JobFile jobFile = require(jobs, jobId);
            return runOne(jobFile, jobOwners.getOrDefault(jobId, appName),
                    TesseraqlRuntime.bindJobParams(jobFile, params), triggerType, triggeredBy,
                    dataSource, dataSources, mainJobPool, runtimeConfig, tenantPools,
                    jobExecutor);
        };
        return (jobId, params, triggerType, triggeredBy) -> {
            JobExecution execution = runOne.run(jobId, params, triggerType, triggeredBy);
            if (execution == null
                    || execution.status() != io.tesseraql.operations.batch.JobStatus.COMPLETED) {
                return execution;
            }
            Map<String, Object> chainedParams = execution.businessDate() == null
                    ? Map.of()
                    : Map.of("businessDate", execution.businessDate().toString());
            java.util.Set<String> fired = new java.util.LinkedHashSet<>();
            fired.add(jobId);
            java.util.ArrayDeque<String> completed = new java.util.ArrayDeque<>();
            completed.add(jobId);
            while (!completed.isEmpty()) {
                String parent = completed.poll();
                String parentOwner = jobOwners.getOrDefault(parent, appName);
                for (JobFile candidate : jobs.values()) {
                    String candidateId = candidate.definition().id();
                    io.tesseraql.yaml.model.TriggerSpec trigger = candidate.definition()
                            .trigger();
                    if (trigger == null || !parent.equals(trigger.after())
                            || !parentOwner.equals(jobOwners.getOrDefault(candidateId, appName))
                            || !fired.add(candidateId)) {
                        continue;
                    }
                    try {
                        JobExecution chained = runOne.run(candidateId, chainedParams, "after",
                                null);
                        if (chained != null && chained
                                .status() == io.tesseraql.operations.batch.JobStatus.COMPLETED) {
                            completed.add(candidateId);
                        }
                    } catch (RuntimeException chainFailure) {
                        // A broken link stops its own chain; the parent's success stands.
                        LOG.warn("Chained job {} (after {}) failed: {}", candidateId, parent,
                                chainFailure.getMessage());
                    }
                }
            }
            return execution;
        };
    }
}
