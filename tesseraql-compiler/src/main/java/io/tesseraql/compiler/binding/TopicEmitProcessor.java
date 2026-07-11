package io.tesseraql.compiler.binding;

import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.core.events.TopicBus;
import io.tesseraql.security.Principal;
import java.util.List;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

/**
 * Broadcasts a command route's {@code emit:} topics after its transaction committed
 * (docs/realtime.md): the processor sits directly after the transactional command in the
 * compiled pipeline, so an exception anywhere in the command (rollback) skips it. The signal
 * carries the topic name only — subscribers refetch through their own authorized routes — and
 * is scoped to the requesting principal's tenant. Without a bound {@link TopicBus} (embedded
 * setups, tests) emitting is a no-op.
 */
public final class TopicEmitProcessor implements Processor {

    private final List<String> topics;

    public TopicEmitProcessor(List<String> topics) {
        this.topics = List.copyOf(topics);
    }

    @Override
    public void process(Exchange exchange) {
        TopicBus bus = exchange.getContext().getRegistry()
                .lookupByNameAndType(TesseraqlProperties.TOPIC_BUS_BEAN, TopicBus.class);
        if (bus == null) {
            return;
        }
        String tenantId = exchange.getProperty(TesseraqlProperties.PRINCIPAL) instanceof Principal p
                ? p.tenantId()
                : null;
        for (String topic : topics) {
            bus.emit(tenantId, topic);
        }
    }
}
