package com.example.booking;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/resources")
public class ResourceController {

    public record NewResource(@NotBlank String name) {}
    public record NewSlot(@NotNull Instant startTime, @NotNull Instant endTime) {}

    private final ResourceRepository resources;
    private final SlotRepository slots;

    public ResourceController(ResourceRepository resources, SlotRepository slots) {
        this.resources = resources;
        this.slots = slots;
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
        return slots.save(new Slot(id, body.startTime(), body.endTime()));
    }

    @GetMapping("/{id}/slots/available")
    public List<Slot> available(@PathVariable Long id) {
        return slots.findAvailable(id);
    }
}
