package com.example.booking.events;

import java.time.Instant;
import java.util.UUID;

public record BookingEvent(
        UUID eventId,
        String type,
        Long bookingId,
        Long slotId,
        Long resourceId,
        Long userId,
        Instant occurredAt) {

    public static final String CREATED = "BookingCreated";
    public static final String CANCELLED = "BookingCancelled";
}