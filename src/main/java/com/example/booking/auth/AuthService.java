package com.example.booking.auth;

import com.example.booking.auth.AuthDtos.TokenResponse;
import com.example.booking.user.AppUser;
import com.example.booking.user.Role;
import com.example.booking.user.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;

@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional
    public AppUser register(String email, String rawPassword, String name) {
        String normalized = normalize(email);
        if (users.existsByEmail(normalized)) {
            throw emailTaken();
        }
        try {
            return users.saveAndFlush(
                    new AppUser(normalized, passwordEncoder.encode(rawPassword), name, Role.USER));
        } catch (DataIntegrityViolationException e) {
            throw emailTaken();  
        }
    }

    @Transactional(readOnly = true)
    public TokenResponse login(String email, String rawPassword) {
        AppUser user = users.findByEmail(normalize(email))
                .filter(u -> passwordEncoder.matches(rawPassword, u.getPasswordHash()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password"));
        return new TokenResponse(jwtService.createToken(user), "Bearer", jwtService.getTtlSeconds());
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static ResponseStatusException emailTaken() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Email already registered");
    }
}
