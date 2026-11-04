package com.example.booking.hold;

import com.example.booking.Booking;
import com.example.booking.BookingRepository;
import com.example.booking.Slot;
import com.example.booking.SlotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class HoldService {

    private static final Logger log = LoggerFactory.getLogger(HoldService.class);

    private static final DefaultRedisScript<Long> RELEASE_IF_OWNER = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            else
                return 0
            end
            """, Long.class);

    public record HoldResponse(Long slotId, long expiresInSeconds) {}

    private final StringRedisTemplate redis;
    private final SlotRepository slots;
    private final BookingRepository bookings;
    private final Duration ttl;

    public HoldService(StringRedisTemplate redis, SlotRepository slots, BookingRepository bookings,
                       @Value("${app.holds.ttl-seconds}") long ttlSeconds) {
        this.redis = redis;
        this.slots = slots;
        this.bookings = bookings;
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    public HoldResponse hold(Long slotId, Long userId) {
        Slot slot = slots.findById(slotId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Slot " + slotId + " not found"));
        if (!slot.getStartTime().isAfter(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot hold a slot that has already started");
        }
        if (bookings.countBySlotIdAndStatus(slotId, Booking.Status.CONFIRMED) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Slot " + slotId + " is already booked");
        }

        String key = key(slotId);
        String me = String.valueOf(userId);
        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, me, ttl))) {
                    return new HoldResponse(slotId, ttl.toSeconds());
                }
                String holder = redis.opsForValue().get(key);
                if (me.equals(holder)) {
                    redis.expire(key, ttl);   // my own hold: extend it
                    return new HoldResponse(slotId, ttl.toSeconds());
                }
                if (holder != null) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Slot " + slotId + " is held by another user");
                }
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Slot " + slotId + " is busy, please retry");
        } catch (DataAccessException e) {
            log.warn("Redis unavailable while holding slot {} ({})", slotId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Holds are temporarily unavailable");
        }
    }

    public void release(Long slotId, Long userId) {
        try {
            String holder = redis.opsForValue().get(key(slotId));
            if (holder != null && !holder.equals(String.valueOf(userId))) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only release your own hold");
            }
            redis.execute(RELEASE_IF_OWNER, List.of(key(slotId)), String.valueOf(userId));
        } catch (DataAccessException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Holds are temporarily unavailable");
        }
    }

    public Optional<Long> getHolder(Long slotId) {
        try {
            String holder = redis.opsForValue().get(key(slotId));
            return holder == null ? Optional.empty() : Optional.of(Long.valueOf(holder));
        } catch (DataAccessException e) {
            log.warn("Redis unavailable, ignoring holds for slot {} ({})", slotId, e.getMessage());
            return Optional.empty();
        }
    }

    public void releaseQuietly(Long slotId, Long userId) {
        try {
            redis.execute(RELEASE_IF_OWNER, List.of(key(slotId)), String.valueOf(userId));
        } catch (DataAccessException e) {
            log.warn("Could not release hold for slot {} ({})", slotId, e.getMessage());
        }
    }

    public Set<Long> heldSlotIds(Collection<Long> slotIds) {
        if (slotIds.isEmpty()) {
            return Set.of();
        }
        List<Long> ids = new ArrayList<>(slotIds);
        try {
            List<String> values = redis.opsForValue().multiGet(ids.stream().map(HoldService::key).toList());
            Set<Long> held = new HashSet<>();
            for (int i = 0; values != null && i < ids.size(); i++) {
                if (values.get(i) != null) {
                    held.add(ids.get(i));
                }
            }
            return held;
        } catch (DataAccessException e) {
            log.warn("Redis unavailable, showing no holds ({})", e.getMessage());
            return Set.of();
        }
    }

    private static String key(Long slotId) {
        return "hold:slot:" + slotId;
    }
}