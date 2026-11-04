package com.example.booking.cache;

import com.example.booking.SlotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class AvailabilityService {

    public static final String CACHE_NAME = "availability";

    private static final Logger log = LoggerFactory.getLogger(AvailabilityService.class);

    public record SlotView(Long id, Long resourceId, Instant startTime, Instant endTime) implements Serializable {}

    private final SlotRepository slots;

    public AvailabilityService(SlotRepository slots) {
        this.slots = slots;
    }

    
    @Cacheable(cacheNames = CACHE_NAME, key = "#resourceId")
    public List<SlotView> getAvailable(Long resourceId) {
        log.info("Availability cache MISS for resource {} - querying PostgreSQL", resourceId);
        return slots.findAvailable(resourceId).stream()
                .map(s -> new SlotView(s.getId(), s.getResourceId(), s.getStartTime(), s.getEndTime()))
                .collect(Collectors.toCollection(ArrayList::new));
    }
}