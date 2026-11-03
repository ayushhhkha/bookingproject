package com.example.booking.auth;

import com.example.booking.user.AppUser;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthDtos {
    private AuthDtos() {}

    public record RegisterRequest(
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(min = 8, max = 72) String password,   
            @NotBlank @Size(max = 120) String name) {}

    public record LoginRequest(
            @NotBlank String email,
            @NotBlank String password) {}

    public record UserResponse(Long id, String email, String name, String role) {
        public static UserResponse from(AppUser u) {
            return new UserResponse(u.getId(), u.getEmail(), u.getName(), u.getRole().name());
        }
    }

    public record TokenResponse(String token, String tokenType, long expiresInSeconds) {}
}
