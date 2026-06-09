package io.tesseraql.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.compiler.binding.ErrorResponseRenderer;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.operations.batch.JobExecution;
import io.tesseraql.operations.batch.JobRepository;
import io.tesseraql.operations.batch.StepExecution;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;

/**
 * Builds the Operations API for batch jobs under {@code /_tesseraql/ops/batch} (design ch. 26.7,
 * 43.7). All endpoints require a bearer principal and a {@code ops.batch.*} policy.
 */
final class OperationsRouteBuilder extends RouteBuilder {

    private static final String VIEW = "tesseraql-auth:authenticate?auth=bearer";
    private final ObjectMapper mapper = new ObjectMapper();
    private final JobRunner runner;
    private final JobRepository repository;
    private final List<String> jobIds;
    private final io.tesseraql.opsui.OpsDashboard dashboard;

    /** Runs a job by id; decouples the route builder from the runtime instance. */
    @FunctionalInterface
    interface JobRunner {
        JobExecution run(String jobId, Map<String, Object> params);
    }

    OperationsRouteBuilder(JobRunner runner, JobRepository repository, List<String> jobIds,
            io.tesseraql.opsui.OpsDashboard dashboard) {
        this.runner = runner;
        this.repository = repository;
        this.jobIds = List.copyOf(jobIds);
        this.dashboard = dashboard;
    }

    @Override
    public void configure() {
        onException(TqlException.class).handled(true).process(new ErrorResponseRenderer());
        onException(Exception.class).handled(true).process(new ErrorResponseRenderer());

        rest().get("/_tesseraql/ops/batch/jobs").to("direct:ops.batch.jobs");
        rest().get("/_tesseraql/ops/batch/executions").to("direct:ops.batch.executions");
        rest().get("/_tesseraql/ops/batch/executions/{id}").to("direct:ops.batch.executionDetail");
        rest().post("/_tesseraql/ops/batch/jobs/{jobId}/run").to("direct:ops.batch.run");
        rest().get("/_tesseraql/ops/overview").to("direct:ops.overview");
        rest().get("/_tesseraql/ops/lanes").to("direct:ops.lanes");

        from("direct:ops.batch.jobs").routeId("ops.batch.jobs")
                .to(VIEW).to("tesseraql-auth:authorize?policy=ops.batch.view")
                .process(jsonProcessor(exchange -> jobIds));

        from("direct:ops.batch.executions").routeId("ops.batch.executions")
                .to(VIEW).to("tesseraql-auth:authorize?policy=ops.batch.view")
                .process(jsonProcessor(exchange ->
                        repository.listExecutions(50).stream().map(this::executionMap).toList()));

        from("direct:ops.batch.executionDetail").routeId("ops.batch.executionDetail")
                .to(VIEW).to("tesseraql-auth:authorize?policy=ops.batch.view")
                .process(jsonProcessor(this::executionDetail));

        from("direct:ops.batch.run").routeId("ops.batch.run")
                .to(VIEW).to("tesseraql-auth:authorize?policy=ops.batch.run")
                .process(jsonProcessor(this::runJob));

        from("direct:ops.overview").routeId("ops.overview")
                .to(VIEW).to("tesseraql-auth:authorize?policy=ops.batch.view")
                .process(jsonProcessor(exchange -> dashboard.overview(20)));

        from("direct:ops.lanes").routeId("ops.lanes")
                .to(VIEW).to("tesseraql-auth:authorize?policy=ops.batch.view")
                .process(jsonProcessor(exchange -> dashboard.overview(0).lanes()));
    }

    private Object runJob(Exchange exchange) {
        String jobId = exchange.getMessage().getHeader("jobId", String.class);
        Map<String, Object> params = parseBody(exchange);
        JobExecution execution = runner.run(jobId, params);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("executionId", execution.id());
        result.put("status", execution.status().name());
        return result;
    }

    private Object executionDetail(Exchange exchange) {
        String id = exchange.getMessage().getHeader("id", String.class);
        JobExecution execution = repository.findExecution(id).orElse(null);
        if (execution == null) {
            return Map.of("error", Map.of("code", "TQL-BATCH-4040", "message", "Not Found"));
        }
        Map<String, Object> detail = executionMap(execution);
        List<Object> steps = new ArrayList<>();
        for (StepExecution step : repository.findSteps(id)) {
            steps.add(stepMap(step));
        }
        detail.put("steps", steps);
        return detail;
    }

    private Map<String, Object> executionMap(JobExecution execution) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", execution.id());
        map.put("jobId", execution.jobId());
        map.put("status", execution.status().name());
        map.put("triggerType", execution.triggerType());
        map.put("startTime", execution.startTime() == null ? null : execution.startTime().toString());
        map.put("endTime", execution.endTime() == null ? null : execution.endTime().toString());
        map.put("durationMs", execution.durationMs());
        map.put("exitMessage", execution.exitMessage());
        return map;
    }

    private Map<String, Object> stepMap(StepExecution step) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", step.id());
        map.put("stepId", step.stepId());
        map.put("status", step.status().name());
        map.put("affectedRows", step.affectedRows());
        map.put("durationMs", step.durationMs());
        map.put("errorMessage", step.errorMessage());
        return map;
    }

    private Map<String, Object> parseBody(Exchange exchange) {
        String raw = exchange.getMessage().getBody(String.class);
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = mapper.readValue(raw, Map.class);
            return parsed == null ? Map.of() : parsed;
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            return Map.of();
        }
    }

    private Processor jsonProcessor(java.util.function.Function<Exchange, Object> handler) {
        return exchange -> {
            Object body = handler.apply(exchange);
            exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 200);
            exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json; charset=utf-8");
            exchange.getMessage().setBody(mapper.writeValueAsString(body));
        };
    }
}
