package com.example.booking;

import com.example.booking.user.AppUser;
import com.example.booking.user.Role;
import com.example.booking.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class DoubleBookingTest extends IntegrationTestBase {

    @Autowired ResourceRepository resources;
    @Autowired SlotRepository slots;
    @Autowired BookingRepository bookings;
    @Autowired UserRepository users;
    @Autowired BookingService service;

    @Test
    void onlyOneBookingWins() throws Exception {
        int attempts = 50;
        Resource court = resources.save(new Resource("Court 1"));
        Instant start = Instant.now().plus(1, ChronoUnit.DAYS);
        Slot slot = slots.save(new Slot(court.getId(), start, start.plus(1, ChronoUnit.HOURS)));

        List<Long> userIds = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            String email = "race-" + UUID.randomUUID() + "@test.dev";
            userIds.add(users.save(new AppUser(email, "not-a-real-hash", "Racer " + i, Role.USER)).getId());
        }

        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();

        List<Future<Void>> futures = new ArrayList<>();
        for (Long userId : userIds) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();  
                try {
                    service.book(slot.getId(), userId);
                    success.incrementAndGet();
                } catch (ResponseStatusException e) {
                    if (e.getStatusCode() == HttpStatus.CONFLICT) conflict.incrementAndGet();
                    else other.incrementAndGet();
                } catch (Exception e) {
                    other.incrementAndGet();
                }
                return null;
            }));
        }
        ready.await();
        go.countDown();
        for (Future<Void> f : futures) f.get(60, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(success.get()).isEqualTo(1);
        assertThat(conflict.get()).isEqualTo(attempts - 1);
        assertThat(other.get()).isZero();
        assertThat(bookings.countBySlotIdAndStatus(slot.getId(), Booking.Status.CONFIRMED)).isEqualTo(1);
    }
}
