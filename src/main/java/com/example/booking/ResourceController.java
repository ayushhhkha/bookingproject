package com.example.booking;

import com.example.booking.cache.AvailabilityCache;
import com.example.booking.cache.AvailabilityService;
import com.example.booking.cache.AvailabilityService.SlotView;
import com.example.booking.hold.HoldService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/resources")
public class ResourceController {

    public record NewResource(@NotBlank String name) {}
    public record NewSlot(@NotNull Instant startTime, @NotNull Instant endTime) {}
    public record SlotAvailability(Long id, Long resourceId, Instant startTime, Instant endTime, boolean held) {}

    private final ResourceRepository resources;
    private final SlotRepository slots;
    private final AvailabilityService availability;
    private final AvailabilityCache availabilityCache;
    private final HoldService holds;

    public ResourceController(ResourceRepository resources, SlotRepository slots,
                              AvailabilityService availability, AvailabilityCache availabilityCache,
                              HoldService holds) {
        this.resources = resources;
        this.slots = slots;
        this.availability = availability;
        this.availabilityCache = availabilityCache;
        this.holds = holds;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Resource create(@Valid @RequestBody NewResource body) {
        return resources.save(new Resource(body.name()));
    }

    @GetMapping
    public List<Resource> list() {
        return resources.findAll();
    }

    @PostMapping("/{id}/slots")
    @ResponseStatus(HttpStatus.CREATED)
    public Slot addSlot(@PathVariable Long id, @Valid @RequestBody NewSlot body) {
        if (!resources.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Resource " + id + " not found");
        }
        if (!body.endTime().isAfter(body.startTime())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "endTime must be after startTime");
        }
        Slot saved = slots.save(new Slot(id, body.startTime(), body.endTime()));
        availabilityCache.evictAfterCommit(id);   // a new slot changes the availability of this resource
        return saved;
    }

    @GetMapping("/{id}/slots/available")
    public List<SlotAvailability> available(@PathVariable Long id) {
        List<SlotView> free = availability.getAvailable(id);
        Set<Long> held = holds.heldSlotIds(free.stream().map(SlotView::id).toList());
        return free.stream()
                .map(s -> new SlotAvailability(s.id(), s.resourceId(), s.startTime(), s.endTime(), held.contains(s.id())))
                .toList();
    }
}