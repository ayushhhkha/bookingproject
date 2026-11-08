package com.example.booking.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@EnableScheduling   // needed for @Scheduled in OutboxPublisher
public class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    /** 3 partitions: up to 3 consumers of one group can work in parallel. Replication factor 1: dev only. */
    @Bean
    NewTopic bookingEventsTopic(@Value("${app.kafka.topic}") String topic) {
        return TopicBuilder.name(topic).partitions(3).replicas(1).build();
    }

    /** Dead-letter topic: where messages that cannot be processed are parked. */
    @Bean
    NewTopic bookingEventsDeadLetterTopic(@Value("${app.kafka.topic}") String topic) {
        return TopicBuilder.name(topic + ".DLT").partitions(3).replicas(1).build();
    }

    /**
     * What happens when the consumer throws: retry 3 more times, 1 second apart, then park the message
     * in the dead-letter topic and move on (so one bad message can never block everything behind it).
     * Unreadable JSON can never succeed on retry, so it goes to the DLT immediately.
     *
     * Partition -1 = "let Kafka pick the partition from the message key". Spring's default is to reuse the
     * original partition number, which silently BREAKS (the consumer keeps retrying the bad message and
     * blocks its partition) if the DLT has fewer partitions than the main topic. We do not need to keep
     * the partition, so we do not depend on it.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                (ConsumerRecord<?, ?> record, Exception ex) -> new TopicPartition(record.topic() + ".DLT", -1));

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3L));
        handler.addNotRetryableExceptions(JsonProcessingException.class);

        // Make the failure path VISIBLE in the logs. Without this, a failing dead-lettering is almost silent.
        handler.setRetryListeners(new RetryListener() {
            public void failedDelivery(ConsumerRecord<?, ?> record, Exception ex, int deliveryAttempt) {
                log.warn("Kafka message {}-{}@{} failed (attempt {}): {}",
                        record.topic(), record.partition(), record.offset(), deliveryAttempt, rootMessage(ex));
            }

            public void recovered(ConsumerRecord<?, ?> record, Exception ex) {
                log.warn("Kafka message {}-{}@{} moved to the dead-letter topic", record.topic(), record.partition(), record.offset());
            }

            public void recoveryFailed(ConsumerRecord<?, ?> record, Exception original, Exception failure) {
                log.error("Could NOT move Kafka message {}-{}@{} to the dead-letter topic: {}",
                        record.topic(), record.partition(), record.offset(), rootMessage(failure));
            }
        });
        return handler;
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }
}