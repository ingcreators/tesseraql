package io.tesseraql.operations.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.operations.messaging.JdbcEventChannelStore;
import org.junit.jupiter.api.Test;

/**
 * LISTEN/NOTIFY channel names (docs/unicode-identifiers.md): the sanitizer keeps Unicode
 * letters and the statements double-quote the identifier, so a Japanese channel reaches
 * PostgreSQL as a name instead of a syntax error.
 */
class NotifyChannelTest {

    @Test
    void japaneseChannelsKeepTheirNames() {
        assertThat(JdbcEventChannelStore.notifyChannel("受注-events"))
                .isEqualTo("tql_evt_受注_events");
        assertThat(JdbcEventChannelStore.notifyChannel("Order.Events"))
                .isEqualTo("tql_evt_order_events");
    }
}
