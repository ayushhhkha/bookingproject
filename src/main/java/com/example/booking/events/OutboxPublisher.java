package com.example.booking.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final String topic;
    private final int batchSize;

    public OutboxPublisher(OutboxRepository outbox, KafkaTemplate<String, String> kafka,
                           PlatformTransactionManager txManager,
                           @Value("${app.kafka.topic}") String topic,
                           @Value("${app.outbox.batch-size}") int batchSize) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.tx = new TransactionTemplate(txManager);   // explicit transaction (see MASTER.md for why)
        this.topic = topic;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms}")
    public void poll() {
        try {
            publishPending();
        } catch (RuntimeException e) {
            log.warn("Outbox relay run failed, will retry on the next tick ({})", e.getMessage());
        }
    }

    public int publishPending() {
        Integer published = tx.execute(status -> {
            List<OutboxEvent> batch = outbox.lockNextBatch(batchSize);
            int count = 0;
            for (OutboxEvent event : batch) {
                try {
                    kafka.send(topic, String.valueOf(event.getAggregateId()), event.getPayload())
                            .get(5, TimeUnit.SECONDS);
                    event.markPublished();   
                    count++;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.warn("Could not publish outbox event {} ({}). {} event(s) published this run.",
                            event.getId(), e.getMessage(), count);
                    break;
                }
            }
            return count;
        });
        return published == null ? 0 : published;
    }
}