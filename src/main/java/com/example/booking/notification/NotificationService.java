package com.example.booking.notification;

import com.example.booking.events.BookingEvent;
import com.example.booking.user.AppUser;
import com.example.booking.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notifications;
    private final UserRepository users;
    private final EmailSender email;

    public NotificationService(NotificationRepository notifications, UserRepository users, EmailSender email) {
        this.notifications = notifications;
        this.users = users;
        this.email = email;
    }

    @Transactional
    public void process(BookingEvent event) {
        AppUser user = users.findById(event.userId()).orElse(null);
        if (user == null) {
            log.warn("User {} not found, skipping event {}", event.userId(), event.eventId());
            return;
        }
        String message = messageFor(event);
        int inserted = notifications.insertIfAbsent(event.eventId(), user.getId(), event.type(), message);
        if (inserted == 0) {
            log.info("Event {} was already processed, skipping (idempotent consumer)", event.eventId());
            return;
        }
        email.send(user.getEmail(), subjectFor(event), message);
    }

    @Transactional(readOnly = true)
    public List<Notification> forUser(Long userId) {
        return notifications.findByUserIdOrderByCreatedAtDesc(userId);
    }

    private static String subjectFor(BookingEvent event) {
        return BookingEvent.CANCELLED.equals(event.type()) ? "Booking cancelled" : "Booking confirmed";
    }

    private static String messageFor(BookingEvent event) {
        if (BookingEvent.CREATED.equals(event.type())) {
            return "Your booking #" + event.bookingId() + " for slot #" + event.slotId() + " is confirmed.";
        }
        if (BookingEvent.CANCELLED.equals(event.type())) {
            return "Your booking #" + event.bookingId() + " for slot #" + event.slotId() + " was cancelled.";
        }
        return "Update on your booking #" + event.bookingId() + ".";
    }
}