package com.example.booking.events;

import com.example.booking.Booking;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class OutboxService {

    private final OutboxRepository outbox;
    private final ObjectMapper mapper;

    public OutboxService(OutboxRepository outbox, ObjectMapper mapper) {
        this.outbox = outbox;
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String eventType, Booking booking, Long resourceId) {
        BookingEvent event = new BookingEvent(UUID.randomUUID(), eventType, booking.getId(),
                booking.getSlotId(), resourceId, booking.getUserId(), Instant.now());
        try {
            outbox.save(new OutboxEvent(event.eventId(), "Booking", booking.getId(), eventType,
                    mapper.writeValueAsString(event)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise " + eventType + " event", e);
        }
    }
}