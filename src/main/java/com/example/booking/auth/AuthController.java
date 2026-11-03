package com.example.booking.auth;

import com.example.booking.auth.AuthDtos.*;
import com.example.booking.user.UserRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class AuthController {

    private final AuthService auth;
    private final UserRepository users;

    public AuthController(AuthService auth, UserRepository users) {
        this.auth = auth;
        this.users = users;
    }

    @PostMapping("/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse register(@Valid @RequestBody RegisterRequest req) {
        return UserResponse.from(auth.register(req.email(), req.password(), req.name()));
    }

    @PostMapping("/auth/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest req) {
        return auth.login(req.email(), req.password());
    }

    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal Jwt jwt) {
        return users.findById(Long.valueOf(jwt.getSubject()))
                .map(UserResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User no longer exists"));
    }
}
