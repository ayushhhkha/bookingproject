package com.example.booking.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;

@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final RateLimiter limiter;
    private final int loginLimit;
    private final int bookingLimit;

    public RateLimitInterceptor(RateLimiter limiter,
                                @Value("${app.rate-limit.login-per-minute}") int loginLimit,
                                @Value("${app.rate-limit.booking-per-minute}") int bookingLimit) {
        this.limiter = limiter;
        this.loginLimit = loginLimit;
        this.bookingLimit = bookingLimit;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!"POST".equals(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        String key;
        int limit;
        if (path.equals("/api/auth/login")) {
            key = "rate:login:" + request.getRemoteAddr();
            limit = loginLimit;
        } else if (path.equals("/api/bookings")) {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null) {
                return true;
            }
            key = "rate:booking:" + auth.getName();   
            limit = bookingLimit;
        } else {
            return true;
        }

        if (!limiter.tryAcquire(key, limit, WINDOW)) {
            response.setHeader("Retry-After", String.valueOf(limiter.retryAfterSeconds(key)));
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests. Please slow down.");
        }
        return true;
    }
}