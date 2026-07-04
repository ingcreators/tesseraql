package io.tesseraql.yaml.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.outbox.OutboxEvent;
import io.tesseraql.yaml.model.NotifySpec;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NotifyEventsTest {

    @Test
    void buildsAnOutboxEventCarryingTheEnvelope() {
        NotifyEvents.CompiledNotify notify = NotifyEvents.compile("members.register",
                "confirmation",
                new NotifySpec("member-mail", null, Map.of("email", "body.email")));

        OutboxEvent event = notify.build(Map.of("body", Map.of("email", "a@example.com")), "app");

        assertThat(event.eventType()).isEqualTo(NotifyEvents.EVENT_TYPE);
        assertThat(event.aggregateType()).isEqualTo(NotifyEvents.AGGREGATE_TYPE);
        assertThat(event.aggregateId()).isEqualTo("members.register.confirmation");
        assertThat(event.appName()).isEqualTo("app");
        assertThat(NotifyEvents.isNotification(event)).isTrue();

        NotifyEvents.Envelope envelope = NotifyEvents.parse(event.payloadJson());
        assertThat(envelope.channel()).isEqualTo("member-mail");
        assertThat(envelope.source()).isEqualTo("members.register.confirmation");
        assertThat(envelope.payload()).containsEntry("email", "a@example.com");
    }

    /** Roadmap Phase 48: the recipient expression resolves against the live context. */
    @Test
    void theRecipientResolvesFromTheContextAndTheOptOutDecisionUsesIt() {
        NotifyEvents.CompiledNotify addressed = NotifyEvents.compile("r", "n",
                new NotifySpec("user-mail", null, "principal.subject", Map.of()));
        Map<String, Object> context = Map.of("principal", Map.of("subject", "alice"));

        assertThat(addressed.resolveRecipient(context)).isEqualTo("alice");
        // Without a recipient the notification is channel-level: never consults preferences.
        NotifyEvents.CompiledNotify channelLevel = NotifyEvents.compile("r", "n",
                new NotifySpec("user-mail", null, Map.of()));
        assertThat(channelLevel.resolveRecipient(context)).isNull();

        io.tesseraql.core.account.PreferenceStore optedOut = new io.tesseraql.core.account.PreferenceStore() {
            @Override
            public Map<String, String> preferences(String tenantId, String subject) {
                return "alice".equals(subject)
                        ? Map.of("notify.user-mail.optOut", "true")
                        : Map.of();
            }

            @Override
            public void put(String tenantId, String subject, String key, String value) {
            }

            @Override
            public void remove(String tenantId, String subject, String key) {
            }
        };
        assertThat(NotifyOptOut.optedOut(addressed, context, optedOut, null)).isTrue();
        assertThat(NotifyOptOut.optedOut(channelLevel, context, optedOut, null)).isFalse();
        assertThat(NotifyOptOut.optedOut(addressed, context, null, null)).isFalse();
    }

    @Test
    void theWhenGuardSkipsTheNotification() {
        NotifyEvents.CompiledNotify guarded = NotifyEvents.compile("r", "n",
                new NotifySpec("c", "body.active == true", Map.of()));

        assertThat(guarded.fires(Map.of("body", Map.of("active", true)))).isTrue();
        assertThat(guarded.fires(Map.of("body", Map.of("active", false)))).isFalse();
    }

    @Test
    void aMissingChannelFailsAtCompileTime() {
        assertThatThrownBy(() -> NotifyEvents.compile("r", "n",
                new NotifySpec(null, null, Map.of())))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-FIELD-2004");
    }

    @Test
    void compileAllKeepsTheAuthoredOrder() {
        java.util.Map<String, NotifySpec> block = new java.util.LinkedHashMap<>();
        block.put("first", new NotifySpec("a", null, Map.of()));
        block.put("second", new NotifySpec("b", null, Map.of()));

        var compiled = NotifyEvents.compileAll("r", block);

        assertThat(compiled).extracting(NotifyEvents.CompiledNotify::id)
                .containsExactly("first", "second");
    }
}
