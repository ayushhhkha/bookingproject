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

        List<OutboxEvent> events = outbox.findByAggregateIdOrderByIdAsc(booking.getId());
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getEventType()).isEqualTo(BookingEvent.CREATED);
        assertThat(events.get(0).getPublishedAt()).isNull();

        publisher.publishPending();
        assertThat(outbox.findByAggregateIdOrderByIdAsc(booking.getId()).get(0).getPublishedAt()).isNotNull();

        await().atMost(60, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(notifications.findByUserIdOrderByCreatedAtDesc(alice.getId())).hasSize(1));

        kafka.send(topic, String.valueOf(booking.getId()), events.get(0).getPayload()).get(10, TimeUnit.SECONDS);

        bookingService.cancel(booking.getId(), alice.getId(), false);
        publisher.publishPending();

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
            bookingService.book(slot.getId(), bob.getId());   
        } catch (Exception expected) {
        }

        assertThat(outbox.count()).isEqualTo(eventsBefore);  
    }

    @Test
    void unreadableMessageEndsUpInTheDeadLetterTopic() throws Exception {
        String poison = "this-is-not-json-" + UUID.randomUUID();
        kafka.send(topic, "poison", poison).get(10, TimeUnit.SECONDS);

        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-test-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        boolean found = false;
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic + ".DLT"));
            long deadline = System.currentTimeMillis() + 60_000;
            while (!found && System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofSeconds(1))) {
                    if (poison.equals(record.value())) {
                        found = true;
                    }
                }
            }
        }
        assertThat(found).as("poison message should arrive in %s.DLT", topic).isTrue();
    }


    private Slot futureSlot() {
        Resource r = resources.save(new Resource("Kafka court"));
        Instant start = Instant.now().plus(1, ChronoUnit.DAYS);
        return slots.save(new Slot(r.getId(), start, start.plus(1, ChronoUnit.HOURS)));
    }

    private AppUser newUser() {
        return users.save(new AppUser("kafka-" + UUID.randomUUID() + "@test.dev", "not-a-real-hash", "Tester", Role.USER));
    }
}