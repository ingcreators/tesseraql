package io.tesseraql.test;

import io.tesseraql.test.TestSuite.TestCase;
import io.tesseraql.yaml.manifest.RouteFile;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The {@code httpCall} case kind: a job's or route's outbound calls, planned or really sent. */
final class HttpCallCases {

    private final SuiteContext context;

    HttpCallCases(SuiteContext context) {
        this.context = context;
    }

    /**
     * Plans a job's {@code http:} steps against the case's params (roadmap Phase 26), without
     * issuing a network request: each matching step is one row carrying its id, method, the resolved
     * url and host, whether the host is allow-listed, and the credential name. URL placeholders and
     * query bindings resolve exactly as they would at runtime, so a case exercises the binding and
     * the deny-by-default egress rule deterministically.
     */
    List<Map<String, Object>> evaluate(TestCase test) {
        TestSuite.HttpCallTarget target = test.httpCall();
        boolean hasJob = target.job() != null && !target.job().isBlank();
        boolean hasRoute = target.route() != null && !target.route().isBlank();
        if (hasJob == hasRoute) {
            throw new IllegalArgumentException(
                    "An httpCall case needs exactly one of httpCall.job or httpCall.route");
        }
        io.tesseraql.yaml.http.HttpOutbound outbound = io.tesseraql.yaml.http.HttpOutbound
                .load(context.manifest().config());
        List<Map.Entry<String, io.tesseraql.yaml.model.HttpCallSpec>> calls = new ArrayList<>();
        if (hasJob) {
            io.tesseraql.yaml.manifest.JobFile job = context.job(target.job());
            for (io.tesseraql.yaml.model.PipelineStep step : job.definition().effectiveSteps()) {
                io.tesseraql.yaml.model.HttpCallSpec spec = step.sql() == null
                        || !step.sql().isHttp()
                                ? null
                                : step.sql().http().call();
                if (spec == null || (target.id() != null && !target.id().equals(step.id()))) {
                    continue;
                }
                calls.add(Map.entry(step.id(), spec));
            }
        } else {
            // A route source whose arm is an outbound call plans the same way a job's step does
            // (docs/connectors.md, "HTTP sources") — url, host, and the allow-list verdict.
            RouteFile route = context.route(target.route());
            route.definition().sources().forEach((name, binding) -> {
                if (binding.isHttp() && (target.id() == null || target.id().equals(name))) {
                    calls.add(Map.entry(name, binding.http().call()));
                }
            });
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        if (target.isSend()) {
            // Real-send mode (docs/testing.md): the request — headers, credential, body — is
            // built exactly as at runtime and goes over a real socket to the runner's capture
            // server; the row carries what actually hit the wire.
            try (CaptureServer capture = CaptureServer.start()) {
                for (var call : calls) {
                    rows.add(sendRow(call.getKey(), call.getValue(), test.params(), outbound,
                            capture));
                }
            }
        } else {
            for (var call : calls) {
                rows.add(planRow(call.getKey(), call.getValue(), test.params(), outbound));
            }
        }
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("'" + (hasJob ? target.job() : target.route())
                    + "' declares no matching httpCall"
                    + (target.id() == null ? "" : " '" + target.id() + "'"));
        }
        return rows;
    }

    /**
     * One real-send row: everything the plan row reports, plus what actually crossed the wire
     * to the capture server — the method and path+query, the credential's Authorization (or
     * custom) header exactly as runtime delivery builds it, declared headers, and the body.
     */
    private Map<String, Object> sendRow(String id, io.tesseraql.yaml.model.HttpCallSpec spec,
            Map<String, Object> params, io.tesseraql.yaml.http.HttpOutbound outbound,
            CaptureServer capture) {
        Map<String, Object> row = planRow(id, spec, params, outbound);
        String url = (String) row.get("url");
        java.net.URI original = java.net.URI.create(url);
        String pathAndQuery = original.getRawPath()
                + (original.getRawQuery() == null ? "" : "?" + original.getRawQuery());
        java.net.http.HttpRequest.Builder request = java.net.http.HttpRequest
                .newBuilder(java.net.URI.create(capture.url() + pathAndQuery))
                .timeout(java.time.Duration.ofSeconds(10));
        spec.headers().forEach(request::header);
        if (spec.credential() != null && !spec.credential().isBlank()) {
            outbound.requireCredential(spec.credential()).authorizationHeaders()
                    .forEach(request::header);
        }
        request.method(spec.effectiveMethod(), spec.body() == null
                ? java.net.http.HttpRequest.BodyPublishers.noBody()
                : java.net.http.HttpRequest.BodyPublishers.ofString(spec.body()));
        try {
            java.net.http.HttpResponse<Void> response = java.net.http.HttpClient.newHttpClient()
                    .send(request.build(),
                            java.net.http.HttpResponse.BodyHandlers.discarding());
            CaptureServer.Captured captured = capture.last();
            row.put("sent", true);
            row.put("requestPath", captured.pathAndQuery());
            row.put("authorization", captured.headers().get("authorization"));
            row.put("requestBody", captured.body());
            row.put("responseStatus", response.statusCode());
        } catch (java.io.IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Real-send failed: " + ex.getMessage(), ex);
        }
        return row;
    }

    /** One planning row: the resolved url/host and the deny-by-default egress verdict. */
    private Map<String, Object> planRow(String id, io.tesseraql.yaml.model.HttpCallSpec spec,
            Map<String, Object> params, io.tesseraql.yaml.http.HttpOutbound outbound) {
        String url = resolveUrl(context.manifest().config(), spec, params);
        String host = hostOf(url);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("http", id);
        row.put("method", spec.effectiveMethod());
        row.put("url", url);
        row.put("host", host);
        row.put("allowed", host != null && outbound.isHostAllowed(host));
        row.put("credential", spec.credential());
        return row;
    }

    /** Resolves a step's url (config placeholders and bound query params) for a planning row. */
    private static String resolveUrl(io.tesseraql.yaml.config.AppConfig config,
            io.tesseraql.yaml.model.HttpCallSpec spec, Map<String, Object> params) {
        String raw = spec.url() == null ? "" : spec.url();
        String base;
        try {
            base = config.resolve(raw);
        } catch (RuntimeException ex) {
            base = raw;
        }
        if (spec.query().isEmpty()) {
            return base;
        }
        io.tesseraql.core.expr.EvaluationContext evaluation = new io.tesseraql.core.expr.EvaluationContext(
                params);
        List<String> pairs = new ArrayList<>();
        spec.query().forEach((name, sourceExpr) -> {
            Object value = evaluation.resolve(java.util.Arrays.asList(sourceExpr.split("\\.")));
            if (value != null) {
                pairs.add(encode(name) + "=" + encode(String.valueOf(value)));
            }
        });
        if (pairs.isEmpty()) {
            return base;
        }
        return base + (base.indexOf('?') >= 0 ? "&" : "?") + String.join("&", pairs);
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String hostOf(String url) {
        if (url == null) {
            return null;
        }
        try {
            return java.net.URI.create(url).getHost();
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
