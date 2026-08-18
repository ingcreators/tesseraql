package io.tesseraql.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.operations.batch.JobExecution;
import io.tesseraql.operations.batch.JobExecutor;
import io.tesseraql.operations.batch.JobRepository;
import io.tesseraql.operations.batch.JobStatus;
import io.tesseraql.operations.batch.StepExecution;
import io.tesseraql.operations.batch.StepStatus;
import io.tesseraql.report.DriverManagerDataSource;
import io.tesseraql.yaml.calendar.Calendars;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.JobFile;
import io.tesseraql.yaml.manifest.ManifestLoader;
import io.tesseraql.yaml.model.CalendarsDocument;
import io.tesseraql.yaml.model.TriggerSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import javax.sql.DataSource;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code tesseraql job <list|run|rerun> --app <dir>}: the external-scheduler execution contract
 * (docs/batch-platform.md track D). {@code run} executes a job in-process — manifest plus
 * datasource config, no server — waits, prints the execution id and per-step summary, and exits
 * with a code a scheduler can branch on: <b>0</b> COMPLETED, <b>1</b> FAILED, <b>3</b> the
 * business-day calendar filtered the run out (distinct, so "holiday" can be success-with-note),
 * and <b>2</b> for a request that cannot run at all (unknown job or execution). {@code rerun}
 * starts a new execution with the source run's recorded parameters and business date — chunk
 * checkpoints make it resume where the failure stopped — and {@code --from-failed-step}
 * additionally records the source's completed steps as SKIPPED. A completed run fires
 * {@code trigger: after:} chains exactly as the serving runtime does.
 */
@Command(name = "job", description = "List, run, or rerun batch jobs in-process"
        + " (exit 0 completed, 1 failed, 3 calendar-filtered).")
