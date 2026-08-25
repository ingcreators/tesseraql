package io.tesseraql.test;

import io.tesseraql.test.TestSuite.TestCase;
import io.tesseraql.yaml.manifest.RouteFile;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The {@code notify} case kind: the fired notifications as rows, evaluated or really sent. */
final class NotifyCases {

    private final SuiteContext context;
    private final MailCapture mail;

    NotifyCases(SuiteContext context, MailCapture mail) {
        this.context = context;
        this.mail = mail;
    }

    /**
     * Evaluates a route's {@code notify:} block or a job's notify steps against the case's
     * params (roadmap Phase 20), returning the fired notifications as rows — id, channel,
     * source, and the resolved payload columns — without touching SMTP or HTTP. Guards
     * ({@code when:}) and payload expressions evaluate exactly as they would at runtime.
     */
    List<Map<String, Object>> evaluate(TestCase test) {
        TestSuite.NotifyTarget target = test.notifications();
        if ((target.route() == null) == (target.job() == null)) {
            throw new IllegalArgumentException(
                    "A notify case needs exactly one of notify.route or notify.job");
        }
        List<io.tesseraql.yaml.notify.NotifyEvents.CompiledNotify> compiled = new ArrayList<>();
        if (target.route() != null) {
            RouteFile route = context.route(target.route());
            route.definition().notifications().forEach((id, spec) -> {
                if (target.id() == null || target.id().equals(id)) {
                    compiled.add(io.tesseraql.yaml.notify.NotifyEvents
                            .compile(target.route(), id, spec));
                }
            });
        } else {
            io.tesseraql.yaml.manifest.JobFile job = context.job(target.job());
            for (io.tesseraql.yaml.model.PipelineStep step : job.definition().effectiveSteps()) {
                if (step.notification() == null
                        || (target.id() != null && !target.id().equals(step.id()))) {
                    continue;
                }
                compiled.add(io.tesseraql.yaml.notify.NotifyEvents
                        .compile(target.job(), step.id(), step.notification()));
            }
        }
        if (compiled.isEmpty()) {
            throw new IllegalArgumentException("'"
                    + (target.route() != null ? target.route() : target.job())
                    + "' declares no matching notification"
                    + (target.id() == null ? "" : " '" + target.id() + "'"));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        try (CaptureServer capture = target.isSend() ? CaptureServer.start() : null) {
            for (io.tesseraql.yaml.notify.NotifyEvents.CompiledNotify notification : compiled) {
                if (!notification.fires(test.params())) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("notify", notification.id());
                row.put("channel", notification.channel());
                row.put("source", notification.source());
                Map<String, Object> payload = notification.resolvePayload(test.params());
                payload.forEach(row::putIfAbsent);
                if (capture != null) {
                    deliver(notification, payload, row, capture);
                }
                rows.add(row);
            }
        }
        return rows;
    }

    /**
     * Real-send mode (docs/testing.md): the production senders build and deliver over a real
     * socket to the runner's capture servers — {@link io.tesseraql.yaml.notify.WebhookNotifier}
     * for webhook channels (JSON body, timestamp header, HMAC signature; rows add
     * {@code delivered}/{@code signature}/{@code wireBody}) and
     * {@link io.tesseraql.yaml.notify.MailNotifier} for mail channels (rendered template body,
     * inline subject, to/from resolution over real SMTP; rows add
     * {@code delivered}/{@code to}/{@code from}/{@code subject}/{@code wireBody}). Inbox
     * channels keep their evaluate-only row — delivery there is a database write the outbox
     * integration tests own.
     */
    private void deliver(io.tesseraql.yaml.notify.NotifyEvents.CompiledNotify notification,
            Map<String, Object> payload, Map<String, Object> row, CaptureServer capture) {
        io.tesseraql.yaml.notify.NotificationChannels channels = io.tesseraql.yaml.notify.NotificationChannels
                .load(context.manifest().config());
        io.tesseraql.yaml.notify.NotificationChannels.Channel channel = channels
                .require(notification.channel());
        io.tesseraql.core.outbox.OutboxEvent event = new io.tesseraql.core.outbox.OutboxEvent(
                "test-" + java.util.UUID.randomUUID(), "notify", notification.id(), "notify",
                null, "PENDING", 0, null, java.time.Instant.now(), null, "test", null, null);
        io.tesseraql.yaml.notify.NotifyEvents.Envelope envelope = new io.tesseraql.yaml.notify.NotifyEvents.Envelope(
                notification.channel(), notification.source(), payload);
        if (io.tesseraql.yaml.notify.NotificationChannels.WEBHOOK.equals(channel.type())) {
            try {
                new io.tesseraql.yaml.notify.WebhookNotifier(captureGateway())
                        .send(channel, envelope, event, capture.url());
                CaptureServer.Captured captured = capture.last();
                row.put("delivered", true);
                row.put("signature", captured.headers().get("x-tesseraql-signature"));
                row.put("wireBody", captured.body());
            } catch (Exception ex) {
                throw new IllegalStateException("Webhook real-send failed: " + ex.getMessage(),
                        ex);
            }
        } else if (io.tesseraql.yaml.notify.NotificationChannels.MAIL.equals(channel.type())) {
            mail.deliver(channel, envelope, event, row);
        }
    }

    /**
     * The gateway a real-send case delivers through. The production path builds and signs the
     * request; only the policy is the harness's, because the destination is the runner's own
     * capture server rather than the app's declared host — applying the app's allow-list to a
     * loopback address would refuse a delivery the app never makes.
     */
    private io.tesseraql.yaml.http.OutboundGateway captureGateway() {
        io.tesseraql.yaml.config.AppConfig harness = new io.tesseraql.yaml.config.AppConfig(
                java.util.Map.of("tesseraql", java.util.Map.of("http", java.util.Map.of(
                        "outbound", java.util.Map.of("allowedHosts",
                                java.util.List.of("localhost", "127.0.0.1"))))),
                name -> null);
        return new io.tesseraql.operations.http.HttpCallClient(
                io.tesseraql.yaml.http.HttpOutbound.load(harness), harness,
                io.tesseraql.core.telemetry.NoopTracer.INSTANCE,
                io.tesseraql.core.telemetry.NoopMeter.INSTANCE);
    }
}
