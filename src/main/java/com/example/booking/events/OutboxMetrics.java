package com.example.booking.events;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class OutboxMetrics {

    public OutboxMetrics(MeterRegistry registry, OutboxRepository outbox) {
        Gauge.builder("outbox.pending", outbox, o -> o.countByPublishedAtIsNull())
                .description("Outbox events not yet published to Kafka")
                .register(registry);
    }
}