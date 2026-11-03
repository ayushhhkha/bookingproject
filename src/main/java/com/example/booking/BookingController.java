package com.example.booking;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    public record NewBooking(@NotNull Long slotId) {}

    private final BookingService service;

    public BookingController(BookingService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Booking book(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody NewBooking body) {
        return service.book(body.slotId(), userId(jwt));
    }

    @PostMapping("/{id}/cancel")
    public Booking cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        boolean isAdmin = "ADMIN".equals(jwt.getClaimAsString("role"));
        return service.cancel(id, userId(jwt), isAdmin);
    }

    @GetMapping("/mine")
    public List<Booking> mine(@AuthenticationPrincipal Jwt jwt) {
        return service.forUser(userId(jwt));
    }

    private static Long userId(Jwt jwt) {
        return Long.valueOf(jwt.getSubject());
    }
}
