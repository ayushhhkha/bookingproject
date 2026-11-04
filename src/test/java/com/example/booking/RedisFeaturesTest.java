package com.example.booking;

import com.example.booking.cache.AvailabilityService;
import com.example.booking.hold.HoldService;
import com.example.booking.ratelimit.RateLimiter;
import com.example.booking.user.AppUser;
import com.example.booking.user.Role;
import com.example.booking.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedisFeaturesTest extends IntegrationTestBase {

    @Autowired ResourceRepository resources;
    @Autowired SlotRepository slots;
    @Autowired UserRepository users;
    @Autowired BookingService bookingService;
    @Autowired AvailabilityService availability;
    @Autowired HoldService holds;
    @Autowired RateLimiter rateLimiter;
    @Autowired CacheManager cacheManager;


    @Test
    void availabilityIsCachedAndEvictedWhenSomeoneBooks() {
        Resource court = resources.save(new Resource("Cache court"));
        Instant start = Instant.now().plus(1, ChronoUnit.DAYS);
        Slot first = slots.save(new Slot(court.getId(), start, start.plus(1, ChronoUnit.HOURS)));
        AppUser user = newUser();
        Cache cache = cacheManager.getCache(AvailabilityService.CACHE_NAME);

        assertThat(cache.get(court.getId())).isNull();                      
        assertThat(availability.getAvailable(court.getId())).hasSize(1);     
        assertThat(cache.get(court.getId())).isNotNull();                   

        slots.save(new Slot(court.getId(), start.plus(2, ChronoUnit.HOURS), start.plus(3, ChronoUnit.HOURS)));
        assertThat(availability.getAvailable(court.getId())).hasSize(1);

        bookingService.book(first.getId(), user.getId());
        assertThat(cache.get(court.getId())).isNull();

        assertThat(availability.getAvailable(court.getId())).hasSize(1);
    }


    @Test
    void holdBlocksOthersUntilBookedOrReleased() {
        Slot slot = futureSlot();
        AppUser alice = newUser();
        AppUser bob = newUser();

        holds.hold(slot.getId(), alice.getId());
        assertThat(holds.getHolder(slot.getId())).isEqualTo(Optional.of(alice.getId()));

        assertConflict(() -> holds.hold(slot.getId(), bob.getId()));
        assertConflict(() -> bookingService.book(slot.getId(), bob.getId()));
        assertThatThrownBy(() -> holds.release(slot.getId(), bob.getId()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));

        bookingService.book(slot.getId(), alice.getId());
        assertThat(holds.getHolder(slot.getId())).isEmpty();

        assertConflict(() -> holds.hold(slot.getId(), bob.getId()));
    }

    @Test
    void releasedHoldFreesTheSlotForSomeoneElse() {
        Slot slot = futureSlot();
        AppUser alice = newUser();
        AppUser bob = newUser();

        holds.hold(slot.getId(), alice.getId());
        holds.release(slot.getId(), alice.getId());
        assertThat(holds.getHolder(slot.getId())).isEmpty();

        holds.hold(slot.getId(), bob.getId());
        assertThat(holds.getHolder(slot.getId())).isEqualTo(Optional.of(bob.getId()));
    }

    @Test
    void onlyOneOfManyConcurrentHoldsSucceeds() throws Exception {
        int attempts = 20;
        Slot slot = futureSlot();
        List<Long> userIds = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            userIds.add(newUser().getId());
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
                    holds.hold(slot.getId(), userId);
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
        for (Future<Void> f : futures) f.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(success.get()).isEqualTo(1);
        assertThat(conflict.get()).isEqualTo(attempts - 1);
        assertThat(other.get()).isZero();
    }

    @Test
    void rateLimiterAllowsOnlyTheLimitPerWindow() {
        String key = "rate:test:" + UUID.randomUUID();
        Duration window = Duration.ofSeconds(30);

        assertThat(rateLimiter.tryAcquire(key, 3, window)).isTrue();
        assertThat(rateLimiter.tryAcquire(key, 3, window)).isTrue();
        assertThat(rateLimiter.tryAcquire(key, 3, window)).isTrue();
        assertThat(rateLimiter.tryAcquire(key, 3, window)).isFalse();   
        assertThat(rateLimiter.retryAfterSeconds(key)).isBetween(1L, 30L);

        assertThat(rateLimiter.tryAcquire("rate:test:" + UUID.randomUUID(), 3, window)).isTrue();
    }

    private Slot futureSlot() {
        Resource r = resources.save(new Resource("Hold court"));
        Instant start = Instant.now().plus(1, ChronoUnit.DAYS);
        return slots.save(new Slot(r.getId(), start, start.plus(1, ChronoUnit.HOURS)));
    }

    private AppUser newUser() {
        return users.save(new AppUser("redis-" + UUID.randomUUID() + "@test.dev", "not-a-real-hash", "Tester", Role.USER));
    }

    private void assertConflict(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }
}