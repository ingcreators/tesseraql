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
 * Builds the runtime's job runner (docs/boot-phases.md slice 3): one execution honouring the
 * declared datasource and per-tenant routing, wrapped in light after-chaining
 * (docs/batch-platform.md track D) - {@code trigger: { after: <jobId> } } fires a job when the
 * named job's execution completes successfully in the same app, carrying the business date so
 * "extract, then send" runs the same fact. Breadth-first with a fired-set, so a declared cycle
 * (a lint error) cannot loop at runtime; anything wider than a chain belongs to the external
 * scheduler by design. Extracted verbatim from {@code TesseraqlRuntime.start(...)}.
 */
final class JobRunners {

    private static final Logger LOG = LoggerFactory.getLogger(JobRunners.class);

    private JobRunners() {
    }

    /** The runner every trigger shares: the ops console, the scheduler, and the chain itself. */
    static OpsActions.JobRunner chained(Map<String, JobFile> jobs, Map<String, String> jobOwners,
            String appName, javax.sql.DataSource dataSource,
            Map<String, HikariDataSource> dataSources, AppConfig runtimeConfig,
            TenantDataSources tenantPools, JobExecutor jobExecutor) {
        OpsActions.JobRunner runOne = (jobId, params, triggerType, triggeredBy) -> {
            JobFile jobFile = jobs.get(jobId);
            if (jobFile == null) {
                throw new IllegalArgumentException("Unknown job: " + jobId);
            }
            Map<String, Object> boundParams = TesseraqlRuntime.bindJobParams(jobFile, params);
            String owner = jobOwners.getOrDefault(jobId, appName);
            // A job's declared datasource: (docs/duckdb.md ETL) wins over main; per-tenant pool
            // routing applies only to main-datasource jobs (a duckdb engine is one per node,
            // tenant isolation there comes from tenant-partitioned file scopes).
            String declared = jobFile.definition().datasource();
            javax.sql.DataSource jobPool;
            if (declared == null || declared.isBlank() || "main".equals(declared)) {
                jobPool = dataSource;
            } else {
                jobPool = dataSources.get(declared);
                if (jobPool == null) {
                    throw new IllegalArgumentException(
                            "Job datasource '" + declared + "' is not declared");
                }
            }
            if (jobFile.definition().perTenant()) {
                List<String> tenants = TenantRegistry.tenantIds(runtimeConfig, dataSource,
                        tenantPools);
                if (!tenants.isEmpty()) {
                    JobExecution last = null;
                    for (String tenantId : tenants) {
                        last = jobExecutor.run(jobFile,
                                jobPool == dataSource
                                        ? tenantPools.dataSourceFor(tenantId, dataSource)
                                        : jobPool,
                                io.tesseraql.core.tenant.TenantContext.of(tenantId),
                                owner, boundParams, triggerType, triggeredBy);
                    }
                    return last;
                }
            }
            return jobExecutor.run(jobFile, jobPool, owner, boundParams, triggerType, triggeredBy);
        };
        // Light chaining (docs/batch-platform.md track D): trigger: { after: <jobId> } fires a
        // job when the named job's execution completes successfully in the same app, carrying
        // the business date so "extract, then send" runs the same fact. Breadth-first with a
        // fired-set, so a declared cycle (a lint error) cannot loop at runtime; anything wider
        // than a chain belongs to the external scheduler by design.
        return (jobId, params, triggerType, triggeredBy) -> {
            JobExecution execution = runOne.run(jobId, params, triggerType, triggeredBy);
            if (execution == null
                    || execution.status() != io.tesseraql.operations.batch.JobStatus.COMPLETED) {
                return execution;
            }
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
                    Map<String, Object> chainedParams = execution.businessDate() == null
                            ? Map.of()
                            : Map.of("businessDate", execution.businessDate().toString());
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
