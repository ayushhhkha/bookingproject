package com.example.booking.events;

import com.example.booking.notification.NotificationService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class BookingEventConsumer {

    private final ObjectMapper mapper;
    private final NotificationService notifications;

    public BookingEventConsumer(ObjectMapper mapper, NotificationService notifications) {
        this.mapper = mapper;
        this.notifications = notifications;
    }

    @KafkaListener(topics = "${app.kafka.topic}")
    public void onMessage(String payload) throws JsonProcessingException {
        BookingEvent event = mapper.readValue(payload, BookingEvent.class);
        notifications.process(event);
    }
}