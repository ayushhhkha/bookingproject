package com.example.booking;

import com.example.booking.cache.AvailabilityCache;
import com.example.booking.events.BookingEvent;
import com.example.booking.events.OutboxService;
import com.example.booking.hold.HoldService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class BookingService {

    private final BookingRepository bookings;
    private final SlotRepository slots;
    private final HoldService holds;
    private final AvailabilityCache availabilityCache;
    private final OutboxService outbox;

    public BookingService(BookingRepository bookings, SlotRepository slots, HoldService holds,
                          AvailabilityCache availabilityCache, OutboxService outbox) {
        this.bookings = bookings;
        this.slots = slots;
        this.holds = holds;
        this.availabilityCache = availabilityCache;
        this.outbox = outbox;
    }

    @Transactional
    public Booking book(Long slotId, Long userId) {
        Slot slot = slots.findById(slotId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Slot " + slotId + " not found"));
        if (!slot.getStartTime().isAfter(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot book a slot that has already started");
        }

        Optional<Long> holder = holds.getHolder(slotId);
        if (holder.isPresent() && !holder.get().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Slot " + slotId + " is currently held by another user");
        }

        Booking saved;
        try {
            saved = bookings.saveAndFlush(new Booking(slotId, userId));
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Slot " + slotId + " is already booked");
        }
        outbox.record(BookingEvent.CREATED, saved, slot.getResourceId());

        availabilityCache.evictAfterCommit(slot.getResourceId());   // the free-slot list just changed
        holds.releaseQuietly(slotId, userId);                       // my hold has served its purpose
        return saved;
    }

    @Transactional
    public Booking cancel(Long bookingId, Long currentUserId, boolean isAdmin) {
        Booking booking = bookings.findById(bookingId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Booking " + bookingId + " not found"));
        if (!isAdmin && !booking.getUserId().equals(currentUserId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only cancel your own bookings");
        }
        if (booking.getStatus() == Booking.Status.CANCELLED) {
            return booking; 
        }

        booking.cancel();  
        Slot slot = slots.findById(booking.getSlotId()).orElseThrow();   
        availabilityCache.evictAfterCommit(slot.getResourceId());      
        outbox.record(BookingEvent.CANCELLED, booking, slot.getResourceId());
        return booking;
    }

    @Transactional(readOnly = true)
    public List<Booking> forUser(Long userId) {
        return bookings.findByUserIdOrderByCreatedAtDesc(userId);
    }
}
