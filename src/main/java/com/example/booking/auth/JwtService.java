package com.example.booking.auth;

import com.example.booking.user.AppUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class JwtService {

    private final JwtEncoder encoder;
    private final long ttlSeconds;

    public JwtService(JwtEncoder encoder, @Value("${app.jwt.ttl-seconds}") long ttlSeconds) {
        this.encoder = encoder;
        this.ttlSeconds = ttlSeconds;
    }

    public String createToken(AppUser user) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("booking-app")
                .subject(String.valueOf(user.getId()))   
                .issuedAt(now)                           
                .expiresAt(now.plusSeconds(ttlSeconds))  
                .claim("email", user.getEmail())
                .claim("role", user.getRole().name())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    public long getTtlSeconds() {
        return ttlSeconds;
    }
}
