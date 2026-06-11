package io.tesseraql.yaml.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.config.AppConfig;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NotificationChannelsTest {

    private static AppConfig config(Map<String, Object> channels, Map<String, String> env) {
        return new AppConfig(
                Map.of("tesseraql", Map.of("notifications", Map.of("channels", channels))),
                env::get);
    }

    @Test
    void loadsMailAndWebhookChannels() {
        NotificationChannels channels = NotificationChannels.load(config(Map.of(
                "member-mail", Map.of(
                        "type", "mail",
                        "host", "localhost",
                        "port", 2525,
                        "from", "noreply@example.com"),
                "audit-webhook", Map.of(
                        "type", "webhook",
                        "url", "https://hooks.example.com/tessera")),
                Map.of()));

        assertThat(channels.names()).containsExactlyInAnyOrder("member-mail", "audit-webhook");
        assertThat(channels.require("member-mail").type()).isEqualTo("mail");
        assertThat(channels.require("member-mail").require("port")).isEqualTo("2525");
        assertThat(channels.require("audit-webhook").setting("secret")).isEmpty();
    }

    @Test
    void settingsResolvePlaceholdersLazilyPerRead() {
        java.util.Map<String, String> env = new java.util.HashMap<>();
        NotificationChannels channels = NotificationChannels.load(config(Map.of(
                "hook", Map.of("type", "webhook", "url", "https://x", "secret", "${HOOK_SECRET}")),
                env));

        // The secret is not needed (and not resolved) at load time...
        NotificationChannels.Channel hook = channels.require("hook");
        // ...and resolves against the live environment when the delivery reads it.
        env.put("HOOK_SECRET", "s3cr3t");
        assertThat(hook.require("secret")).isEqualTo("s3cr3t");
    }

    @Test
    void anUnsupportedChannelTypeFailsAtLoadTime() {
        assertThatThrownBy(() -> NotificationChannels.load(config(Map.of(
                "pager", Map.of("type", "carrier-pigeon")), Map.of())))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-YAML-1102");
    }

    @Test
    void anUnknownChannelFailsLoudly() {
        NotificationChannels channels = NotificationChannels.load(
                new AppConfig(Map.of(), name -> null));

        assertThat(channels.isEmpty()).isTrue();
        assertThatThrownBy(() -> channels.require("missing"))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-BATCH-5301");
    }
}
