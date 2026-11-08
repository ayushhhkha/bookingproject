package com.example.booking;

import com.example.booking.events.BookingEvent;
import com.example.booking.events.OutboxEvent;
import com.example.booking.events.OutboxPublisher;
import com.example.booking.events.OutboxRepository;
import com.example.booking.notification.Notification;
import com.example.booking.notification.NotificationRepository;
import com.example.booking.user.AppUser;
import com.example.booking.user.Role;
import com.example.booking.user.UserRepository;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Tests the whole event pipeline against real PostgreSQL and real Kafka: outbox -> relay -> topic -> consumer. */
class KafkaOutboxTest extends IntegrationTestBase {

    @Autowired ResourceRepository resources;
    @Autowired SlotRepository slots;
    @Autowired UserRepository users;
    @Autowired BookingService bookingService;
    @Autowired OutboxRepository outbox;
    @Autowired OutboxPublisher publisher;
    @Autowired NotificationRepository notifications;
    @Autowired KafkaTemplate<String, String> kafka;
    @Value("${app.kafka.topic}") String topic;

    @Test
    void bookingWritesAnOutboxEventThatBecomesExactlyOneNotificationPerEvent() throws Exception {
        AppUser alice = newUser();
        Slot slot = futureSlot();

        Booking booking = bookingService.book(slot.getId(), alice.getId());

        // 1. the event was saved together with the booking, and nothing has been sent to Kafka yet
        List<OutboxEvent> events = outbox.findByAggregateIdOrderByIdAsc(booking.getId());
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getEventType()).isEqualTo(BookingEvent.CREATED);
        assertThat(events.get(0).getPublishedAt()).isNull();

        // 2. the relay publishes it (this is what the scheduler does every second)
        publisher.publishPending();
        assertThat(outbox.findByAggregateIdOrderByIdAsc(booking.getId()).get(0).getPublishedAt()).isNotNull();

        // 3. the consumer turns it into a notification
        await().atMost(60, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(notifications.findByUserIdOrderByCreatedAtDesc(alice.getId())).hasSize(1));

        // 4. redelivery of the SAME event (at-least-once delivery means this can happen in real life)
        kafka.send(topic, String.valueOf(booking.getId()), events.get(0).getPayload()).get(10, TimeUnit.SECONDS);

        // 5. a follow-up event for the same booking. Same key = same partition, so it is consumed AFTER the duplicate.
        bookingService.cancel(booking.getId(), alice.getId(), false);
        publisher.publishPending();

        // Exactly 2 notifications (created + cancelled): the duplicate was recognised and skipped.
        await().atMost(60, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(notifications.findByUserIdOrderByCreatedAtDesc(alice.getId())).hasSize(2));
        assertThat(notifications.findByUserIdOrderByCreatedAtDesc(alice.getId()))
                .extracting(Notification::getType)
                .containsExactlyInAnyOrder(BookingEvent.CREATED, BookingEvent.CANCELLED);
    }

    @Test
    void cancellingTwiceRecordsOnlyOneCancelledEvent() {
        AppUser bob = newUser();
        Booking booking = bookingService.book(futureSlot().getId(), bob.getId());

        bookingService.cancel(booking.getId(), bob.getId(), false);
        bookingService.cancel(booking.getId(), bob.getId(), false);   // second call changes nothing

        List<OutboxEvent> events = outbox.findByAggregateIdOrderByIdAsc(booking.getId());
        assertThat(events).extracting(OutboxEvent::getEventType)
                .containsExactly(BookingEvent.CREATED, BookingEvent.CANCELLED);
    }

    @Test
    void failedBookingWritesNoEvent() {
        AppUser alice = newUser();
        AppUser bob = newUser();
        Slot slot = futureSlot();
        bookingService.book(slot.getId(), alice.getId());
        long eventsBefore = outbox.count();

        try {
            bookingService.book(slot.getId(), bob.getId());   // loses: 409
        } catch (Exception expected) {
            // ignored on purpose
        }

        assertThat(outbox.count()).isEqualTo(eventsBefore);   // rolled back, no ghost event
    }

    @Test
    void unreadableMessageEndsUpInTheDeadLetterTopic() throws Exception {
        String dlt = topic + ".DLT";
        String poison = "this-is-not-json-" + UUID.randomUUID();

        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-test-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        // never let this test consumer create the topic itself: the APPLICATION must have created it
        props.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, "false");

        boolean found = false;
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(dlt));

            // 1. wait until the DLT topic exists and our consumer has partitions, BEFORE sending anything
            long assignDeadline = System.currentTimeMillis() + 30_000;
            while (consumer.assignment().isEmpty() && System.currentTimeMillis() < assignDeadline) {
                consumer.poll(Duration.ofMillis(500));
            }
            assertThat(consumer.assignment()).as("dead-letter topic %s should exist (created by KafkaConfig)", dlt).isNotEmpty();

            // 2. send the poison message
            kafka.send(topic, "poison", poison).get(10, TimeUnit.SECONDS);

            // 3. it must arrive in the DLT (retries are skipped for unreadable JSON, so this is fast)
            long deadline = System.currentTimeMillis() + 60_000;
            while (!found && System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofSeconds(1))) {
                    if (poison.equals(record.value())) {
                        found = true;
                    }
                }
            }
        }
        assertThat(found).as("poison message should arrive in %s", dlt).isTrue();
    }

    // ---- helpers ----

    private Slot futureSlot() {
        Resource r = resources.save(new Resource("Kafka court"));
        Instant start = Instant.now().plus(1, ChronoUnit.DAYS);
        return slots.save(new Slot(r.getId(), start, start.plus(1, ChronoUnit.HOURS)));
    }

    private AppUser newUser() {
        return users.save(new AppUser("kafka-" + UUID.randomUUID() + "@test.dev", "not-a-real-hash", "Tester", Role.USER));
    }
}