final class JobCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "list, run, rerun, or cancel.")
    String operation;

    @Parameters(index = "1", arity = "0..1", description = "The job id (run) or execution id (rerun/cancel).")
    String target;

    @Option(names = {"--app"}, required = true, description = "Path to the external app home.")
    Path app;

    @Mixin
    ConnectionOptions datasource;

    @Option(names = {"--param"}, description = "Job parameter as name=value; repeatable.")
    List<String> params = List.of();

    @Option(names = {"--business-date"}, description = "The business date the run is for"
            + " (ISO yyyy-MM-dd; default: today). Shorthand for --param businessDate=….")
    String businessDate;

    @Option(names = {"--ignore-calendar"}, description = "Run even when the job's business-day"
            + " calendar filters the date out.")
    boolean ignoreCalendar;

    @Option(names = {"--from-failed-step"}, description = "rerun only: record the source"
            + " execution's completed steps as SKIPPED and start at its first failure.")
    boolean fromFailedStep;

    @Mixin
    CompileOptions compile;

    @Mixin
    ConfigOptions configOptions;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public Integer call() throws Exception {
        configOptions.apply();
        CliModules.installAppExtensions(app, compile.modules);
        AppManifest manifest = new ManifestLoader().load(app);
        Map<String, JobFile> jobs = new LinkedHashMap<>();
        manifest.jobs().forEach(job -> jobs.put(job.definition().id(), job));
        return switch (operation.toLowerCase(Locale.ROOT)) {
            case "list" -> list(jobs);
            case "run" -> run(manifest, jobs);
            case "rerun" -> rerun(manifest, jobs);
            case "cancel" -> cancel(manifest);
            default -> {
                System.err.println("Unknown operation '" + operation + "': use list, run,"
                        + " rerun, or cancel");
                yield 2;
            }
        };
    }

    /**
     * Requests a cooperative stop of a running execution — the flag travels through the
     * shared database, so it reaches a run on any node (or another terminal's in-process
     * run) and takes effect at its next step or chunk-commit boundary.
     */
    private Integer cancel(AppManifest manifest) throws Exception {
        if (target == null || target.isBlank()) {
            System.err.println("job cancel needs an execution id");
            return 2;
        }
        Wiring wiring = wire(manifest);
        JobExecution execution = wiring.repository().findExecution(target).orElse(null);
        if (execution == null) {
            System.err.println("Unknown execution '" + target + "'");
            return 2;
        }
        if (!wiring.repository().requestCancel(target)) {
            System.err.println("Execution " + target + " is " + execution.status()
                    + " — only a RUNNING execution can be stopped");
            return 2;
        }
        System.out.println("Cancel requested for execution " + target
                + " — takes effect at its next step or chunk-commit boundary");
        return 0;
    }

    private Integer list(Map<String, JobFile> jobs) {
        if (jobs.isEmpty()) {
            System.out.println("No jobs declared under batch/");
            return 0;
        }
        System.out.printf("%-40s %-16s %-12s %s%n", "JOB", "RECIPE", "DATASOURCE", "TRIGGER");
        for (JobFile job : jobs.values()) {
            String declared = job.definition().datasource();
            System.out.printf("%-40s %-16s %-12s %s%n",
                    job.definition().id(),
                    job.definition().recipe() == null ? "-" : job.definition().recipe(),
                    declared == null || declared.isBlank() ? "main" : declared,
                    TriggerSpec.describe(job.definition().trigger()));
        }
        return 0;
    }

    private Integer run(AppManifest manifest, Map<String, JobFile> jobs) throws Exception {
        if (target == null || target.isBlank()) {
            System.err.println("job run needs a job id");
            return 2;
        }
        JobFile job = jobs.get(target);
        if (job == null) {
            System.err.println("Unknown job '" + target + "' — `tesseraql job list --app "
                    + app + "` shows what is declared");
            return 2;
        }
        Map<String, Object> runParams = new LinkedHashMap<>();
        for (String param : params) {
            int eq = param.indexOf('=');
            if (eq < 1) {
                System.err.println("--param must be name=value (was '" + param + "')");
                return 2;
            }
            runParams.put(param.substring(0, eq), param.substring(eq + 1));
        }
        if (businessDate != null && !businessDate.isBlank()) {
            runParams.put("businessDate", businessDate);
        }
        LocalDate effectiveDate = runParams.get("businessDate") == null
                ? LocalDate.now()
                : LocalDate.parse(String.valueOf(runParams.get("businessDate")));

        Wiring wiring = wire(manifest);
        // The calendar gate (docs/jobs.md "Business-day calendars"): the scheduler surface is
        // exactly where "holiday" must be a distinct answer, so `run` consults the calendar
        // the way a scheduled firing does — and exit 3 says filtered, not failed.
        if (!ignoreCalendar) {
            CalendarDecision decision = decide(manifest, job, effectiveDate, wiring);
            if (!decision.counts()) {
                System.out.println("Filtered: " + effectiveDate
                        + " does not count under calendar '"
                        + job.definition().trigger().schedule().calendar()
                        + "' — exit 3 (use --ignore-calendar to force)");
                return 3;
            }
            // A shifted nominal-day rule names the date the run is FOR: the 5th's close,
            // executed on its shifted business day, records the 5th.
            if (decision.nominal() != null) {
                runParams.put("businessDate", decision.nominal().toString());
            }
        }
        return execute(manifest, jobs, wiring, job, runParams, "manual", Set.of());
    }

    private Integer rerun(AppManifest manifest, Map<String, JobFile> jobs) throws Exception {
        if (target == null || target.isBlank()) {
            System.err.println("job rerun needs an execution id");
            return 2;
        }
        Wiring wiring = wire(manifest);
        JobExecution source = wiring.repository().findExecution(target).orElse(null);
        if (source == null) {
            System.err.println("Unknown execution '" + target + "'");
            return 2;
        }
        JobFile job = jobs.get(source.jobId());
        if (job == null) {
            System.err.println("Execution " + target + " ran job '" + source.jobId()
                    + "', which this app no longer declares");
            return 2;
        }
        // The rerun re-runs the same fact: the source's recorded parameters and business date,
        // not whatever today would default (docs/batch-platform.md track A+D).
        Map<String, Object> runParams = new LinkedHashMap<>();
        String recorded = wiring.repository().findExecutionParams(target).orElse(null);
        if (recorded != null && !recorded.isBlank()) {
            MAPPER.readTree(recorded).properties().forEach(
                    entry -> runParams.put(entry.getKey(), entry.getValue().asText()));
        }
        if (source.businessDate() != null) {
            runParams.put("businessDate", source.businessDate().toString());
        }
        Set<String> skipSteps = new LinkedHashSet<>();
        if (fromFailedStep) {
            for (StepExecution step : wiring.repository().findSteps(target)) {
                if (step.status() == StepStatus.COMPLETED) {
                    skipSteps.add(step.stepId());
                }
            }
        }
        return execute(manifest, jobs, wiring, job, runParams, "rerun", skipSteps);
    }

    /** Runs the job, prints its summary, fires {@code after:} chains, and maps the exit code. */
    private Integer execute(AppManifest manifest, Map<String, JobFile> jobs, Wiring wiring,
            JobFile job, Map<String, Object> runParams, String triggerType,
            Set<String> skipSteps) {
        JobExecution execution = runOne(manifest, wiring, job, runParams, triggerType,
                skipSteps);
        print(wiring, execution);
        if (execution.status() == JobStatus.SKIPPED) {
            // Did not run by policy (overlap: skip) — the same scheduler answer as a
            // calendar-filtered date: not a success, not a failure.
            return 3;
        }
        boolean chainFailed = false;
        if (execution.status() == JobStatus.COMPLETED) {
            chainFailed = chain(manifest, jobs, wiring, job.definition().id(), execution);
        }
        return execution.status() == JobStatus.COMPLETED && !chainFailed ? 0 : 1;
    }

    private JobExecution runOne(AppManifest manifest, Wiring wiring, JobFile job,
            Map<String, Object> runParams, String triggerType, Set<String> skipSteps) {
        Map<String, Object> bound = job.definition().input().isEmpty()
                ? runParams
                : io.tesseraql.compiler.binding.InputBinder.bind(job.definition().input(),
                        name -> runParams.get(name) == null
                                ? null
                                : String.valueOf(runParams.get(name)),
                        runParams::get, Locale.ENGLISH);
        return wiring.executor().run(job, jobDataSource(manifest, job, wiring), null,
                wiring.appName(), bound, triggerType, null, skipSteps);
    }

    /**
     * Fires {@code trigger: after:} chains breadth-first with a fired-set, the serving
     * runtime's semantics; a scheduler must hear about a broken link, so a failed chained job
     * flips the exit code even though the parent's success stands.
     */
    private boolean chain(AppManifest manifest, Map<String, JobFile> jobs, Wiring wiring,
            String rootJobId, JobExecution rootExecution) {
        boolean anyFailed = false;
        Set<String> fired = new LinkedHashSet<>();
        fired.add(rootJobId);
        ArrayDeque<String> completed = new ArrayDeque<>();
        completed.add(rootJobId);
        while (!completed.isEmpty()) {
            String parent = completed.poll();
            for (JobFile candidate : jobs.values()) {
                TriggerSpec trigger = candidate.definition().trigger();
                if (trigger == null || !parent.equals(trigger.after())
                        || !fired.add(candidate.definition().id())) {
                    continue;
                }
                Map<String, Object> chainedParams = rootExecution.businessDate() == null
                        ? Map.of()
                        : Map.of("businessDate", rootExecution.businessDate().toString());
                JobExecution chained = runOne(manifest, wiring, candidate, chainedParams,
                        "after", Set.of());
                print(wiring, chained);
                if (chained.status() == JobStatus.COMPLETED) {
                    completed.add(candidate.definition().id());
                } else {
                    anyFailed = true;
                }
            }
        }
        return anyFailed;
    }

    private void print(Wiring wiring, JobExecution execution) {
        System.out.println("Execution " + execution.id() + ": " + execution.jobId() + " "
                + execution.status()
                + (execution.businessDate() == null
                        ? ""
                        : " (business date " + execution.businessDate() + ")")
                + (execution.exitMessage() == null ? "" : " — " + execution.exitMessage()));
        for (StepExecution step : wiring.repository().findSteps(execution.id())) {
            System.out.printf("  %-24s %-10s rows=%s%s%n", step.stepId(), step.status(),
                    step.affectedRows() == null ? "-" : step.affectedRows(),
                    step.skippedRows() == null || step.skippedRows() == 0
                            ? ""
                            : " skipped=" + step.skippedRows());
        }
    }

    /**
     * @param counts  whether the effective date counts under the job's calendar
     * @param nominal the nominal date a shifted {@code dayOfMonth:} rule runs for, when the
     *                effective date is its shifted target
     */
    private record CalendarDecision(boolean counts, LocalDate nominal) {
    }

    /** The job's business-day calendar applied to {@code date} (fail-open on errors). */
    private CalendarDecision decide(AppManifest manifest, JobFile job, LocalDate date,
            Wiring wiring) {
        TriggerSpec trigger = job.definition().trigger();
        TriggerSpec.Schedule schedule = trigger == null ? null : trigger.schedule();
        if (schedule == null || schedule.calendar() == null || schedule.calendar().isBlank()) {
            return new CalendarDecision(true, null);
        }
        Calendars calendars = Calendars.load(app, new io.tesseraql.yaml.SimpleYamlParser());
        CalendarsDocument.Calendar calendar = calendars.calendars().get(schedule.calendar());
        if (calendar == null) {
            // Says so, like the read-failure arm below always did: running unfiltered is the
            // fail-open stance, but the operator has to be told the gate was skipped.
            System.err.println("Calendar '" + schedule.calendar()
                    + "' is not declared; running unfiltered");
            return new CalendarDecision(true, null);
        }
        try {
            Set<LocalDate> holidays;
            if (calendar.holidays() != null && calendar.holidays().source() != null) {
                try (java.sql.Connection connection = jobDataSource(manifest, job, wiring)
                        .getConnection()) {
                    holidays = Calendars.readHolidays(connection, schedule.calendar(),
                            calendar.holidays().source());
                }
            } else {
                holidays = Calendars.staticHolidays(calendar);
            }
            if (schedule.dayOfMonth() != null) {
                LocalDate nominal = Calendars.shiftedNominal(calendar, schedule.dayOfMonth(),
                        schedule.shift(), date, holidays);
                return new CalendarDecision(nominal != null, nominal);
            }
            return new CalendarDecision(
                    Calendars.counts(calendar, schedule.runOn(), date, holidays), null);
        } catch (Exception ex) {
            System.err.println("Calendar '" + schedule.calendar()
                    + "' could not be resolved; running unfiltered: " + ex.getMessage());
            return new CalendarDecision(true, null);
        }
    }

    /** The job's datasource: its declared connector's config, or the CLI/main datasource. */
    private DataSource jobDataSource(AppManifest manifest, JobFile job, Wiring wiring) {
        String declared = job.definition().datasource();
        if (declared == null || declared.isBlank() || "main".equals(declared)) {
            return wiring.mainDataSource();
        }
        String prefix = "tesseraql.datasources." + declared;
        String url = manifest.config().getString(prefix + ".jdbcUrl").orElseThrow(
                () -> new IllegalArgumentException("Job datasource '" + declared
                        + "' declares no " + prefix + ".jdbcUrl"));
        return new DriverManagerDataSource(url,
                manifest.config().getString(prefix + ".username").orElse(null),
                manifest.config().getString(prefix + ".password").orElse(null));
    }

    /** The in-process wiring `serve` boots, reduced to what a single run needs. */
    private Wiring wire(AppManifest manifest) throws Exception {
        DriverManagerDataSource main = datasource.resolve(manifest.config(), app);
        JobRepository repository = new JobRepository(main);
        repository.ensureSchema();
        io.tesseraql.operations.outbox.JdbcOutboxStore outbox = new io.tesseraql.operations.outbox.JdbcOutboxStore(
                main);
        outbox.ensureSchema();
        Path scratch = io.tesseraql.yaml.config.WorkHome.resolve(app, manifest.config())
                .resolve("tmp").resolve("tesseraql");
        Files.createDirectories(scratch);
        io.tesseraql.core.spool.FileTempStore tempStore = new io.tesseraql.core.spool.FileTempStore(
                scratch);
        // export: steps write through the same transfer machinery `serve` wires
        // (docs/analytics-experience.md track 3), so a CLI-run job records the same
        // execution + transfer rows and the console download works either way.
        io.tesseraql.operations.files.JdbcFileTransferService transfers = new io.tesseraql.operations.files.JdbcFileTransferService(
                repository, tempStore, main, io.tesseraql.core.files.FileCodecs.discover());
        transfers.sqlTimeoutSeconds(manifest.config().getString("tesseraql.sql.timeoutSeconds")
                .map(Integer::parseInt).orElse(30));
        transfers.ensureSchema();
        JobExecutor executor = new JobExecutor(repository, tempStore)
                .sqlTimeoutSeconds(manifest.config().getString("tesseraql.sql.timeoutSeconds")
                        .map(Integer::parseInt).orElse(30))
                // notify: steps enqueue on the durable outbox; the serving runtime delivers.
                .notificationOutbox(outbox)
                .fileTransfers(transfers, app)
                // push: steps deliver through an owned, lazily-started Camel context — a run
                // without a push step never pays for it, and the JVM exits with the command.
                .filePush(new io.tesseraql.runtime.FilePushService(
                        io.tesseraql.yaml.connectors.FileConnectors.push(manifest.config()),
                        app)::push)
                .httpCall(new io.tesseraql.operations.http.HttpCallClient(
                        io.tesseraql.yaml.http.HttpOutbound.load(manifest.config()),
                        manifest.config(), io.tesseraql.core.telemetry.NoopTracer.INSTANCE,
                        io.tesseraql.core.telemetry.NoopMeter.INSTANCE));
        String appName = manifest.config().getString("tesseraql.app.name").orElse("app");
        return new Wiring(main, repository, executor, appName);
    }

    private record Wiring(DriverManagerDataSource mainDataSource, JobRepository repository,
            JobExecutor executor, String appName) {
    }
}
