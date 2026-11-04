package com.example.booking.hold;

import com.example.booking.hold.HoldService.HoldResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/holds")
public class HoldController {

    public record NewHold(@NotNull Long slotId) {}

    private final HoldService holds;

    public HoldController(HoldService holds) {
        this.holds = holds;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public HoldResponse hold(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody NewHold body) {
        return holds.hold(body.slotId(), Long.valueOf(jwt.getSubject()));
    }

    @DeleteMapping("/{slotId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void release(@AuthenticationPrincipal Jwt jwt, @PathVariable Long slotId) {
        holds.release(slotId, Long.valueOf(jwt.getSubject()));
    }
}