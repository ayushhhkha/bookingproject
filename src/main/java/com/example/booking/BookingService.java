package com.example.booking;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

@Service
public class BookingService {

    private final BookingRepository bookings;
    private final SlotRepository slots;

    public BookingService(BookingRepository bookings, SlotRepository slots) {
        this.bookings = bookings;
        this.slots = slots;
    }

    @Transactional
    public Booking book(Long slotId, Long userId) {
        Slot slot = slots.findById(slotId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Slot " + slotId + " not found"));
        if (!slot.getStartTime().isAfter(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot book a slot that has already started");
        }
        try {
            return bookings.saveAndFlush(new Booking(slotId, userId));
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Slot " + slotId + " is already booked");
        }
    }

    @Transactional
    public Booking cancel(Long bookingId, Long currentUserId, boolean isAdmin) {
        Booking booking = bookings.findById(bookingId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Booking " + bookingId + " not found"));
        if (!isAdmin && !booking.getUserId().equals(currentUserId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only cancel your own bookings");
        }
        booking.cancel();   
        return booking;
    }

    @Transactional(readOnly = true)
    public List<Booking> forUser(Long userId) {
        return bookings.findByUserIdOrderByCreatedAtDesc(userId);
    }
}
