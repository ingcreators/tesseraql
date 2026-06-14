package io.tesseraql.yaml.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.config.AppConfig;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Loads the Phase 27 {@code tesseraql.messaging.channels} config, failing fast on a bad transport. */
class MessagingChannelsTest {

    private static AppConfig config(Map<String, Object> channels) {
        Map<String, Object> root = Map.of("tesseraql",
                Map.of("messaging", Map.of("channels", channels)));
        return new AppConfig(root, name -> null);
    }

    @Test
    void loadsAPgNotifyChannelAndDefaultsTheTransport() {
        MessagingChannels channels = MessagingChannels.load(config(Map.of(
                "events", Map.of("transport", "pg-notify"),
                "audit", Map.of())));

        assertThat(channels.isEmpty()).isFalse();
        assertThat(channels.require("events").transport()).isEqualTo(MessagingChannels.PG_NOTIFY);
        // An unspecified transport defaults to the built-in pg-notify.
        assertThat(channels.require("audit").transport()).isEqualTo(MessagingChannels.PG_NOTIFY);
    }

    @Test
    void requireFailsFastForAnUnknownChannel() {
        MessagingChannels channels = MessagingChannels.load(config(Map.of(
                "events", Map.of("transport", "pg-notify"))));
        assertThatThrownBy(() -> channels.require("missing"))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-YAML-1106");
    }

    @Test
    void rejectsAnUnknownTransportAtLoad() {
        assertThatThrownBy(() -> MessagingChannels.load(config(Map.of(
                "events", Map.of("transport", "kafka")))))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-YAML-1106");
    }
}
